# Bundled Amoo binaries

Release automation places an executable named `amoo` in the matching directory before packaging:

- `macos-arm64/amoo`
- `macos-x64/amoo`
- `linux-arm64/amoo`
- `linux-x64/amoo`

Gradle selects the directory matching the build host and architecture, then Compose Desktop stages
its `amoo` file at the application resource root. Each release build must populate only its matching
directory with the verified executable. Development builds can instead set `AMOO_BINARY` to a local
Swift build.
