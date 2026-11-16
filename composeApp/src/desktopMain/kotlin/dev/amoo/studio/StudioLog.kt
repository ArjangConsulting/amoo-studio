package dev.amoo.studio

import java.io.File
import java.time.Instant
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Studio's diagnostic log: a size-rotated file in the data directory that users can attach to bug
 * reports. Until [install] runs (and in tests) records are dropped.
 *
 * Log operations and identifiers (method names, run and device IDs), never values the user typed and
 * never credentials: [Secret] redacts itself, but nothing should rely on that.
 */
internal object StudioLog {
	private val logger = Logger.getLogger("dev.amoo.studio").apply {
		useParentHandlers = false
		level = Level.ALL
	}

	@Volatile var directory: File? = null
		private set

	/** Starts writing to `studio.log` in [logDirectory], rotating at about 1 MB across 5 files, and records uncaught exceptions. */
	@Synchronized
	fun install(logDirectory: File, maxBytes: Int = 1_000_000, fileCount: Int = 5) {
		if (directory != null) return
		runCatching {
			logDirectory.mkdirs()
			val pattern = logDirectory.path.replace("%", "%%") + "/studio.%g.log"
			val handler = FileHandler(pattern, maxBytes, fileCount, true).apply {
				formatter = LineFormatter
				encoding = "UTF-8"
				level = Level.INFO
			}
			logger.addHandler(handler)
			directory = logDirectory
		}.onFailure { System.err.println("Could not open the Studio log in ${logDirectory.path}: ${it.message}") }
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
			forFeature("Process").error("uncaught", failure, "thread=${thread.name}")
			previous?.uncaughtException(thread, failure)
		}
	}

	fun forFeature(feature: String) = FeatureLog(feature)

	class FeatureLog internal constructor(private val feature: String) {
		fun info(action: String, context: String = "") = write(Level.INFO, action, context, null)
		fun debug(action: String, context: String = "") = write(Level.FINE, action, context, null)
		fun warn(action: String, context: String = "") = write(Level.WARNING, action, context, null)
		fun error(action: String, failure: Throwable, context: String = "") = write(Level.SEVERE, action, context, failure)

		private fun write(level: Level, action: String, context: String, failure: Throwable?) {
			if (!logger.isLoggable(level)) return
			val record = LogRecord(level, action)
			record.parameters = arrayOf(feature, context)
			record.thrown = failure
			logger.log(record)
		}
	}

	private object LineFormatter : Formatter() {
		override fun format(record: LogRecord): String {
			val feature = record.parameters[0]
			val context = record.parameters[1] as String
			val level = when (record.level) {
				Level.SEVERE -> "ERROR"
				Level.WARNING -> "WARN"
				Level.INFO -> "INFO"
				else -> "DEBUG"
			}
			val failure = record.thrown?.let { " error=${it::class.simpleName}: ${it.message.orEmpty().replace('\n', ' ')}" }.orEmpty()
			val trace = record.thrown?.let { "\n" + it.stackTraceToString().trimEnd() }.orEmpty()
			return "${Instant.ofEpochMilli(record.millis)} $level [$feature] ${record.message} ${context.trim()}$failure$trace\n"
		}
	}
}
