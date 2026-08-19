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
```

The packaged application must be signed and notarized. Runtime compatibility is still validated by
`system.handshake`; version pinning is not a substitute for protocol negotiation.
