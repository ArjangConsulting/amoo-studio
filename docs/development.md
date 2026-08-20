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

Without `AMOO_BINARY`, Studio searches packaged application resources and then `PATH`. An unavailable
backend is represented in UI state and must never crash the desktop process.

Studio runs on macOS and Linux. macOS can expose both iOS/Xcode and Android/Gradle workflows;
Linux exposes Android/Gradle workflows. The backend handshake remains the authority for individual
operations on either host.

## Verification

```bash
./gradlew :composeApp:desktopTest
./gradlew :composeApp:compileKotlinDesktop
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
