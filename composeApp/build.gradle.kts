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
val packagedAmoo = layout.projectDirectory.file("app-resources/$packagingOs-$packagingArch/amoo")

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
				implementation(libs.coroutines.core)
				implementation(libs.kotlinx.serialization.json)
			}
		}
		named("desktopMain") {
			dependencies {
				implementation("com.github.maniramezan:process-rpc-kotlin:0.1.0")
				implementation(compose.desktop.currentOs)
				implementation(libs.coroutines.swing)
			}
		}
		named("desktopTest") {
			dependencies { implementation(kotlin("test")) }
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
			appResourcesRootDir.set(project.layout.projectDirectory.dir("app-resources/$packagingOs-$packagingArch"))
			macOS {
				bundleID = "dev.amoo.studio"
				appCategory = "public.app-category.developer-tools"
				minimumSystemVersion = "15.0"
				iconFile.set(project.file("app-resources/icon.icns"))
			}
		}
	}
}

tasks.register("verifyEmbeddedAmoo") {
	group = "distribution"
	description = "Fails unless the current platform's Amoo executable is staged for release packaging."
	doLast {
		val binary = packagedAmoo.asFile
		check(binary.isFile && binary.length() > 0) {
			"Missing embedded Amoo executable: ${binary.absolutePath}"
		}
		check(!System.getProperty("os.name").startsWith("Windows", ignoreCase = true) && binary.canExecute()) {
			"Embedded Amoo executable is not executable: ${binary.absolutePath}"
		}
	}
}
