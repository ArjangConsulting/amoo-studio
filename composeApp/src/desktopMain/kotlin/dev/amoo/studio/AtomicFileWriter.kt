package dev.amoo.studio

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Replaces [file] with [text] so a crash, kill or full disk mid-write leaves either the old contents
 * or the new ones, never a truncated file. The text goes to a temporary file in the same directory
 * (so the final move cannot cross file systems), is flushed to disk, and then atomically renamed
 * over [file]. A symlinked [file] is written through to its target, and an existing file keeps its
 * permissions.
 */
internal fun writeAtomically(file: File, text: String) {
	val existing = file.toPath().takeIf(Files::exists)
	val target = existing?.toRealPath() ?: file.absoluteFile.toPath()
	val directory = target.parent
	Files.createDirectories(directory)
	val temporary = Files.createTempFile(directory, ".${target.fileName}.", ".tmp")
	try {
		FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
			val buffer = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
			while (buffer.hasRemaining()) channel.write(buffer)
			channel.force(true)
		}
		runCatching {
			val permissions = existing?.let(Files::getPosixFilePermissions) ?: DEFAULT_PERMISSIONS
			Files.setPosixFilePermissions(temporary, permissions)
		}
		Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
	} finally {
		Files.deleteIfExists(temporary)
	}
}

private val DEFAULT_PERMISSIONS = setOf(
	PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
	PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ,
)
