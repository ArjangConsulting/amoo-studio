# Studio protocol

Studio starts `amoo studio serve` and communicates through JSON-RPC 2.0 over Content-Length-framed
standard input/output. The framing and process lifecycle are supplied by `process-rpc-kotlin`.

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
| `apps.buildInstallRun` | Build, install, and launch an app on the selected target |
| `apps.reinstallRun` | Reinstall the last build artifact without rebuilding |
| `apps.resetData` | Erase app data after explicit Studio approval |
| `chat.send` | Send provider selection, conversation history, and active test context to Amoo |
| `repl.execute` | Execute a safe structured console command with current Studio context |
| `tests.run` | Execute an authored test on a selected device and return session/report references |
| `reports.list` | List report summaries and artifact paths owned by Amoo |

`chat.send` returns `{ "message": "..." }`. Provider profiles contain endpoint/model configuration
and an environment-variable name, never secret values. Amoo resolves the environment variable and
owns provider networking. Session and report methods will be added to the Swift-owned protocol.
Their Kotlin DTOs remain boundary models, not alternate implementations of Amoo behavior.

`repl.execute` accepts a command plus the active test, selected device ID, and selected provider ID;
it returns `{ "output": "..." }`. Output is structured protocol data rendered by the console, never
captured human CLI output. Destructive commands are rejected at this boundary and must use a Studio
workflow with an explicit approval event.

`tests.run` accepts the complete authored test, selected device ID, and optional provider profile ID.
It returns a user-facing summary plus optional `sessionId` and `reportId`. Cancelling in Studio stops
waiting for that request; a future additive `tests.cancel` capability may provide backend cancellation.

`reports.list` returns `{ "reports": [...] }`; each report contains its stable ID, test name, status,
start time, optional duration, device name, summary, and artifact paths. Studio renders these paths but
does not move or reinterpret backend-owned artifacts.
