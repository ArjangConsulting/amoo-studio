#!/usr/bin/env python3
"""Local smoke test for the real `amoo studio serve` process boundary."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time


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


def call(process: subprocess.Popen[bytes], request_id: int, method: str, params: dict | None = None) -> dict:
    request = {"jsonrpc": "2.0", "id": request_id, "method": method}
    if params is not None:
        request["params"] = params
    payload = json.dumps(request).encode()
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
    parser.add_argument("--exercise-tools", action="store_true", help="Run a real screenshot operation and verify its report artifact")
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
        running = next((device for device in devices if device.get("status") == "Running"), None)
        if args.exercise_tools and not running:
            available = next((device for device in devices if device.get("status") == "Available"), None)
            if available:
                call(process, 5, "devices.start", {"id": available["id"]})
                devices = call(process, 6, "devices.list").get("devices", [])
                running = next((device for device in devices if device.get("status") == "Running"), None)
        if args.require_device and not running:
            raise RuntimeError("No running simulator, emulator, or device was discovered")
        if args.exercise_tools:
            if not running:
                raise RuntimeError("--exercise-tools requires a running device")
            test = {
                "formatVersion": 1,
                "name": "Studio local tool smoke",
                "description": "Exercises the real driver boundary",
                "platform": running["platform"],
                "steps": [{"id": "step-1", "instruction": "Capture the current screen", "expected": "A screenshot artifact is produced"}],
                "compiledPlan": {
                    "compiler": "studio-smoke",
                    "compilerVersion": "1",
                    "toolOperations": [{"id": "operation-1", "tool": "take_screenshot", "arguments": {}}],
                },
            }
            started = call(process, 7, "tests.start", {"test": test, "deviceId": running["id"]})
            request_id = 8
            while True:
                status = call(process, request_id, "tests.status", {"runId": started["runId"]})
                request_id += 1
                if status["state"] != "Running":
                    break
                time.sleep(0.25)
            if status["state"] != "Passed":
                raise RuntimeError(f"Tool smoke failed: {status['message']}")
            reports = call(process, request_id, "reports.list")["reports"]
            report = next((item for item in reports if item["id"] == status["reportId"]), None)
            if not report or not report.get("artifacts") or not all(Path(path).is_file() for path in report["artifacts"]):
                raise RuntimeError("Tool smoke passed without a readable screenshot artifact")
            print(f"PASS: real {running['platform']} tool execution produced {report['artifacts'][0]}")
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
