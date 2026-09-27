rootProject.name = "amoo-studio"

pluginManagement {
	repositories {
		google()
		gradlePluginPortal()
		mavenCentral()
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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

// credential-keychain-kotlin is resolved from Maven Central by default. Point the build at a local
// checkout with -PuseLocalCredentialKeychain=true (optionally -PlocalCredentialKeychainPath=...);
// when the property is unset, a sibling checkout is used automatically if one exists.
val localCredentialKeychain = file(providers.gradleProperty("localCredentialKeychainPath").orNull ?: "../credential-keychain-kotlin")
val useLocalCredentialKeychain = providers.gradleProperty("useLocalCredentialKeychain").orNull?.toBoolean()
	?: localCredentialKeychain.resolve("settings.gradle.kts").isFile
if (useLocalCredentialKeychain) {
	require(localCredentialKeychain.resolve("settings.gradle.kts").isFile) {
		"useLocalCredentialKeychain requires credential-keychain-kotlin at ${localCredentialKeychain.path}"
	}
	includeBuild(localCredentialKeychain) {
		// The library's Gradle project is :core but it publishes as credential-keychain-kotlin.
		dependencySubstitution {
			substitute(module("com.maniramezan:credential-keychain-kotlin")).using(project(":core"))
		}
	}
}

include(":composeApp")
