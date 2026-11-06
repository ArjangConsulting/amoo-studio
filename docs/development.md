# Development

## Prerequisites

- JDK 21
- A local checkout of Amoo when exercising the real backend
- A sibling `kmp-code-editor` checkout until its `0.1.0` release is published

Build Amoo and launch Studio:

```bash
cd ../mobile-testing
swift build --product amoo
cd ../amoo-studio
AMOO_BINARY="$PWD/../mobile-testing/.build/debug/amoo" ./gradlew -PuseLocalCodeEditor=true :composeApp:run
```

## Local library checkouts

KMPComponents and credential-keychain-kotlin resolve their pinned releases from Maven Central,
even when sibling checkouts exist. Opt into local development with `-PuseLocalKmpComponents=true`
or `-PuseLocalCredentialKeychain=true`. Their default paths are `../KMPComponents` and
`../credential-keychain-kotlin`; override them with `-PlocalKmpComponentsPath=<path>` and
`-PlocalCredentialKeychainPath=<path>`.

`process-rpc-kotlin` automatically includes a sibling `../process-rpc-kotlin` checkout when present;
override that with `-PuseLocalProcessRpc=true|false` and `-PlocalProcessRpcPath=<path>`.
`kmp-code-editor` resolves the catalog dependency `com.arjangconsulting.codeeditor:code-editor:0.1.0`
by default, even when `../kmp-code-editor` exists. Until that first release is published, opt into
its source checkout explicitly with `-PuseLocalCodeEditor=true`; use
`-PlocalCodeEditorPath=<path>` for another checkout. For example:

```bash
./gradlew -PuseLocalCodeEditor=true :composeApp:compileKotlinDesktop :composeApp:desktopTest
./gradlew -PuseLocalCodeEditor=true :composeApp:packageDmg :composeApp:packagePkg
```

CI and release packaging explicitly enable substitution and check out editor commit `28790a5`,
which includes controlled-value synchronization without history-resetting callback echoes.
Publication-dependent follow-up: after `0.1.0` resolves from Maven Central, remove the editor
checkout steps and both `ORG_GRADLE_PROJECT_useLocalCodeEditor` and
`ORG_GRADLE_PROJECT_localCodeEditorPath` from CI/release workflows. Verify default dependency
resolution, desktop tests/compilation, and native packaging before dropping the source fallback.
Do not enable the editor's release gate or publish it as part of this substitution change.
For IDE imports before publication, explicitly set `useLocalCodeEditor=true` in your local Gradle
properties; the shared run configurations retain the catalog default.

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

KMPComponents is pinned to `0.2.1`, and provider API keys use credential-keychain-kotlin `0.1.1`.
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

Desktop tests use the published KotlinTestCommons `0.2.0` `core` and `compose` artifacts for temporary
directories, bounded flow observation, scripted responders, event recording, serialization checks,
and UI waits, input verification, target-size assertions, and PNG capture. These dependencies are
limited to `desktopTest`.

`SharedComponentsUiTest` covers form validation, credential recovery, composer keyboard behavior,
approvals, accessible split adjustment, and keyboard zoom. It also renders all eight screens in
light, dark, and 200% RTL configurations, writing PNGs under `composeApp/build/reports/ui-integration`.

```bash
./gradlew -PuseLocalCodeEditor=true :composeApp:desktopTest
./gradlew -PuseLocalCodeEditor=true :composeApp:compileKotlinDesktop
```

To verify the released UI and keychain artifacts while explicitly substituting only the unpublished editor:

```bash
./gradlew -PuseLocalCodeEditor=true :composeApp:desktopTest :composeApp:compileKotlinDesktop -PuseLocalKmpComponents=false -PuseLocalCredentialKeychain=false
```

CI intentionally does not require a simulator or emulator. Before a release, exercise the real local
Amoo process boundary and device discovery instead:

```bash
cd ../mobile-testing
swift build --product amoo
cd ../amoo-studio
./scripts/studio-protocol-smoke.py --require-device --exercise-tools
AMOO_BINARY="$PWD/../mobile-testing/.build/debug/amoo" ./gradlew -PuseLocalCodeEditor=true :composeApp:run
```

The smoke command verifies the handshake, health, device discovery, and MCP readiness against the
real backend. Continue the interactive E2E pass in Studio by selecting the discovered device,
building/installing the sample app, saving and reopening an `.amootest`, and running its plan.

## Console and generated source

The console passes `StudioState.runtimeContextRevision` as the editor's `languageServiceRevision`.
Execution start, progress, completion, failure and cancellation, device selection/discovery, app
changes, recording/session changes, catalog changes, and backend reconnects invalidate suggestions
for unchanged command text. Tool calls/results from agent runs also invalidate them; mirror frames
and presentation changes do not. Studio owns this revision and execution through `StudioEvent`;
Amoo owns the runtime context and completion answers. Late service results cannot reopen a stale
popup. Enter accepts a visible completion before a later Enter can submit the command. Invalid
commands and running commands do not submit through the editor.

Controlled history navigation changes the console value without generating input callbacks. An
identical input echo also preserves the history index; an actual edit leaves history navigation.
Short command and result snippets use `CodeSnippet`. Expanded structured results larger than 4,096
characters or at least 20 lines use a 320 dp, wrapped, read-only `CodeEditor`; selection and copying
operate on the full source while rendering stays bounded. Generated source uses a read-only editor
with selected-step line highlighting, source reveal, and gutter clicks to select the mapped step.

`ConsoleEditorIntegrationTest` exercises these boundaries with the actual editor, including late
completion responses, full-source read-only copy and generated source rendering. Copy assertions
use an isolated clipboard for repeatability. Captures are in
`composeApp/build/reports/editor-integration/`. These desktop tests do not establish native mobile
IME or screen-reader behavior; editor-native evidence lives in its `benchmarks/VERIFICATION.md`.
