# Releases

A Studio release pins three independent versions:

- Amoo Studio application version
- embedded Amoo executable version
- `process-rpc-kotlin` dependency version

The manual `Release` workflow downloads Amoo's release tarball for the platform
(`amoo-<version>-macos-universal.tar.gz` or `amoo-<version>-linux-static.tar.gz`) and its `.sha256`
from the Amoo GitHub release. It verifies the checksum, then stages the tarball unchanged as the
runtime archive:

```text
composeApp/app-resources/macos-arm64/amoo-runtime.tar.gz
composeApp/app-resources/macos-arm64/amoo-runtime.tar.gz.sha256
composeApp/app-resources/linux-x64/amoo-runtime.tar.gz
composeApp/app-resources/linux-x64/amoo-runtime.tar.gz.sha256
```

Compose packages only the subfolder that matches the build host, so the archive ends up in the
application resources. On first launch Studio extracts it into a per-user runtime directory, keyed
by checksum, and runs the extracted `amoo`. That copy finds the prebuilt companions next to it, so
users never compile companion apps. `composeApp/app-resources/README.md` explains why the archive
is not unpacked into the app bundle.

The release targets Apple Silicon macOS (DMG/PKG) and x64 Linux (DEB). The Amoo version must be a
release that publishes prebuilt companions and tarball checksums. Amoo 0.2.1 has neither, so it
cannot be embedded. The workflow creates a draft GitHub release so the artifacts and platform
signing can be reviewed before publication. Runtime compatibility is still validated by
`system.handshake`; version pinning is not a substitute for protocol negotiation.

CI packages without embedding Amoo so pull requests can validate `jpackage` independently of an Amoo
release. The release workflow additionally runs `verifyEmbeddedAmoo`, which checks the archive's checksum
and that it contains an executable `amoo` and the prebuilt companions, so it cannot publish an empty
GUI shell or one that would compile companions on a user's machine.

Provider credentials use credential-keychain-kotlin's native secure storage. macOS desktop builds
use the login keychain through `/usr/bin/security`; Linux desktop sessions need `secret-tool` and a
running Secret Service. There is no plaintext fallback. Updates must retain the `amoo-studio`
service and account namespace so previously saved API keys remain available. OS keychain failures
are surfaced in Settings with explicit retry actions.
