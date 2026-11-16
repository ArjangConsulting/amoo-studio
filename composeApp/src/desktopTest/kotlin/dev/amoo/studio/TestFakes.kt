package dev.amoo.studio

import com.maniramezan.credentialkeychain.CredentialKeychain
import io.github.maniramezan.processrpc.ProcessRpcException
import io.github.maniramezan.processrpc.ProcessRpcState
import io.github.maniramezan.processrpc.RpcNotification
import io.github.maniramezan.testcommons.CallRecorder
import io.github.maniramezan.testcommons.ScriptedResponder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

internal fun json(text: String): JsonElement = Json.parseToJsonElement(text)
internal fun ok(text: String): Result<JsonElement> = Result.success(json(text))
internal fun failure(message: String): Result<JsonElement> = Result.failure(ProcessRpcException(rpcCode = -32000, message = message))

internal data class RecordedCall(val method: String, val params: JsonElement, val timeoutMs: Long?)

/** An in-memory `amoo studio serve`: each method answers from a [ScriptedResponder], and nothing else is allowed. */
internal class FakeRpc : StudioRpc {
	override val state = MutableStateFlow<ProcessRpcState>(ProcessRpcState.Stopped)
	private val pushed = MutableSharedFlow<RpcNotification>(extraBufferCapacity = 64)
	override val notifications: SharedFlow<RpcNotification> get() = pushed
	val calls = CallRecorder<RecordedCall>()
	val responders = mutableMapOf<String, ScriptedResponder<JsonElement, JsonElement>>()
	var starts = 0
	var closed = false

	fun reply(method: String, outcomes: List<Result<JsonElement>>, fallback: Result<JsonElement>? = null): ScriptedResponder<JsonElement, JsonElement> =
		ScriptedResponder<JsonElement, JsonElement>(outcomes, fallback).also { responders[method] = it }

	/** Answers every call to [method] with [outcome]. */
	fun reply(method: String, outcome: Result<JsonElement>): ScriptedResponder<JsonElement, JsonElement> = reply(method, emptyList(), fallback = outcome)

	fun notify(method: String, params: String) {
		check(pushed.tryEmit(RpcNotification(method, json(params)))) { "notification buffer is full" }
	}

	fun count(method: String) = calls.values().count { it.method == method }

	fun requests(method: String): List<JsonElement> = calls.values().filter { it.method == method }.map { it.params }

	override fun start() { starts++ }

	override suspend fun call(method: String, params: JsonElement, timeoutMs: Long?): JsonElement {
		calls.record(RecordedCall(method, params, timeoutMs))
		val responder = responders[method] ?: throw ProcessRpcException(rpcCode = -32601, message = "No scripted response for $method")
		return responder.respond(params)
	}

	override fun close() { closed = true }
}

internal class FakeHost(var amooMissing: Boolean = false) : StudioHost {
	override val platform = HostPlatform.MacOS
	override fun locateAmoo() = "/fake/amoo"
	override fun locateHomebrew() = "/fake/brew"
	override fun isAmooMissing() = amooMissing
}

internal class FakeFilePicker : FilePicker {
	var toOpen: File? = null
	var toSave: File? = null
	var project: File? = null
	val prompts = CallRecorder<String>()

	override fun chooseFileToOpen(title: String, pattern: String): File? = toOpen.also { prompts.record("open:$title") }
	override fun chooseFileToSave(title: String, initialName: String): File? = toSave.also { prompts.record("save:$title:$initialName") }
	override fun chooseProject(): File? = project.also { prompts.record("project") }
}

internal class FakeKeychain : CredentialKeychain {
	val entries = mutableMapOf<String, String>()
	val readFailures = mutableMapOf<String, Throwable>()
	var mutationFailure: Throwable? = null
	val reads = CallRecorder<String>()
	val deletions = CallRecorder<String>()
	val clears = CallRecorder<Unit>()

	override fun read(key: String): String? {
		reads.record(key)
		readFailures[key]?.let { throw it }
		return entries[key]
	}

	override fun write(key: String, value: String) {
		mutationFailure?.let { throw it }
		require(value.isNotBlank()) { "blank values are rejected" }
		entries[key] = value
	}

	override fun delete(key: String) {
		mutationFailure?.let { throw it }
		deletions.record(key)
		entries.remove(key)
	}

	override fun clear() {
		clears.record(Unit)
		entries.clear()
	}
}
