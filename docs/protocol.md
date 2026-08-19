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

Device, chat, session, report, and approval methods will be added to the Swift-owned protocol. Their
Kotlin DTOs remain boundary models, not alternate implementations of Amoo behavior.
