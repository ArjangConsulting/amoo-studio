# Bundled Amoo runtime

Compose Desktop packages only the subfolder that matches the build host, such as `macos-arm64/` or
`linux-x64/`; the icons and this README at this level are not packaged. The release workflow stages
two files in the matching subfolder:

- `amoo-runtime.tar.gz`: Amoo's own release tarball, unchanged. It holds the `amoo` executable and
  `CompanionApps` with prebuilt companions (iOS simulator and device test products on macOS, Android
  APKs on both platforms).
- `amoo-runtime.tar.gz.sha256`: its SHA-256 in `sha256sum` format.

Studio extracts the archive once per checksum into `~/Library/Application Support/Amoo Studio/runtime/`
on macOS or `$XDG_DATA_HOME/amoo-studio/runtime/` (default `~/.local/share`) on Linux, then runs the
extracted `amoo`. Amoo finds `CompanionApps` next to its executable and launches the prebuilt
companions without compiling them. The archive is not unpacked into the app bundle because jpackage
drops the symlinks and executable bits inside the iOS test products, and nested iOS-simulator binaries
must stay out of the macOS notarization scan.

`./gradlew :composeApp:verifyEmbeddedAmoo` fails unless the archive matches its checksum and carries an
executable `amoo` and the prebuilt companions. These subfolders are gitignored. Development builds stage
nothing and use `AMOO_BINARY` or an `amoo` on `PATH`.
