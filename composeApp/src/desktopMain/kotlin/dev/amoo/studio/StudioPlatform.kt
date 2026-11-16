package dev.amoo.studio

import io.github.maniramezan.processrpc.ProcessRpcClient
import io.github.maniramezan.processrpc.ProcessRpcState
import io.github.maniramezan.processrpc.RpcNotification
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/** The connection to `amoo studio serve`, narrowed to what [StudioController] uses so tests can script Amoo. */
internal interface StudioRpc : AutoCloseable {
	val state: StateFlow<ProcessRpcState>
	val notifications: SharedFlow<RpcNotification>
	fun start()
	suspend fun call(method: String, params: JsonElement = JsonObject(emptyMap()), timeoutMs: Long? = null): JsonElement
}

internal class ProcessStudioRpc(private val client: ProcessRpcClient) : StudioRpc {
	override val state: StateFlow<ProcessRpcState> get() = client.state
	override val notifications: SharedFlow<RpcNotification> get() = client.notifications
	override fun start() = client.start()
	override suspend fun call(method: String, params: JsonElement, timeoutMs: Long?): JsonElement = client.call(method, params, timeoutMs)
	override fun close() = client.close()
}

/** What the controller needs to know about the machine it runs on. */
internal interface StudioHost {
	val platform: HostPlatform
	fun locateAmoo(): String
	fun locateHomebrew(): String
	fun isAmooMissing(): Boolean
}

/** Native file choosers. A null result means the user cancelled. */
internal interface FilePicker {
	fun chooseFileToOpen(title: String, pattern: String): File?
	fun chooseFileToSave(title: String, initialName: String): File?
	fun chooseProject(): File?
}

internal object DesktopFilePicker : FilePicker {
	override fun chooseFileToOpen(title: String, pattern: String) = choose(FileDialog.LOAD, title, pattern)

	override fun chooseFileToSave(title: String, initialName: String) = choose(FileDialog.SAVE, title, initialName)

	override fun chooseProject(): File? {
		val chooser = JFileChooser().apply {
			dialogTitle = "Choose Xcode project, workspace, or Gradle project"
			fileSelectionMode = JFileChooser.FILES_AND_DIRECTORIES
		}
		return chooser.selectedFile.takeIf { chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION }
	}

	private fun choose(mode: Int, title: String, initialFile: String): File? {
		val dialog = FileDialog(null as Frame?, title, mode)
		dialog.file = initialFile
		dialog.isVisible = true
		val selected = dialog.file?.let { File(dialog.directory, it) }
		dialog.dispose()
		return selected
	}
}
