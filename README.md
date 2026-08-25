# Amoo Studio

A pure Compose Multiplatform desktop client powered by the bundled Swift `amoo` executable.
Studio owns presentation state only; device control, sessions, MCP tools, and model integration
remain in Amoo.

Amoo Studio supports macOS and Linux. macOS hosts can run iOS and Android workflows; Linux hosts
run Android workflows through the same Amoo/ShipItSwifty process boundary.

The reusable subprocess transport is the versioned
[process-rpc-kotlin](https://github.com/maniramezan/process-rpc-kotlin) library dependency.

## Documentation

- [Architecture](Architecture.md)
- [Development](docs/development.md)
- [Studio protocol](docs/protocol.md)
- [Amoo test file format](docs/test-format.md)
- [Release packaging](docs/releases.md)
- [Contributing](CONTRIBUTING.md)
- [Security](SECURITY.md)

## Development

Clone Amoo and Amoo Studio as siblings, then build the Swift backend:

```bash
cd ../mobile-testing
swift build --product amoo

cd ../amoo-studio
AMOO_BINARY="$PWD/../mobile-testing/.build/debug/amoo" ./gradlew :composeApp:run
```

Alternatively, install a released `amoo` build via Homebrew and let Studio find it on `PATH`:

```bash
brew tap arjangconsulting/tap
brew install amoo
./gradlew :composeApp:run
```

This flow works with Homebrew on macOS and Linux. Packaged desktop launches also check the standard
Apple Silicon, Intel macOS, and Linuxbrew binary locations when Homebrew is not present on `PATH`.

The desktop app starts `amoo studio serve` and communicates through JSON-RPC 2.0 over
Content-Length-framed stdio. The transport comes from the versioned
[`process-rpc-kotlin`](https://github.com/maniramezan/process-rpc-kotlin) library.

## Architecture

Studio is a presentation client. It must not execute `adb`, `xcrun`, Ollama, or MCP tools itself.
See [Architecture.md](Architecture.md) for repository and release boundaries.
