package dev.amoo.studio

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.maniramezan.processrpc.BundledBinaryLocator
import io.github.maniramezan.processrpc.ProcessRpcClient
import io.github.maniramezan.processrpc.ProcessRpcState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

@Serializable
private data class Handshake(
	val protocolVersion: Int,
	val product: String,
	val version: String,
	val capabilities: List<String>,
)

private class StudioController(
	private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) : AutoCloseable {
	val state = MutableStateFlow(StudioState())
	private val json = Json { ignoreUnknownKeys = true }
	private val client = ProcessRpcClient(command = {
		listOf(BundledBinaryLocator("amoo", "AMOO_BINARY").locate(), "studio", "serve")
	})

	init {
		client.start()
		scope.launch {
			client.state.collect { processState ->
				when (processState) {
					is ProcessRpcState.Ready -> handshake()
					is ProcessRpcState.Unavailable -> state.value = StudioState(ConnectionState.Unavailable(processState.reason))
					ProcessRpcState.Starting, ProcessRpcState.Stopped -> state.value = StudioState()
				}
			}
		}
	}

	private suspend fun handshake() {
		try {
			val handshake = json.decodeFromJsonElement<Handshake>(client.call("system.handshake"))
			state.value = StudioState(ConnectionState.Ready(handshake.version, handshake.protocolVersion, handshake.capabilities))
		} catch (error: Exception) {
			state.value = StudioState(ConnectionState.Unavailable(error.message ?: "Handshake failed"))
		}
	}

	fun onEvent(event: StudioEvent) {
		state.value = state.value.reduce(event)
		if (event == StudioEvent.RetryConnection) {
			client.close()
			client.start()
		}
	}

	override fun close() = client.close()
}

fun main() = application {
	val controller = remember { StudioController() }
	DisposableEffect(controller) {
		onDispose(controller::close)
	}
	val state by controller.state.collectAsState()
	Window(
		onCloseRequest = {
			exitApplication()
		},
		title = "Amoo Studio",
		state = rememberWindowState(width = 960.dp, height = 680.dp),
	) {
		AmooStudioApp(state, controller::onEvent)
	}
}
