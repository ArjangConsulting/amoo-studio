# Development

## Prerequisites

- JDK 21
- A local checkout of Amoo when exercising the real backend

Build Amoo and launch Studio:

```bash
cd ../mobile-testing
swift build --product amoo
cd ../amoo-studio
AMOO_BINARY="$PWD/../mobile-testing/.build/debug/amoo" ./gradlew :composeApp:run
```

## Local library checkouts

KMPComponents and credential-keychain-kotlin resolve their pinned releases from Maven Central,
even when sibling checkouts exist. Opt into local development with `-PuseLocalKmpComponents=true`
or `-PuseLocalCredentialKeychain=true`. Their default paths are `../KMPComponents` and
`../credential-keychain-kotlin`; override them with `-PlocalKmpComponentsPath=<path>` and
`-PlocalCredentialKeychainPath=<path>`.

`process-rpc-kotlin` automatically includes a sibling `../process-rpc-kotlin` checkout when present;
override that with `-PuseLocalProcessRpc=true|false` and `-PlocalProcessRpcPath=<path>`.
The unpublished `kmp-code-editor` works the same way with `../kmp-code-editor`,
`-PuseLocalCodeEditor=true|false`, and `-PlocalCodeEditorPath=<path>`.

Without `AMOO_BINARY`, Studio searches packaged application resources and then `PATH`. An unavailable
backend is represented in UI state and must never crash the desktop process.

Instead of building from source, you can install a released `amoo` build via Homebrew and rely on
the `PATH` lookup:

```bash
brew tap arjangconsulting/tap
brew install amoo
```

Homebrew installation is supported on macOS and Linux. Studio checks `PATH` plus `/opt/homebrew/bin`,
`/usr/local/bin`, and `/home/linuxbrew/.linuxbrew/bin`, which covers desktop launches that do not
inherit an interactive shell's `PATH`.

Studio runs on macOS and Linux. macOS can expose both iOS/Xcode and Android/Gradle workflows;
Linux exposes Android/Gradle workflows. The backend handshake remains the authority for individual
operations on either host.

## Verification

KMPComponents is pinned to `0.2.0`, and provider API keys use credential-keychain-kotlin `0.1.1`.
Their versions are managed in `gradle/libs.versions.toml`. API keys stay in the OS keychain;
provider preferences contain only profile metadata. Keychain access runs on a serialized IO dispatcher.

The shared UI uses KMPComponents for navigation, confirmation dialogs, pane splitting, screenshot
zoom, provider configuration and secret visibility, theme selection, selection cards, notices,
disclosures, progress, buttons, and form fields. Conversation rendering uses `ChatLog` and
`ChatComposer`; agent execution details have their own Run activity segment. Provider drafts live in
`StudioState`, and shared controls emit `StudioEvent` actions. Generated source copying uses
`CopyButton` for clipboard feedback. App-specific code retains protocol readiness, approvals,
tool timelines, syntax-aware editors, screenshot loading, and coordinate conversion.
Generated-test panes use shared splitters with layout state in `StudioState`; unreadable screenshots
show a shared error state. Material 3 is supplied by KMPComponents' public dependency contract.
Narrow windows and enlarged text switch generated-test panes to shared Steps / Source / Screenshot
segments. Code editors retain left-to-right source ordering inside right-to-left application layouts.
Device creation keeps its custom form dialog because the shared `ThemeDialog` accepts text content.

The keychain retains the existing `amoo-studio` service/account namespace. Its desktop OS-command
timeout is 60 seconds to allow access prompts. Failures are mapped from the library's reason enum
to remediation without displaying raw diagnostics. Read failures block edits and replacement saves;
read, write, and deletion failures have explicit retry actions in Settings. API keys preserve their
exact value, including surrounding whitespace. Blank keys delete only that profile's entry.

Desktop tests use the published KotlinTestCommons `0.1.0` `core` and `compose` artifacts for temporary
directories, bounded flow observation, scripted responders, event recording, serialization checks,
and UI waits, input verification, target-size assertions, and PNG capture. These dependencies are
limited to `desktopTest`.

`SharedComponentsUiTest` covers form validation, credential recovery, composer keyboard behavior,
approvals, accessible split adjustment, and keyboard zoom. It also renders all eight screens in
light, dark, and 200% RTL configurations, writing PNGs under `composeApp/build/reports/ui-integration`.

```bash
./gradlew :composeApp:desktopTest
./gradlew :composeApp:compileKotlinDesktop
```

To verify the released UI and keychain artifacts without substituting sibling checkouts:

```bash
./gradlew :composeApp:desktopTest :composeApp:compileKotlinDesktop -PuseLocalKmpComponents=false -PuseLocalCredentialKeychain=false
```

CI intentionally does not require a simulator or emulator. Before a release, exercise the real local
Amoo process boundary and device discovery instead:

```bash
cd ../mobile-testing
swift build --product amoo
cd ../amoo-studio
./scripts/studio-protocol-smoke.py --require-device --exercise-tools
AMOO_BINARY="$PWD/../mobile-testing/.build/debug/amoo" ./gradlew :composeApp:run
```

The smoke command verifies the handshake, health, device discovery, and MCP readiness against the
real backend. Continue the interactive E2E pass in Studio by selecting the discovered device,
building/installing the sample app, saving and reopening an `.amootest`, and running its plan.
