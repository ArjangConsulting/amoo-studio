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

## Verification

```bash
./gradlew :composeApp:desktopTest
./gradlew :composeApp:compileKotlinDesktop
```
