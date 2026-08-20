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

if (providers.gradleProperty("useLocalKmpComponents").orNull.toBoolean()) {
	val localKmpComponents = file("../KMPComponents")
	require(localKmpComponents.resolve("settings.gradle.kts").isFile) {
		"useLocalKmpComponents requires KMPComponents to be checked out next to amoo-studio"
	}
	includeBuild(localKmpComponents)
}

include(":composeApp")
