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

// KMPComponents resolves from Maven Central by default. A sibling
// checkout is included automatically when present; -PuseLocalKmpComponents=true|false overrides that.
val localKmpComponents = file("../KMPComponents")
val useLocalKmpComponents = providers.gradleProperty("useLocalKmpComponents").orNull?.toBoolean()
	?: localKmpComponents.resolve("settings.gradle.kts").isFile
if (useLocalKmpComponents) {
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

// process-rpc-kotlin is resolved from JitPack by default. Point the build at a local checkout with
// -PuseLocalProcessRpc=true (optionally -PlocalProcessRpcPath=...); when the property is unset, a
// sibling checkout is used automatically if one exists.
val localProcessRpc = file(providers.gradleProperty("localProcessRpcPath").orNull ?: "../process-rpc-kotlin")
val useLocalProcessRpc = providers.gradleProperty("useLocalProcessRpc").orNull?.toBoolean()
	?: localProcessRpc.resolve("settings.gradle.kts").isFile
if (useLocalProcessRpc) {
	require(localProcessRpc.resolve("settings.gradle.kts").isFile) {
		"useLocalProcessRpc requires process-rpc-kotlin at ${localProcessRpc.path}"
	}
	includeBuild(localProcessRpc) {
		// The library is a single root project; JitPack publishes it under the repository name.
		dependencySubstitution {
			substitute(module("com.github.maniramezan:process-rpc-kotlin")).using(project(":"))
		}
	}
}

// kmp-code-editor (com.arjangconsulting.codeeditor:code-editor) resolves from Maven Central by default. A
// sibling checkout is included automatically when present; -PuseLocalCodeEditor=true|false overrides
// that, and -PlocalCodeEditorPath=<path> points at another checkout.
val localCodeEditor = file(providers.gradleProperty("localCodeEditorPath").orNull ?: "../kmp-code-editor")
val useLocalCodeEditor = providers.gradleProperty("useLocalCodeEditor").orNull?.toBoolean()
	?: localCodeEditor.resolve("settings.gradle.kts").isFile
if (useLocalCodeEditor) {
	require(localCodeEditor.resolve("settings.gradle.kts").isFile) {
		"useLocalCodeEditor requires kmp-code-editor at ${localCodeEditor.path}"
	}
	includeBuild(localCodeEditor)
}

include(":composeApp")
