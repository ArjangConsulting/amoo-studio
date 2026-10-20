package dev.amoo.studio

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * Decodes saved screenshots off the UI thread, downscaled to the size they are shown at, and keeps
 * recently shown ones in an LRU bounded by decoded bytes. A full-resolution phone screenshot is about
 * 12 MB decoded, so the budget, not an entry count, is what keeps a long console history affordable.
 */
internal class DesktopScreenshotLoader(private val budgetBytes: Long = 192L * 1024 * 1024) : ScreenshotLoader {
	private data class Key(val path: String, val maxDimension: Int, val modified: Long)

	private val cache = LinkedHashMap<Key, ImageBitmap>(16, 0.75f, true)
	private var cachedBytes = 0L
	private val lock = Mutex()

	override suspend fun load(path: String, maxDimension: Int): ImageBitmap? {
		val file = File(path)
		val key = withContext(Dispatchers.IO) { file.takeIf(File::isFile)?.let { Key(path, maxDimension, it.lastModified()) } } ?: return null
		lock.withLock { cache[key] }?.let { return it }
		val image = withContext(Dispatchers.IO) { runCatching { decodeDownscaled(file.readBytes(), maxDimension) }.getOrNull() } ?: return null
		lock.withLock {
			if (cache.put(key, image) == null) cachedBytes += image.byteCount
			val iterator = cache.entries.iterator()
			while (cachedBytes > budgetBytes && iterator.hasNext()) {
				val eldest = iterator.next()
				if (eldest.key == key) continue
				cachedBytes -= eldest.value.byteCount
				iterator.remove()
			}
		}
		return image
	}

	private val ImageBitmap.byteCount: Long get() = width.toLong() * height * 4
}

/** Decodes [bytes], scaling down with linear filtering so the longest side is at most [maxDimension]. */
internal fun decodeDownscaled(bytes: ByteArray, maxDimension: Int): ImageBitmap {
	val image = Image.makeFromEncoded(bytes)
	val longest = max(image.width, image.height)
	if (maxDimension <= 0 || longest <= maxDimension) return image.toComposeImageBitmap()
	val ratio = maxDimension.toFloat() / longest
	val width = (image.width * ratio).roundToInt().coerceAtLeast(1)
	val height = (image.height * ratio).roundToInt().coerceAtLeast(1)
	val surface = Surface.makeRasterN32Premul(width, height)
	surface.canvas.drawImageRect(
		image,
		Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
		Rect.makeWH(width.toFloat(), height.toFloat()),
		FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
		null,
		true,
	)
	return surface.makeImageSnapshot().toComposeImageBitmap()
}
