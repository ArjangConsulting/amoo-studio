# Releases

A Studio release pins three independent versions:

- Amoo Studio application version
- embedded Amoo executable version
- `process-rpc-kotlin` dependency version

The manual `Release` workflow downloads the Amoo binary and checksum from an Amoo GitHub release,
verifies the checksum, and stages the executable under the matching Compose resource directory:

```text
composeApp/app-resources/macos-arm64/amoo
composeApp/app-resources/macos-x64/amoo
composeApp/app-resources/linux-arm64/amoo
composeApp/app-resources/linux-x64/amoo
```

Gradle selects exactly one directory from the build host's operating system and architecture. The
selected executable is therefore staged as `<application resources>/amoo`, which is the location
resolved by `process-rpc-kotlin` at runtime.

The first automated release targets Apple Silicon macOS (DMG/PKG) and x64 Linux (DEB). Amoo releases
must provide `amoo-macos-arm64`, `amoo-linux-x64`, and a `.sha256` asset alongside each executable.
The workflow creates a draft GitHub release so the artifacts and platform signing can be reviewed
before publication. Runtime compatibility is still validated by `system.handshake`; version pinning
is not a substitute for protocol negotiation.

CI packages without embedding Amoo so pull requests can validate `jpackage` independently of an Amoo
release. The release workflow additionally runs `verifyEmbeddedAmoo` and cannot publish an empty GUI
shell.
