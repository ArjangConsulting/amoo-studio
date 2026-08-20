#!/usr/bin/env python3
"""Local smoke test for the real `amoo studio serve` process boundary."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


def read_response(process: subprocess.Popen[bytes]) -> dict:
    headers: dict[str, str] = {}
    while True:
        line = process.stdout.readline()
        if not line:
            stderr = process.stderr.read().decode(errors="replace")
            raise RuntimeError(f"Amoo exited before replying.\n{stderr}")
        if line in (b"\r\n", b"\n"):
            break
        key, value = line.decode("ascii").split(":", 1)
        headers[key.lower()] = value.strip()
    length = int(headers["content-length"])
    return json.loads(process.stdout.read(length))


def call(process: subprocess.Popen[bytes], request_id: int, method: str) -> dict:
    payload = json.dumps({"jsonrpc": "2.0", "id": request_id, "method": method}).encode()
    process.stdin.write(f"Content-Length: {len(payload)}\r\n\r\n".encode() + payload)
    process.stdin.flush()
    response = read_response(process)
    if response.get("error"):
        raise RuntimeError(f"{method} failed: {response['error']}")
    return response["result"]


def resolve_amoo(explicit: str | None) -> Path:
    candidates = [
        explicit,
        os.environ.get("AMOO_BINARY"),
        str(Path(__file__).resolve().parents[2] / "mobile-testing" / ".build" / "debug" / "amoo"),
    ]
    for candidate in candidates:
        if candidate and Path(candidate).is_file():
            return Path(candidate).resolve()
    raise RuntimeError("Build Amoo with `swift build --product amoo`, or pass --amoo/AMOO_BINARY.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--amoo", help="Path to the local Amoo executable")
    parser.add_argument("--require-device", action="store_true", help="Fail unless a running simulator, emulator, or device is discovered")
    args = parser.parse_args()
    executable = resolve_amoo(args.amoo)
    process = subprocess.Popen(
        [str(executable), "studio", "serve"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    try:
        handshake = call(process, 1, "system.handshake")
        if handshake.get("protocolVersion") != 1:
            raise RuntimeError(f"Unsupported Studio protocol: {handshake.get('protocolVersion')}")
        health = call(process, 2, "system.health")
        devices = call(process, 3, "devices.list").get("devices", [])
        mcp = call(process, 4, "mcp.status")
        if health.get("status") != "ready" or not mcp.get("available"):
            raise RuntimeError("Amoo health or MCP readiness check failed")
        if args.require_device and not any(device.get("status") == "Running" for device in devices):
            raise RuntimeError("No running simulator, emulator, or device was discovered")
        print(f"PASS: Amoo {handshake.get('version')} protocol 1; {len(devices)} device(s); MCP {mcp.get('transport')}")
        return 0
    finally:
        process.terminate()
        process.wait(timeout=5)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
