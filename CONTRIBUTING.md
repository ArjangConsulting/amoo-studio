# Contributing

Thank you for improving Amoo Studio.

1. Read [AGENTS.md](AGENTS.md) and [Architecture.md](Architecture.md).
2. Keep product logic in Amoo; Studio should remain a structured protocol client.
3. Add focused tests with behavioral changes.
4. Run:

```bash
./gradlew :composeApp:desktopTest :composeApp:compileKotlinDesktop
```

5. Describe protocol, UI, packaging, and accessibility implications in the pull request.

Do not commit API credentials, signing material, downloaded Amoo binaries, or local environment
configuration.
