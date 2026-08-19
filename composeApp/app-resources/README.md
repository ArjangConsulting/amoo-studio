# Bundled Amoo binaries

Release automation places an executable named `amoo` in the matching directory before packaging:

- `macos-arm64/amoo`
- `macos-x64/amoo`

Compose Desktop stages the matching binary into the application resources directory. Development
builds can instead set `AMOO_BINARY` to a local Swift build.
