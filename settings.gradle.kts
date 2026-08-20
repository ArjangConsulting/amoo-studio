rootProject.name = "amoo-studio"

pluginManagement {
	repositories {
		google()
		gradlePluginPortal()
		mavenCentral()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		google()
		mavenCentral()
		maven("https://jitpack.io")
	}
}

if (file("../KMPComponents/settings.gradle.kts").isFile) {
	includeBuild("../KMPComponents")
}

include(":composeApp")
