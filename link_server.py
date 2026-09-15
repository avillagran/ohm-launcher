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
import ipaddress
import os
import re
import subprocess
import threading
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

STATE_FILE = "/tmp/omarchy-link-state.json"
SCREEN_FILE = "/tmp/omarchy-screen.png"
HOST = "0.0.0.0"
# Port is configurable so the emulator lab can run this server on a different
# port than the adb-forwarded phone API (both default to 8753 otherwise).
PORT = int(os.environ.get("OMARCHY_LINK_PORT", "8753"))
# Optional "ip:port" rewrite of the phone address stored in the state file.
# Lab use: the emulator's NAT IP is unreachable from the PC, which instead
# reaches the phone through `adb forward` on 127.0.0.1. On a real LAN this
# stays unset and the phone's self-reported IP is used as-is.
PEER_OVERRIDE = os.environ.get("OMARCHY_LINK_PEER", "")
# RLock (not Lock): write_screen_frame() holds it and calls log() at every
# 30th frame; a plain Lock self-deadlocks there and wedges the whole server.
_lock = threading.RLock()
_COLOR = re.compile(r"^#[0-9a-fA-F]{3,4}(?:[0-9a-fA-F]{3,4})?$")


SCREEN_STATE_FILE = "/tmp/omarchy-screen-state.json"
_frame_count = 0


def _screen_path_for(raw: bytes) -> str:
    # JPEG starts with FFD8; otherwise assume PNG.
    if raw[:2] == b"\xff\xd8":
        return "/tmp/omarchy-screen.jpg"
    return "/tmp/omarchy-screen.png"


def write_screen_frame(raw: bytes, w: int = 0, h: int = 0) -> None:
    global _frame_count
    try:
        path = _screen_path_for(raw)
        with _lock:
            with open(path, "wb") as f:
                f.write(raw)
            _frame_count += 1
            with open(SCREEN_STATE_FILE, "w", encoding="utf-8") as s:
                import time
                json.dump({"frames": _frame_count, "last": time.time(),
                           "w": w, "h": h}, s)
            if _frame_count % 30 == 0:
                log("frame %d received (%d bytes)" % (_frame_count, len(raw)))
    except OSError:
        pass


def read_screen_state() -> dict:
    try:
        with _lock:
            with open(SCREEN_STATE_FILE, "r", encoding="utf-8") as s:
                return json.load(s)
    except (OSError, ValueError):
        return {"frames": 0, "last": 0}


def log(msg: str) -> None:
    try:
        with _lock, open("/tmp/ls.log", "a", encoding="utf-8") as f:
            import datetime
            f.write(datetime.datetime.now().strftime("%H:%M:%S") + " " + msg + "\n")
    except OSError:
        pass


def _wl_set_clipboard(text: str) -> bool:
    """Copy text into the desktop clipboard (wl-copy on Wayland, xclip on X11)."""
    import subprocess
    for cmd in (["wl-copy"], ["xclip", "-selection", "clipboard"]):
        try:
            subprocess.run(cmd, input=text.encode("utf-8"), timeout=3,
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return True
        except (OSError, subprocess.SubprocessError):
            continue
    return False


def _wl_get_clipboard() -> str:
    """Read the desktop clipboard (wl-paste on Wayland, xclip on X11)."""
    import subprocess
    for cmd in (["wl-paste", "-n"], ["xclip", "-selection", "clipboard", "-o"]):
        try:
            out = subprocess.run(cmd, capture_output=True, timeout=3)
            if out.returncode == 0:
                return out.stdout.decode("utf-8", "replace")
        except (OSError, subprocess.SubprocessError):
            continue
    return ""


def write_state(state: dict) -> None:
    try:
        with _lock:
            with open(STATE_FILE, "w", encoding="utf-8") as f:
                json.dump(state, f)
        log("state -> connected=%s peer=%s" % (state.get("connected"), state.get("peerIp")))
    except OSError:
        pass


def read_state() -> dict:
    try:
        with _lock:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
    except (OSError, ValueError):
        return {"connected": False, "peerIp": "", "peerPort": 8753,
                "peerName": "", "linkPort": PORT}


def read_omarchy_theme(home=None) -> dict:
    """Read Omarchy's canonical current colors.toml into the phone contract."""
    root = Path(home or Path.home()) / ".local/state/omarchy/current"
    colors_file = root / "theme/colors.toml"
    try:
        import tomllib
        with colors_file.open("rb") as source:
            raw = tomllib.load(source)
    except (OSError, ValueError):
        return {"name": "Omarchy", "mode": "dark", "source": "omarchy", "colors": {}}
    mode = str(raw.get("mode", "dark")).lower()
    if mode not in ("dark", "light"):
        mode = "dark"
    colors = {
        str(key): value
        for key, value in raw.items()
        if isinstance(value, str) and _COLOR.fullmatch(value)
    }
    try:
        name = (root / "theme.name").read_text(encoding="utf-8").strip()
    except OSError:
        name = ""
    return {
        "name": name or colors_file.parent.name or "Omarchy",
        "mode": mode,
        "source": "omarchy",
        "colors": colors,
    }


def push_theme_to_phone(state: dict, payload: dict, opener=urllib.request.urlopen) -> bool:
    if not state.get("connected") or not state.get("peerIp") or not payload.get("colors"):
        return False
    host = str(state["peerIp"])
    if ":" in host and not host.startswith("["):
        host = "[%s]" % host
    try:
        port = int(state.get("peerPort", 8753))
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            "http://%s:%d/omarchy/theme" % (host, port),
            data=body,
            headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
            method="PUT",
        )
        with opener(request, timeout=5) as response:
            return 200 <= int(response.status) < 300
    except (OSError, ValueError):
        return False


NOTIFICATION_FIELD_LIMITS = {
    "id": 128,
    "title": 120,
    "message": 4000,
    "source": 80,
    "level": 16,
    "channel": 80,
    "timestamp": 64,
}
NOTIFICATION_LEVELS = ("info", "success", "warning", "error")
MAX_NOTIFICATION_BODY = 8192


class PayloadTooLarge(ValueError):
    pass


def validate_notification(data: dict) -> dict:
    """Validate a notification payload before forwarding it to the phone."""
    if not isinstance(data, dict):
        raise ValueError("JSON body must be an object")
    unknown = sorted(set(data) - set(NOTIFICATION_FIELD_LIMITS))
    if unknown:
        raise ValueError("unknown field: %s" % unknown[0])
    if "message" not in data or data["message"] == "":
        raise ValueError("message is required")
    for field, limit in NOTIFICATION_FIELD_LIMITS.items():
        if field not in data:
            continue
        value = data[field]
        if not isinstance(value, str):
            raise ValueError("%s must be a string" % field)
        if len(value) > limit:
            raise ValueError("%s must be at most %d characters" % (field, limit))
    if "level" in data and data["level"] not in NOTIFICATION_LEVELS:
        raise ValueError("level must be one of: %s" % ", ".join(NOTIFICATION_LEVELS))
    return dict(data)


def push_notification_to_phone(state: dict, payload: dict,
                               opener=urllib.request.urlopen):
    """Forward a notification to a connected phone and return its JSON reply."""
    if not state.get("connected") or not state.get("peerIp"):
        return None
    host = str(state["peerIp"])
    if ":" in host and not host.startswith("["):
        host = "[%s]" % host
    try:
        port = int(state.get("peerPort", 8753))
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            "http://%s:%d/omarchy/notify" % (host, port),
            data=body,
            headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
            method="POST",
        )
        with opener(request, timeout=5) as response:
            if not 200 <= int(response.status) < 300:
                return None
            decoded = json.loads(response.read().decode("utf-8") or "{}")
            return decoded if isinstance(decoded, dict) else {"ok": True}
    except (OSError, ValueError):
        return None


def parse_avahi_peer(output: str):
    candidates = []
    for line in output.splitlines():
        fields = line.split(";")
        if len(fields) < 9 or fields[0] != "=" or fields[4] != "_ohm._tcp":
            continue
        try:
            address = str(ipaddress.ip_address(fields[7]))
            port = int(fields[8])
        except ValueError:
            continue
        if port not in range(1, 65536):
            continue
        candidates.append((fields[2] != "IPv4", {
            "connected": True,
            "peerIp": address,
            "peerPort": port,
            "peerName": fields[3].replace("\\032", " "),
        }))
    return min(candidates, key=lambda item: item[0])[1] if candidates else None


def discover_phone() -> dict:
    try:
        result = subprocess.run(
            ["avahi-browse", "-rtp", "_ohm._tcp"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        return parse_avahi_peer(result.stdout) or {}
    except (OSError, subprocess.SubprocessError):
        return {}


def sync_theme_once(
    last_payload: str,
    payload_reader=read_omarchy_theme,
    state_reader=read_state,
    discover=discover_phone,
    push=push_theme_to_phone,
    state_writer=write_state,
) -> str:
    """Synchronize once and persist reverse mDNS discovery for the panel."""
    payload = payload_reader()
    encoded = json.dumps(payload, sort_keys=True)
    state = state_reader()
    discovered = False
    if not state.get("connected"):
        state = discover()
        discovered = bool(state.get("connected"))
    if not state.get("connected"):
        return last_payload
    if encoded == last_payload and not discovered:
        return last_payload
    if not push(state, payload):
        return last_payload
    persisted = dict(state)
    persisted["linkPort"] = PORT
    state_writer(persisted)
    return encoded


def theme_sync_loop(stop_event=None) -> None:
    """Push every actual Omarchy palette change to the connected launcher."""
    stopped = stop_event or threading.Event()
    last_payload = ""
    while not stopped.wait(1.0):
        payload = read_omarchy_theme()
        encoded = json.dumps(payload, sort_keys=True)
        updated = sync_theme_once(last_payload, payload_reader=lambda: payload)
        if updated != last_payload:
            log("theme sync -> %s (ok)" % payload.get("name"))
        elif encoded != last_payload:
            log("theme sync -> %s (not connected)" % payload.get("name"))
        last_payload = updated


class Handler(BaseHTTPRequestHandler):
    def _read_body(self, max_bytes=None) -> bytes:
        """Read the request body, decoding Transfer-Encoding: chunked when
        present (dart:io's HttpClient sends chunked unless contentLength is
        set, and http.server does not decode it by itself)."""
        te = (self.headers.get("Transfer-Encoding") or "").lower()
        if "chunked" in te:
            out = bytearray()
            while True:
                size_line = self.rfile.readline(65536).strip()
                try:
                    size = int(size_line.split(b";")[0], 16)
                except ValueError:
                    break
                if size == 0:
                    # Consume trailers up to the blank line.
                    while self.rfile.readline(65536) not in (b"\r\n", b"\n", b""):
                        pass
                    break
                if max_bytes is not None and len(out) + size > max_bytes:
                    raise PayloadTooLarge()
                out += self.rfile.read(size)
                self.rfile.read(2)  # trailing CRLF
            return bytes(out)
        length = int(self.headers.get("Content-Length", "0"))
        if max_bytes is not None and length > max_bytes:
            raise PayloadTooLarge()
        return self.rfile.read(length) if length else b""

    def _cors(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
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
        elif self.path == "/omarchy/screen/status":
            self._json(200, read_screen_state())
        elif self.path == "/omarchy/clipboard":
            self._json(200, {"text": _wl_get_clipboard()})
        elif self.path == "/omarchy/theme":
            self._json(200, read_omarchy_theme())
        else:
            self._json(404, {"error": "not found"})

    @staticmethod
    def _peer_from(data: dict) -> tuple:
        """Resolve the phone address the panel should call. PEER_OVERRIDE wins
        (emulator lab); otherwise the phone's self-reported ip (+api port)."""
        if PEER_OVERRIDE:
            ip, _, port = PEER_OVERRIDE.partition(":")
            return ip, int(port or "8753")
        ip = str(data.get("ip", ""))
        try:
            port = int(data.get("port", 8753))
        except (TypeError, ValueError):
            port = 8753
        return ip, port

    def do_POST(self):
        if self.path == "/omarchy/notify":
            content_type = (self.headers.get("Content-Type") or "").split(";", 1)[0].strip().lower()
            if content_type != "application/json":
                self._json(415, {"ok": False, "error": "content_type_must_be_json"})
                return
            try:
                raw = self._read_body(MAX_NOTIFICATION_BODY)
                data = json.loads(raw.decode("utf-8"))
                payload = validate_notification(data)
            except PayloadTooLarge:
                self._json(413, {"ok": False, "error": "payload_too_large"})
                return
            except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as error:
                self._json(400, {"ok": False, "error": "invalid_request",
                                 "detail": str(error)})
                return
            state = read_state()
            discovered = False
            if not state.get("connected") or not state.get("peerIp"):
                state = discover_phone()
                discovered = bool(state.get("connected") and state.get("peerIp"))
            if not state.get("connected") or not state.get("peerIp"):
                self._json(503, {"ok": False, "error": "phone_unavailable"})
                return
            if discovered:
                persisted = dict(state)
                persisted["linkPort"] = PORT
                write_state(persisted)
            phone_reply = push_notification_to_phone(state, payload)
            if phone_reply is None:
                self._json(503, {"ok": False, "error": "phone_delivery_failed"})
                return
            log("notification forwarded to connected phone")
            self._json(200, {"ok": True, "delivered": True, "phone": phone_reply})
        elif self.path == "/omarchy/link":
            try:
                raw = self._read_body()
                data = json.loads(raw.decode("utf-8") or "{}")
            except (ValueError, OSError):
                raw, data = b"", {}
            log("link POST from %s body=%s" % (self.client_address[0], raw[:200]))
            ip, port = self._peer_from(data)
            state = {
                "connected": True,
                "peerIp": ip,
                "peerPort": port,
                "peerName": str(data.get("name", "phone")),
                "linkPort": PORT,
            }
            write_state(state)
            threading.Thread(
                target=lambda: push_theme_to_phone(state, read_omarchy_theme()),
                daemon=True,
            ).start()
            self._json(200, state)
        elif self.path == "/omarchy/link/bye":
            write_state({"connected": False, "peerIp": "", "peerPort": 8753,
                         "peerName": "", "linkPort": PORT})
            self._json(200, {"connected": False})
        elif self.path == "/omarchy/theme/push":
            payload = read_omarchy_theme()
            state = read_state()
            if not state.get("connected"):
                state = discover_phone()
            ok = push_theme_to_phone(state, payload)
            self._json(200 if ok else 503, {"ok": ok, "theme": payload.get("name", "Omarchy")})
        elif self.path.startswith("/omarchy/screen/frame"):
            try:
                raw = self._read_body()
                # Phone pixel size arrives as ?w=&h= so the panel can map
                # remote-control taps back onto the phone screen.
                from urllib.parse import urlparse, parse_qs
                q = parse_qs(urlparse(self.path).query)
                w = int(q.get("w", ["0"])[0] or 0)
                h = int(q.get("h", ["0"])[0] or 0)
                write_screen_frame(raw, w, h)
                self._json(200, {"ok": True, "bytes": len(raw)})
            except (ValueError, OSError):
                self._json(500, {"error": "frame_write_failed"})
        else:
            self._json(404, {"error": "not found"})

    def do_PUT(self):
        # Phone (ClipboardMonitorService) pushes copied text here; mirror it
        # into the Wayland clipboard so it is paste-able on the desktop.
        if self.path == "/omarchy/clipboard":
            try:
                raw = self._read_body()
                data = json.loads(raw.decode("utf-8") or "{}")
            except (ValueError, OSError):
                data = {}
            text = str(data.get("text", ""))
            ok = _wl_set_clipboard(text)
            log("clipboard PUT (%d chars) wl-copy=%s" % (len(text), ok))
            self._json(200 if ok else 500, {"ok": ok, "chars": len(text)})
        else:
            self._json(404, {"error": "not found"})

    def log_message(self, *args):  # silence default logging
        pass


def main() -> None:
    # Bind BEFORE writing the clean state: if another instance already owns the
    # port (e.g. one started manually or by a previous panel open), this process
    # must fail here without resetting the shared state file to "disconnected".
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    # Start from a clean (disconnected) state.
    write_state({"connected": False, "peerIp": "", "peerPort": 8753,
                 "peerName": "", "linkPort": PORT})
    log("link server listening on %s:%d" % (HOST, PORT))
    threading.Thread(target=theme_sync_loop, daemon=True, name="omarchy-theme-sync").start()
    srv.serve_forever()


if __name__ == "__main__":
    main()
