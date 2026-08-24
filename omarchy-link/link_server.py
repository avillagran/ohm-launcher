#!/usr/bin/env python3
"""Minimal link-state server for the Omarchy Link plugin.

The phone (OhmLauncher) scans the plugin's `omarchy://<pc-ip>:8753?id=<name>`
QR. After showing its "connected" snackbar, OhmLauncher POSTs back to this
server so the PC plugin can reflect the live connection state.

Endpoints:
  POST /omarchy/link        body: {"ip": "<phone-ip>", "name": "<phone-name>"}
                             -> mark connected, persist to STATE_FILE.
  POST /omarchy/link/bye    -> mark disconnected.
  GET  /omarchy/link        -> return current state JSON.

State is written to /tmp/omarchy-link-state.json which the QML panel watches
via a FileView, so the bar icon turns green and the panel shows "Conectado".
"""

import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STATE_FILE = "/tmp/omarchy-link-state.json"
SCREEN_FILE = "/tmp/omarchy-screen.png"
HOST = "0.0.0.0"
PORT = 8753
_lock = threading.Lock()


def _screen_path_for(raw: bytes) -> str:
    # JPEG starts with FFD8; otherwise assume PNG.
    if raw[:2] == b"\xff\xd8":
        return "/tmp/omarchy-screen.jpg"
    return "/tmp/omarchy-screen.png"


def write_screen_frame(raw: bytes) -> None:
    try:
        path = _screen_path_for(raw)
        with _lock:
            with open(path, "wb") as f:
                f.write(raw)
    except OSError:
        pass


def write_state(state: dict) -> None:
    try:
        with _lock:
            with open(STATE_FILE, "w", encoding="utf-8") as f:
                json.dump(state, f)
    except OSError:
        pass


def read_state() -> dict:
    try:
        with _lock:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
    except (OSError, ValueError):
        return {"connected": False, "peerIp": "", "peerName": ""}


class Handler(BaseHTTPRequestHandler):
    def _cors(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self._cors()
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        if self.path == "/omarchy/link":
            self._json(200, read_state())
        else:
            self._json(404, {"error": "not found"})

    def do_POST(self):
        if self.path == "/omarchy/link":
            try:
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length) if length else b"{}"
                data = json.loads(raw.decode("utf-8") or "{}")
            except (ValueError, OSError):
                data = {}
            state = {
                "connected": True,
                "peerIp": str(data.get("ip", "")),
                "peerName": str(data.get("name", "phone")),
            }
            write_state(state)
            self._json(200, state)
        elif self.path == "/omarchy/link/bye":
            write_state({"connected": False, "peerIp": "", "peerName": ""})
            self._json(200, {"connected": False})
        elif self.path == "/omarchy/screen/frame":
            try:
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length) if length else b""
                write_screen_frame(raw)
                self._json(200, {"ok": True, "bytes": len(raw)})
            except (ValueError, OSError):
                self._json(500, {"error": "frame_write_failed"})
        else:
            self._json(404, {"error": "not found"})

    def log_message(self, *args):  # silence default logging
        pass


def main() -> None:
    # Start from a clean (disconnected) state.
    write_state({"connected": False, "peerIp": "", "peerName": ""})
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    srv.serve_forever()


if __name__ == "__main__":
    main()
