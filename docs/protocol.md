# Studio protocol

Studio starts `amoo studio serve` and communicates through JSON-RPC 2.0 over Content-Length-framed
standard input/output. The framing and process lifecycle are supplied by `process-rpc-kotlin`.

Amoo answers requests concurrently when it advertises `rpc.concurrent`. Responses may arrive in any
order and are correlated by ID; work on one device is still applied in arrival order. When Amoo
advertises `rpc.notifications`, it also pushes JSON-RPC notifications (a `method` and no `id`) on the
same stream. Studio subscribes before launching the process and ignores notification methods it does
not recognize. Handler failures keep the request `id` and use standard codes: `-32700` parse error,
`-32600` invalid request, `-32601` unknown method, `-32602` invalid params, `-32603` internal error.

The first request must be `system.handshake`. Studio validates `protocolVersion` and reads advertised
capabilities before enabling screens. Unknown additive capabilities are ignored; required missing
capabilities disable only the affected feature and show remediation.

Current methods:

| Method | Purpose |
| --- | --- |
| `system.handshake` | Product version, protocol version, capabilities |
| `system.health` | Backend readiness |
| `devices.list` | Discover running and available simulators, emulators, and devices |
| `devices.start` | Start a selected simulator or emulator |
| `devices.create` | Create an iOS simulator or Android emulator from guided configuration |
| `apps.buildInstallRun` | Build, install, and launch an app on the selected target |
| `apps.reinstallRun` | Reinstall the last build artifact without rebuilding |
| `apps.resetData` | Erase app data after explicit Studio approval |
| `chat.send` | Send provider selection, conversation history, and active test context to Amoo |
| `repl.execute` | Execute a safe structured console command with current Studio context |
| `tests.run` | Execute an authored test on a selected device and return session/report references |
| `tests.start` | Start a cancellable test run and return its run ID immediately |
| `tests.status` | Poll per-operation progress and terminal session/report references |
| `tests.cancel` | Cancel a running backend task by run ID |
| `tests.export` | Generate native test source from an authored typed test |
| `reports.list` | List report summaries and artifact paths owned by Amoo |
| `mcp.status` | Report MCP readiness, transport, and launch arguments |
| `tools.catalog` | The console and plan vocabulary (`tools.catalog` capability) |
| `repl.complete` | Complete a console line, including on-screen elements (`repl.complete`) |
| `repl.validate` | Diagnostics for a console line (`repl.validate`) |

Notifications pushed by Amoo:

| Method | Capability | Params |
| --- | --- | --- |
| `tests.progress` | `tests.progress` | The same status object `tests.status` returns, sent whenever an asynchronous run changes |

`chat.send` returns `{ "message": "..." }`. Provider profiles contain endpoint/model configuration,
an API-key environment-variable name, and an optional `apiKey`. Studio sends `apiKey` only when Amoo
advertises the `providers.apiKey` capability; Amoo uses it in preference to the environment variable,
never persists or logs it, and owns provider networking. Against older Amoo builds Studio omits the
key and asks the user to configure the environment variable instead. `providers.check` accepts the
same profile shape. `repl.execute` and `tests.*` carry only the provider ID and never need the key. Session and report methods will be added to the Swift-owned protocol.
Their Kotlin DTOs remain boundary models, not alternate implementations of Amoo behavior.

`devices.create` accepts `platform`, `name`, `runtime`, and `deviceType`, returning the standard
operation result. Amoo validates tool availability and installed images; Studio does not invoke Xcode,
Android SDK tools, or ShipItSwifty directly.

`repl.execute` accepts a command plus the active test, selected device ID, and selected provider ID;
it returns `{ "output": "..." }`. Output is structured protocol data rendered by the console, never
captured human CLI output. Destructive commands are rejected at this boundary and must use a Studio
workflow with an explicit approval event.

`tests.run` accepts the complete authored test, selected device ID, and optional provider profile ID.
It returns a user-facing summary plus optional `sessionId` and `reportId`. Cancelling in Studio stops
waiting for legacy backends. When `tests.start`, `tests.status`, and `tests.cancel` are advertised,
Studio uses the asynchronous workflow, renders per-operation progress, and cancels the backend task.
With `tests.progress`, Studio follows pushed statuses instead of polling. A run can report progress
before `tests.start` returns its ID, so Studio keeps the latest status per run ID, and it calls
`tests.status` every 5 seconds without a notification in case one was dropped.

`tests.export` is enabled only when the handshake advertises that capability. It accepts
`{ "test": <authored test> }` and returns `{ "fileName": "...", "source": "..." }`. Amoo owns
native code generation; Studio only asks the user where to save the returned source.

`reports.list` returns `{ "reports": [...] }`; each report contains its stable ID, test name, status,
start time, optional duration, device name, summary, and artifact paths. Studio renders these paths but
does not move or reinterpret backend-owned artifacts.

`mcp.status` returns availability, transport, and arguments for the backend's MCP server. Studio
still resolves the packaged executable locally when generating client configuration and never proxies
MCP traffic.

## Console catalog

When Amoo advertises `tools.catalog`, Studio loads it after the handshake and uses it for the
console grammar, "Add to test plan", and the plan editor. Older Amoo builds keep
`ToolCatalog.Legacy`, the fixed command set they understand.

- **Tools.** Each tool has typed arguments with known `values`. It also carries `readOnly`,
  `destructive`, `replSupported`, and `planTool`, the name a compiled plan records it under
  (`assert_absent` records as `assert_not_visible`).
- **Shared grammar.** `ReplGrammar` is a Kotlin port of Amoo's `StudioReplGrammar`, and their test
  suites mirror each other. Studio uses it for immediate validation, and to complete and validate
  on its own against Amoo builds without `repl.complete` or `repl.validate`.
- **Positions.** Both grammars use UTF-16 positions, which are Kotlin string indices.
- **Requests.** `repl.execute` carries the selected device's `platform` and `captureScreenshot: true`.
- **Results.** Results with `repl.structuredResults` add:
  - `isError`
  - `tool`
  - `structuredContent`, as JSON text
  - `artifacts`
  - `screenshot`, with path and geometry
  - `gesturePoint`, in points

  Console entries show the screenshot as a thumbnail that pins into the device pane, with the
  gesture drawn over it.

## Live device view

The device pane mirrors the selected running device while Console or AI testing is on screen. Studio
spawns `amoo device stream --platform <p> --device <id> --fps 6 --out <temp dir>` as its own process,
separate from `amoo studio serve`, so frames never block the protocol stream. The stream captures on
the host without a device lease, so it can watch a device the console or an agent is driving.

- **Events.** Studio reads only these NDJSON fields:
  - `started.directory`
  - `frame.frame`, `frame.path`, `frame.width`, `frame.height`
  - `error.message`
  - `stopped`

  Unknown events and fields are ignored.
- **Frames.** Frames are decoded off the UI thread and conflated, so a slow decode skips frames
  instead of queueing them.
- **Failures.** A stream that exits with usage error 64 before `started` means Amoo predates
  `device stream`, and the pane says so instead of retrying. Any other exit restarts with backoff
  from 1 to 30 seconds, reset after frames arrive.
- **Lifecycle.** The process is destroyed when the section, the device, or the pause state changes,
  and when Studio closes.

