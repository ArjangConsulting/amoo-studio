package dev.amoo.studio

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import java.awt.GraphicsEnvironment
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.amoo.composeapp.generated.resources.Res
import dev.amoo.composeapp.generated.resources.icon_512
import io.github.maniramezan.processrpc.BundledBinaryLocator
import io.github.maniramezan.processrpc.ProcessRpcClient
import java.awt.Dimension
import java.awt.Image
import java.awt.Taskbar
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.painterResource

internal fun detectHostPlatform(): HostPlatform {
	val osName = System.getProperty("os.name").orEmpty().lowercase()
	return when {
		"mac" in osName || "darwin" in osName -> HostPlatform.MacOS
		"linux" in osName -> HostPlatform.Linux
		else -> HostPlatform.Unsupported
	}
}

// Extracted once per process: the first caller is the RPC launch on an IO thread, so the UI never waits.
private val embeddedAmoo: Result<File?> by lazy {
	runCatching {
		AmooRuntimeInstaller(
			resourcesDirectory = System.getProperty("compose.application.resources.dir")?.let(::File),
			runtimeRoot = AmooRuntimeInstaller.defaultRuntimeRoot(detectHostPlatform()),
		).install()
	}
}

/** `AMOO_BINARY` wins for development; packaged builds then use their embedded runtime, which carries prebuilt companions. */
private fun locateAmoo(): String {
	if (System.getenv("AMOO_BINARY").isNullOrBlank()) {
		embeddedAmoo.getOrThrow()?.let { return it.absolutePath }
	}
	return locateExecutable(name = "amoo", environmentVariable = "AMOO_BINARY")
}

private fun locateHomebrew(): String = locateExecutable(name = "brew")

// An embedded runtime that fails to extract is a broken install, not a missing Amoo: offering Homebrew would hide it.
private fun isAmooMissing(): Boolean = System.getenv("AMOO_BINARY").isNullOrBlank() && embeddedAmoo.isSuccess && runCatching { locateAmoo() }.isFailure

private fun locateExecutable(name: String, environmentVariable: String? = null): String {
	if (environmentVariable != null) {
		if (!System.getenv(environmentVariable).isNullOrBlank()) return BundledBinaryLocator(name, environmentVariable).locate()
		runCatching { BundledBinaryLocator(name, environmentVariable).locate() }.getOrNull()?.let { return it }
	}
	System.getenv("PATH").orEmpty().split(File.pathSeparatorChar)
		.asSequence()
		.filter(String::isNotBlank)
		.map { File(it, name) }
		.firstOrNull { it.isFile && it.canExecute() }
		?.let { return it.absolutePath }
	listOf("/opt/homebrew/bin", "/usr/local/bin", "/home/linuxbrew/.linuxbrew/bin")
		.asSequence()
		.map { File(it, name) }
		.firstOrNull { it.isFile && it.canExecute() }
		?.let { return it.absolutePath }
	throw IllegalStateException("Could not locate $name. Set ${environmentVariable ?: "PATH"} or install Homebrew.")
}

private fun installDockIcon() {
	val icon: Image = runCatching {
		Thread.currentThread().contextClassLoader
			.getResourceAsStream("icons/icon.png")
			?.use(ImageIO::read)
	}.getOrNull() ?: return
	runCatching {
		if (Taskbar.isTaskbarSupported()) {
			val taskbar = Taskbar.getTaskbar()
			if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
				taskbar.iconImage = icon
			}
		}
	}
}

private object DesktopStudioHost : StudioHost {
	override val platform = detectHostPlatform()
	override fun locateAmoo() = dev.amoo.studio.locateAmoo()
	override fun locateHomebrew() = dev.amoo.studio.locateHomebrew()
	override fun isAmooMissing() = dev.amoo.studio.isAmooMissing()
}

private fun createController() = StudioController(
	rpc = ProcessStudioRpc(ProcessRpcClient(command = { listOf(locateAmoo(), "studio", "serve") })),
	host = DesktopStudioHost,
	preferences = Preferences.userNodeForPackage(StudioController::class.java),
	keychain = createProviderKeychain(),
	chatHistoryFile = File(studioDataDirectory(detectHostPlatform()), ChatHistoryStore.FILE_NAME),
	filePicker = DesktopFilePicker,
)

@OptIn(FlowPreview::class)
fun main() {
	StudioLog.install(File(studioDataDirectory(detectHostPlatform()), "logs"))
	StudioLog.forFeature("Studio").info("launch", "os=${System.getProperty("os.name")} arch=${System.getProperty("os.arch")}")
	application {
		val controller = remember { createController() }
		DisposableEffect(controller) {
			onDispose(controller::close)
		}
		val state by controller.state.collectAsState()
		val timestampFormatter = remember { DesktopTimestampFormatter() }
		val initialSize = WindowSize(controller.initialWindowSize?.width ?: DEFAULT_WINDOW_WIDTH, controller.initialWindowSize?.height ?: DEFAULT_WINDOW_HEIGHT)
		val reachableOrigin = controller.initialWindowOrigin?.takeIf { origin ->
			isReachable(origin, initialSize, GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds })
		}
		val windowState = rememberWindowState(
			position = reachableOrigin?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.PlatformDefault,
			width = initialSize.width.dp,
			height = initialSize.height.dp,
		)
		LaunchedEffect(state.exitRequested) {
			if (state.exitRequested) exitApplication()
		}
		LaunchedEffect(windowState) {
			snapshotFlow { windowState.position to windowState.size }
				.drop(1)
				.debounce(500)
				.collect { (position, size) ->
					val origin = position.takeIf { it.isSpecified }?.let { WindowOrigin(it.x.value.roundToInt(), it.y.value.roundToInt()) }
					controller.persistWindowBounds(origin, WindowSize(size.width.value.roundToInt(), size.height.value.roundToInt()))
				}
		}
		Window(
			onCloseRequest = { controller.onEvent(StudioEvent.RequestQuit) },
			title = "Amoo Studio",
			icon = painterResource(Res.drawable.icon_512),
			state = windowState,
		) {
			MenuBar {
				val macOS = detectHostPlatform() == HostPlatform.MacOS
				studioMenus().forEach { group ->
					Menu(group.title) {
						group.commands.forEach { command ->
							Item(command.title, shortcut = command.shortcut?.toKeyShortcut(macOS), onClick = { controller.onEvent(command.event) })
						}
					}
				}
			}
			LaunchedEffect(Unit) {
				installDockIcon()
				window.minimumSize = Dimension(StudioPreferences.MIN_WINDOW_WIDTH, StudioPreferences.MIN_WINDOW_HEIGHT)
			}
			CompositionLocalProvider(
				LocalScreenshotLoader provides controller.screenshotLoader,
				LocalReplAssist provides controller.replAssist,
				LocalTimestampFormatter provides timestampFormatter,
			) {
				AmooStudioApp(state, controller::onEvent)
			}
		}
	}
}

private const val DEFAULT_WINDOW_WIDTH = 960
private const val DEFAULT_WINDOW_HEIGHT = 680
