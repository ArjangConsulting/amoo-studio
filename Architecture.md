# Amoo Studio architecture

## Ownership

Amoo Studio owns Compose UI, presentation state, desktop packaging, and user preferences. The
Swift Amoo executable owns device automation, companion management, MCP tools, model integration,
sessions, reports, and artifacts.

Studio launches `amoo studio serve` and communicates through versioned JSON-RPC over framed stdio.
The GUI never parses human-oriented CLI output.

## State

`StudioState` is the single immutable UI state. `StudioEvent` is the input boundary for user
actions. Composables remain stateless aside from strictly visual state.

## Dependencies

- `process-rpc-kotlin` supervises the bundled child process and transports JSON-RPC messages.
- Compose Multiplatform renders the desktop interface.
- A released Amoo executable is embedded as an OS/architecture-specific resource for distribution.

## Release compatibility

Every packaged Studio release pins an Amoo binary version and verifies its checksum. At runtime,
`system.handshake` validates the Studio protocol version and advertised capabilities before the UI
enables device or agent workflows.
