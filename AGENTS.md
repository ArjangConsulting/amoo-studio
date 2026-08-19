# Amoo Studio contributor guide

This is the canonical operating guide for humans and coding agents working in this repository.
`CLAUDE.md` points here; do not duplicate these rules in tool-specific files.

## Mission

Amoo Studio is a Compose Multiplatform desktop client for the Swift Amoo mobile-testing engine.
Keep the GUI presentation-only: device automation, MCP tools, model providers, sessions, reports,
and companion lifecycle belong in Amoo and are reached through `amoo studio serve`.

## Architecture

- `composeApp/src/commonMain`: immutable UI state, events, stateless composables, shared theme.
- `composeApp/src/desktopMain`: process wiring, desktop lifecycle, packaging integration.
- `composeApp/src/desktopTest`: state and desktop-boundary tests.
- `process-rpc-kotlin`: external versioned dependency; do not copy its transport implementation.
- `Architecture.md`: product and repository boundaries.
- `docs/protocol.md`: Studio protocol consumption rules.
- `docs/releases.md`: binary embedding, signing, and compatibility.

## State and UI rules

- `StudioState` is the single source of truth.
- User actions enter through `StudioEvent`; prefer one event sink over many callbacks.
- Keep common composables stateless. Local state is only for transient visual affordances.
- Keep filesystem, process, environment, and provider access in `desktopMain`.
- Use Material theme roles instead of raw colors and reuse spacing decisions.
- Provide stable keys for dynamic lists and keep expensive work outside composition.
- Every icon-only action needs an accessible description and tooltip.

## Protocol rules

- Never parse human-readable `amoo` CLI output.
- Standard output from `amoo studio serve` is reserved for framed JSON-RPC messages.
- Negotiate protocol compatibility through `system.handshake` before enabling workflows.
- Additive protocol changes must be capability-gated; incompatible versions fail with remediation.
- Destructive operations require an explicit approval event rendered by Studio.

## Commands

```bash
./gradlew :composeApp:compileKotlinDesktop
./gradlew :composeApp:desktopTest
./gradlew :composeApp:run
./gradlew :composeApp:packageDistributionForCurrentOS
```

For local backend development:

```bash
AMOO_BINARY="$PWD/../mobile-testing/.build/debug/amoo" ./gradlew :composeApp:run
```

## Change expectations

- Add state-transition tests for workflow behavior.
- Compile the desktop target after Compose signature or wiring changes.
- Keep warnings visible; do not add suppressions merely to pass CI.
- Preserve unrelated working-tree changes.
- Update protocol and release docs when their contracts change.
