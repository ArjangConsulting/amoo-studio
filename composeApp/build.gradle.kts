import java.security.MessageDigest
import javax.inject.Inject
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

val packagingOs = when {
	System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
	System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "linux"
	else -> "unsupported"
}
val packagingArch = when (System.getProperty("os.arch").lowercase()) {
	"aarch64", "arm64" -> "arm64"
	else -> "x64"
}
val packagedAmooRuntime = layout.projectDirectory.file("app-resources/$packagingOs-$packagingArch/amoo-runtime.tar.gz")

plugins {
	alias(libs.plugins.kotlin.multiplatform)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.compose.multiplatform)
	alias(libs.plugins.compose.compiler)
	alias(libs.plugins.kover)
}

kotlin {
	jvmToolchain(21)
	jvm("desktop")

	sourceSets {
		named("commonMain") {
			dependencies {
				implementation(libs.compose.runtime)
				implementation(libs.compose.foundation)
				implementation(compose.material3)
				implementation(compose.components.resources)
				implementation(libs.compose.ui)
				implementation(libs.kmp.components)
				implementation(libs.kmp.code.editor)
				implementation(libs.coroutines.core)
				implementation(libs.kotlinx.serialization.json)
			}
		}
		named("desktopMain") {
			dependencies {
				implementation("com.github.maniramezan:process-rpc-kotlin:0.1.0")
				implementation("com.maniramezan:credential-keychain-kotlin:0.1.1")
				implementation(compose.desktop.currentOs)
				implementation(libs.coroutines.swing)
			}
		}
		named("desktopTest") {
			dependencies {
				implementation(kotlin("test"))
				implementation(libs.compose.ui.test)
			}
		}
	}
}

kover {
	reports {
		verify {
			rule("Studio line coverage") {
				minBound(20)
			}
		}
	}
}

compose.desktop {
	application {
		mainClass = "dev.amoo.studio.MainKt"
		nativeDistributions {
			targetFormats(TargetFormat.Dmg, TargetFormat.Pkg, TargetFormat.Deb, TargetFormat.Rpm)
			packageName = "Amoo Studio"
			packageVersion = project.version.toString().substringBefore('-')
			description = "Desktop client for Amoo mobile testing"
			vendor = "Amoo"
			appResourcesRootDir.set(project.layout.projectDirectory.dir("app-resources"))
			macOS {
				bundleID = "dev.amoo.studio"
				appCategory = "public.app-category.developer-tools"
				minimumSystemVersion = "15.0"
				iconFile.set(project.file("app-resources/icon.icns"))
			}
		}
	}
}

/**
 * Release guard: the staged Amoo runtime archive must match its checksum and carry an executable
 * `amoo` plus prebuilt companions, so packaged Studio never compiles companions on a user's machine.
 */
abstract class VerifyEmbeddedAmoo @Inject constructor(private val archives: ArchiveOperations) : DefaultTask() {
	// Internal rather than @InputFile so a missing archive fails with the message below, not a validation error.
	@get:Internal abstract val archive: RegularFileProperty
	@get:Input abstract val requiresIosCompanion: Property<Boolean>

	@TaskAction
	fun verify() {
		val archiveFile = archive.get().asFile
		check(archiveFile.isFile && archiveFile.length() > 0) { "Missing embedded Amoo runtime: ${archiveFile.absolutePath}" }
		val checksumFile = File(archiveFile.path + ".sha256")
		check(checksumFile.isFile) { "Missing embedded Amoo runtime checksum: ${checksumFile.absolutePath}" }
		val expected = checksumFile.readText().trim().substringBefore(' ').lowercase()
		val digest = MessageDigest.getInstance("SHA-256")
		archiveFile.inputStream().buffered().use { input ->
			val buffer = ByteArray(1 shl 16)
			while (true) {
				val count = input.read(buffer)
				if (count < 0) break
				digest.update(buffer, 0, count)
			}
		}
		val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
		check(actual == expected) { "Embedded Amoo runtime checksum mismatch: $actual, expected $expected" }

		val entries = mutableSetOf<String>()
		var executable = false
		archives.tarTree(archives.gzip(archiveFile)).visit {
			entries += relativePath.pathString
			if (relativePath.pathString == "amoo" && !isDirectory) executable = permissions.user.execute
		}
		check(executable) { "The embedded Amoo runtime has no executable amoo at its root" }
		val required = buildList {
			add("CompanionApps/Android/prebuilt/app-debug.apk")
			add("CompanionApps/Android/prebuilt/app-debug-androidTest.apk")
			if (requiresIosCompanion.get()) add("CompanionApps/iOS/project.yml")
		}
		val missing = required.filterNot(entries::contains)
		check(missing.isEmpty()) { "The embedded Amoo runtime lacks prebuilt companions: ${missing.joinToString()}" }
		if (requiresIosCompanion.get()) {
			listOf("iphonesimulator", "iphoneos").forEach { platform ->
				check(entries.any { it.startsWith("CompanionApps/iOS/prebuilt/$platform/Products/") && it.endsWith(".xctestrun") }) {
					"The embedded Amoo runtime lacks the prebuilt $platform iOS companion"
				}
			}
		}
	}
}

tasks.register<VerifyEmbeddedAmoo>("verifyEmbeddedAmoo") {
	group = "distribution"
	description = "Fails unless the current platform's Amoo runtime archive, with prebuilt companions, is staged for release packaging."
	archive.set(packagedAmooRuntime)
	requiresIosCompanion.set(packagingOs == "macos")
}
