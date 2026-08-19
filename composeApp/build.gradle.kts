import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
	alias(libs.plugins.kotlin.multiplatform)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.compose.multiplatform)
	alias(libs.plugins.compose.compiler)
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

compose.desktop {
	application {
		mainClass = "dev.amoo.studio.MainKt"
		nativeDistributions {
			targetFormats(TargetFormat.Dmg, TargetFormat.Pkg, TargetFormat.Deb, TargetFormat.Rpm)
			packageName = "Amoo Studio"
			packageVersion = "0.1.0"
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
