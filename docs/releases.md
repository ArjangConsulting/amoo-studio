# Releases

A Studio release pins three independent versions:

- Amoo Studio application version
- embedded Amoo executable version
- `process-rpc-kotlin` dependency version

Release automation downloads the Amoo binary and checksum from an Amoo GitHub release, verifies the
checksum, and stages the executable under the matching Compose resource directory:

```text
composeApp/app-resources/macos-arm64/amoo
composeApp/app-resources/macos-x64/amoo
composeApp/app-resources/linux-arm64/amoo
composeApp/app-resources/linux-x64/amoo
```

Gradle selects exactly one directory from the build host's operating system and architecture. The
selected executable is therefore staged as `<application resources>/amoo`, which is the location
resolved by `process-rpc-kotlin` at runtime.

macOS releases produce signed and notarized DMG/PKG artifacts. Linux releases produce DEB/RPM
artifacts, with DEB used for the baseline CI packaging check. Runtime compatibility is still validated by
`system.handshake`; version pinning is not a substitute for protocol negotiation.
