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
import hashlib
import hmac
import ipaddress
import mimetypes
import os
import re
import subprocess
import threading
import urllib.request
from urllib.parse import quote, unquote, urlparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

STATE_FILE = "/tmp/omarchy-link-state.json"
SCREEN_FILE = "/tmp/omarchy-screen.png"
HOST = "0.0.0.0"
TOKEN_FILE = Path("/tmp/omarchy-link-token")
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
_THEME_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
_PREVIEW_NAMES = (
    "preview.png", "preview.jpg", "preview.jpeg", "preview.webp", "preview.gif", "preview.bmp",
    "preview.mp4", "preview.m4v", "preview.mov", "preview.webm", "preview.mkv", "preview.avi",
)
_PREVIEW_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp", ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi"}
_VIDEO_EXTENSIONS = {".mp4", ".m4v", ".mov", ".webm", ".mkv", ".avi"}
BACKGROUND_CATEGORIES = ("image", "video", "animated", "retro", "amiga", "audio")
_BACKGROUND_ID = re.compile(r"^[0-9a-f]{64}$")
_MAX_BACKGROUND_BYTES = 64 * 1024 * 1024
_MAX_PREVIEW_BYTES = 16 * 1024 * 1024
_ANIMATED_BACKGROUNDS_PLUGIN = (
    Path.home() / ".config/omarchy/plugins/io.github.avillagran.omarchy-animated-backgrounds"
)
_DEFAULT_OMARCHY_PATH = Path.home() / ".local/share/omarchy"
if not _DEFAULT_OMARCHY_PATH.is_dir():
    _DEFAULT_OMARCHY_PATH = Path("/usr/share/omarchy")


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


def read_omarchy_geometry(runner=subprocess.run) -> dict:
    """Read the live Hyprland radius used by Omarchy's Style singleton."""
    try:
        result = runner(
            ["hyprctl", "-j", "getoption", "decoration:rounding"],
            check=False,
            capture_output=True,
            text=True,
            timeout=3,
        )
        value = json.loads(result.stdout or "{}").get("int")
        if result.returncode == 0 and isinstance(value, (int, float)) and value >= 0:
            return {"cornerRadius": min(float(value), 128)}
    except (OSError, ValueError, TypeError, subprocess.TimeoutExpired):
        pass
    return {"cornerRadius": 0}


def read_omarchy_theme(home=None, runner=subprocess.run) -> dict:
    """Read Omarchy's canonical current colors.toml into the phone contract."""
    root = Path(home or Path.home()) / ".local/state/omarchy/current"
    colors_file = root / "theme/colors.toml"
    try:
        import tomllib
        with colors_file.open("rb") as source:
            raw = tomllib.load(source)
    except (OSError, ValueError):
        raw = {}
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
    payload = {
        "name": name or (colors_file.parent.name if colors_file.is_file() else "Omarchy"),
        "mode": mode,
        "source": "omarchy",
        "colors": colors,
        "geometry": read_omarchy_geometry(runner),
    }
    desktop_background = read_desktop_background(home)
    if desktop_background:
        payload["desktopBackground"] = desktop_background
    background = transferable_omarchy_background(home)
    if background:
        payload["background"] = {
            "name": background.name,
            "mime": mimetypes.guess_type(background.name)[0] or "application/octet-stream",
            "sha256": hashlib.sha256(background.read_bytes()).hexdigest(),
        }
    return payload


def current_omarchy_background(home=None):
    path = Path(home or Path.home()) / ".local/state/omarchy/current/background"
    try:
        resolved = path.resolve(strict=True)
        return resolved if resolved.is_file() else None
    except OSError:
        return None


def _state_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def _bounded_file(path, limit: int):
    try:
        candidate = Path(path).resolve(strict=True)
        return candidate if candidate.is_file() and candidate.stat().st_size <= limit else None
    except (OSError, TypeError, ValueError):
        return None


def read_desktop_background(home=None):
    user_home = Path(home or Path.home())
    current = user_home / ".local/state/omarchy/current"
    background_type = _state_text(current / "bg-source-type")
    background_path = _state_text(current / "bg-source-path")
    if not background_type or not background_path:
        return None
    payload = {"type": background_type, "path": background_path}
    if background_type != "audio":
        return payload
    state_file = user_home / ".local/state/omarchy/audio-background/state.json"
    try:
        state = json.loads(state_file.read_text(encoding="utf-8"))
        if not isinstance(state, dict):
            state = {}
    except (OSError, ValueError, json.JSONDecodeError):
        state = {}

    def integer(name, default):
        value = state.get(name, default)
        return value if isinstance(value, int) and not isinstance(value, bool) else default

    def number(name, default):
        value = state.get(name, default)
        return value if isinstance(value, (int, float)) and not isinstance(value, bool) else default

    payload["ttfx"] = {
        "enabled": state.get("running") if isinstance(state.get("running"), bool) else True,
        "effect": background_path or str(state.get("effect") or "matrix"),
        "text": str(state.get("ttfx_text") or "OMARCHY"),
        "textSize": integer("intro_size", 2),
        "audio": state.get("audio") if isinstance(state.get("audio"), bool) else True,
        "intensity": integer("intensity", 5),
        # Desktop state stores fifths of normal speed (5 == 1.0x).
        "speed": number("speed", 5) / 5.0,
        "resolution": integer("resolution", 1),
        "reactivity": integer("reactivity", 2),
    }
    return payload


def transferable_omarchy_background(home=None):
    desktop = read_desktop_background(home)
    if desktop and desktop.get("type") in ("image", "video"):
        selected = _bounded_file(desktop.get("path"), _MAX_BACKGROUND_BYTES)
        if selected:
            return selected
    return _bounded_file(current_omarchy_background(home), _MAX_BACKGROUND_BYTES)


def _background_plugin_path(plugin_path=None):
    return Path(plugin_path or os.environ.get(
        "OMARCHY_ANIMATED_BACKGROUNDS_PATH", str(_ANIMATED_BACKGROUNDS_PLUGIN)
    ))


def _background_entries(home=None, plugin_path=None, runner=subprocess.run):
    user_home = Path(home or Path.home())
    plugin = _background_plugin_path(plugin_path)
    list_script = plugin / "bin/list.sh"
    if not list_script.is_file():
        return []
    environment = dict(os.environ, HOME=str(user_home))
    entries = []
    seen = set()
    for category in BACKGROUND_CATEGORIES:
        try:
            result = runner(
                [str(list_script), category],
                capture_output=True,
                text=True,
                check=False,
                timeout=120,
                env=environment,
            )
        except (OSError, subprocess.SubprocessError):
            continue
        if result.returncode != 0:
            continue
        for line in result.stdout.splitlines():
            fields = line.split("\t")
            if len(fields) != 6:
                continue
            background_type, path, name, preview, media, applied = fields
            if not background_type or not path or not applied or not re.fullmatch(r"[a-z]+", background_type):
                continue
            digest_input = "\0".join((background_type, path, applied)).encode("utf-8")
            background_id = hashlib.sha256(digest_input).hexdigest()
            if background_id in seen:
                continue
            seen.add(background_id)
            preview_source = _bounded_file(preview, _MAX_BACKGROUND_BYTES)
            if preview_source:
                extension = preview_source.suffix.lower()
                if extension not in _PREVIEW_EXTENSIONS or (
                    extension not in _VIDEO_EXTENSIONS
                    and preview_source.stat().st_size > _MAX_PREVIEW_BYTES
                ):
                    preview_source = None
            entries.append({
                "id": background_id,
                "label": name or Path(path).name or path,
                "type": background_type,
                "preview": "/omarchy/backgrounds/%s/preview" % background_id,
                "hasPreview": bool(preview_source),
                "_path": path,
                "_previewPath": str(preview_source) if preview_source else "",
                "_media": media,
                "_applied": applied,
            })
    return entries


def read_omarchy_background_catalog(home=None, plugin_path=None, runner=subprocess.run) -> dict:
    user_home = Path(home or Path.home())
    current = user_home / ".local/state/omarchy/current"
    current_type = _state_text(current / "bg-source-type")
    current_path = _state_text(current / "bg-source-path")
    entries = _background_entries(user_home, plugin_path, runner)
    current_id = next((
        item["id"] for item in entries
        if item["type"] == current_type
        and current_path in (item["_path"], item["_applied"])
    ), "")
    public_fields = ("id", "label", "type", "preview", "hasPreview")
    return {
        "current": {
            "id": current_id,
            "type": current_type,
            "path": current_path,
        },
        "backgrounds": [
            {field: item[field] for field in public_fields}
            for item in entries
        ],
    }


def select_omarchy_background(background_id: str, home=None, plugin_path=None,
                               runner=subprocess.run) -> bool:
    if not isinstance(background_id, str) or not _BACKGROUND_ID.fullmatch(background_id):
        return False
    user_home = Path(home or Path.home())
    plugin = _background_plugin_path(plugin_path)
    selected = next(
        (item for item in _background_entries(user_home, plugin, runner) if item["id"] == background_id),
        None,
    )
    helper = plugin / "bin/omarchy-parallax-set-type"
    if selected is None or not helper.is_file():
        return False
    environment = dict(os.environ, HOME=str(user_home))
    try:
        result = runner(
            [str(helper), selected["type"], selected["_path"]],
            check=False,
            timeout=120,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=environment,
        )
    except (OSError, subprocess.SubprocessError):
        return False
    if result.returncode != 0:
        return False
    current = user_home / ".local/state/omarchy/current"
    return (
        _state_text(current / "bg-source-type") == selected["type"]
        and _state_text(current / "bg-source-path") == selected["_applied"]
    )


def servable_background_preview(background_id: str, home=None, plugin_path=None,
                                runner=subprocess.run):
    if not isinstance(background_id, str) or not _BACKGROUND_ID.fullmatch(background_id):
        return None
    selected = next(
        (item for item in _background_entries(home, plugin_path, runner) if item["id"] == background_id),
        None,
    )
    if not selected:
        return None
    preview = _bounded_file(selected["_previewPath"], _MAX_BACKGROUND_BYTES)
    if not preview or preview.suffix.lower() not in _PREVIEW_EXTENSIONS:
        return None
    if preview.suffix.lower() not in _VIDEO_EXTENSIONS:
        return preview if preview.stat().st_size <= _MAX_PREVIEW_BYTES else None
    cache = Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))) / "omarchy-link/background-previews"
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / (background_id + ".jpg")
    if target.is_file() and target.stat().st_mtime >= preview.stat().st_mtime:
        return target if target.stat().st_size <= _MAX_PREVIEW_BYTES else None
    temporary = target.with_suffix(".tmp.jpg")
    try:
        subprocess.run(
            ["ffmpeg", "-loglevel", "error", "-y", "-ss", "0", "-i", str(preview),
             "-frames:v", "1", "-vf", "scale='min(1280,iw)':-2", str(temporary)],
            check=True,
            timeout=30,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if temporary.stat().st_size > _MAX_PREVIEW_BYTES:
            temporary.unlink(missing_ok=True)
            return None
        temporary.replace(target)
        return target
    except (OSError, subprocess.SubprocessError):
        temporary.unlink(missing_ok=True)
        return None


def background_preview_thumbnail(background_id: str, home=None, plugin_path=None, preview_path=None):
    """Create a phone-safe JPEG thumbnail for any catalog preview."""
    if not isinstance(background_id, str) or not _BACKGROUND_ID.fullmatch(background_id):
        return None
    if preview_path is None:
        selected = next(
            (item for item in _background_entries(home, plugin_path) if item["id"] == background_id),
            None,
        )
        if not selected:
            return None
        preview_path = selected["_previewPath"]
    preview = _bounded_file(preview_path, _MAX_BACKGROUND_BYTES)
    if not preview or preview.suffix.lower() not in _PREVIEW_EXTENSIONS:
        return None
    cache = Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))) / "omarchy-link/background-phone-previews"
    cache.mkdir(parents=True, exist_ok=True)
    source_key = hashlib.sha256(str(preview).encode("utf-8")).hexdigest()[:12]
    target = cache / (background_id + "-" + source_key + ".jpg")
    try:
        if (target.is_file() and target.stat().st_mtime_ns >= preview.stat().st_mtime_ns
                and target.stat().st_size <= _MAX_PREVIEW_BYTES):
            return target
    except OSError:
        pass
    temporary = target.with_suffix(".tmp.jpg")
    try:
        seek = ["-ss", "1"] if preview.suffix.lower() in _VIDEO_EXTENSIONS else []
        subprocess.run(
            ["ffmpeg", "-loglevel", "error", "-y", *seek, "-i", str(preview),
             "-frames:v", "1", "-vf", "scale='min(1280,iw)':-2", str(temporary)],
            check=True,
            timeout=30,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if not temporary.is_file() or temporary.stat().st_size > _MAX_PREVIEW_BYTES:
            temporary.unlink(missing_ok=True)
            return None
        temporary.replace(target)
        return target
    except (OSError, subprocess.SubprocessError):
        temporary.unlink(missing_ok=True)
        return None


def _theme_roots(home=None, omarchy_path=None):
    user_home = Path(home or Path.home())
    install_root = Path(omarchy_path or os.environ.get("OMARCHY_PATH", str(_DEFAULT_OMARCHY_PATH)))
    return user_home / ".config/omarchy/themes", install_root / "themes"


def resolve_theme(theme_id: str, home=None, omarchy_path=None):
    """Resolve an installed theme by opaque slug, honoring Omarchy user precedence."""
    if not isinstance(theme_id, str) or not _THEME_ID.fullmatch(theme_id):
        return None
    for root in _theme_roots(home, omarchy_path):
        candidate = root / theme_id
        if candidate.is_dir():
            return candidate
    return None


def _preview_in(theme: Path):
    if not theme or not theme.is_dir():
        return None
    by_lower_name = {child.name.lower(): child for child in theme.iterdir() if child.is_file()}
    for name in _PREVIEW_NAMES:
        preview = by_lower_name.get(name)
        if preview:
            return preview
    backgrounds = theme / "backgrounds"
    if backgrounds.is_dir():
        choices = sorted(
            (child for child in backgrounds.iterdir() if child.is_file() and child.suffix.lower() in _PREVIEW_EXTENSIONS),
            key=lambda child: child.name.lower(),
        )
        if choices:
            return choices[0]
    return None


def theme_preview_path(theme_id: str, home=None, omarchy_path=None):
    """Match omarchy-theme-switcher's user preview + stock fallback order."""
    if not isinstance(theme_id, str) or not _THEME_ID.fullmatch(theme_id):
        return None
    user_root, stock_root = _theme_roots(home, omarchy_path)
    return _preview_in(user_root / theme_id) or _preview_in(stock_root / theme_id)


def installed_theme_palette(theme_id: str, home=None, omarchy_path=None) -> dict:
    user_root, stock_root = _theme_roots(home, omarchy_path)
    colors_file = user_root / theme_id / "colors.toml"
    if not colors_file.is_file():
        colors_file = stock_root / theme_id / "colors.toml"
    try:
        import tomllib
        with colors_file.open("rb") as source:
            raw = tomllib.load(source)
    except (OSError, ValueError):
        raw = {}
    mode = str(raw.get("mode", "dark")).lower()
    colors = {str(key): value for key, value in raw.items() if isinstance(value, str) and _COLOR.fullmatch(value)}
    return {
        "name": theme_id,
        "mode": mode if mode in ("dark", "light") else "dark",
        "source": "omarchy",
        "colors": colors,
    }


def read_omarchy_theme_catalog(home=None, omarchy_path=None) -> dict:
    user_home = Path(home or Path.home())
    ids = set()
    for root in _theme_roots(user_home, omarchy_path):
        if not root.is_dir():
            continue
        for child in root.iterdir():
            if child.is_dir() and _THEME_ID.fullmatch(child.name):
                ids.add(child.name)
    try:
        current = (user_home / ".local/state/omarchy/current/theme.name").read_text(encoding="utf-8").strip()
    except OSError:
        current = ""
    themes = []
    for theme_id in sorted(ids, key=str.lower):
        preview = theme_preview_path(theme_id, user_home, omarchy_path)
        # omarchy-theme-switcher adds only themes that produced a preview link.
        if preview is None:
            continue
        themes.append({
            "id": theme_id,
            "label": re.sub(r"[-_]+", " ", theme_id).title(),
            "preview": "/omarchy/themes/%s/preview" % quote(theme_id, safe=""),
            "hasPreview": True,
            "palette": installed_theme_palette(theme_id, user_home, omarchy_path),
        })
    return {"current": current, "themes": themes}


def select_omarchy_theme(theme_id: str, home=None, omarchy_path=None, runner=subprocess.run) -> bool:
    if resolve_theme(theme_id, home, omarchy_path) is None:
        return False
    install_root = Path(omarchy_path or os.environ.get("OMARCHY_PATH", str(_DEFAULT_OMARCHY_PATH)))
    environment = dict(os.environ, OMARCHY_PATH=str(install_root))
    try:
        result = runner(
            ["omarchy-theme-set", theme_id],
            check=False,
            timeout=120,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=environment,
        )
        return result.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def servable_theme_preview(theme_id: str):
    preview = theme_preview_path(theme_id)
    if not preview or preview.stat().st_size > 64 * 1024 * 1024:
        return None
    if preview.suffix.lower() not in _VIDEO_EXTENSIONS:
        return preview
    cache = Path(os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))) / "omarchy-link/theme-previews"
    cache.mkdir(parents=True, exist_ok=True)
    target = cache / (theme_id + ".jpg")
    if target.is_file() and target.stat().st_mtime >= preview.stat().st_mtime:
        return target
    temporary = target.with_suffix(".tmp.jpg")
    try:
        subprocess.run(
            ["ffmpeg", "-loglevel", "error", "-y", "-ss", "0", "-i", str(preview), "-frames:v", "1", "-vf", "scale=1280:-2", str(temporary)],
            check=True,
            timeout=30,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        temporary.replace(target)
        return target
    except (OSError, subprocess.SubprocessError):
        temporary.unlink(missing_ok=True)
        return None


def push_theme_to_phone(state: dict, payload: dict, opener=urllib.request.urlopen) -> bool:
    if not state.get("connected") or not state.get("peerIp") or not payload.get("colors"):
        return False
    host = str(state["peerIp"])
    if ":" in host and not host.startswith("["):
        host = "[%s]" % host
    try:
        port = int(state.get("peerPort", 8753))
        delivered_payload = json.loads(json.dumps(payload))
        if delivered_payload.get("background"):
            phone_path = push_background_to_phone(state, opener)
            if not phone_path:
                return False
            delivered_payload["background"]["phonePath"] = phone_path
        body = json.dumps(delivered_payload).encode("utf-8")
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


def push_background_to_phone(state: dict, opener=urllib.request.urlopen):
    background = transferable_omarchy_background()
    if not background or background.stat().st_size > _MAX_BACKGROUND_BYTES:
        return None
    return push_file_to_phone(state, background, background.name, opener)


def push_file_to_phone(state: dict, source: Path, file_name: str, opener=urllib.request.urlopen):
    if not source.is_file() or source.stat().st_size > 64 * 1024 * 1024:
        return None
    host = str(state.get("peerIp", ""))
    if ":" in host and not host.startswith("["):
        host = "[%s]" % host
    port = int(state.get("peerPort", 8753))
    boundary = "ohm-omarchy-style-%s" % os.getpid()
    safe_name = Path(file_name).name.replace('"', '')
    prefix = (
        "--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
        "Content-Type: %s\r\n\r\n"
        % (boundary, safe_name, mimetypes.guess_type(safe_name)[0] or "application/octet-stream")
    ).encode("utf-8")
    body = prefix + source.read_bytes() + ("\r\n--%s--\r\n" % boundary).encode("ascii")
    request = urllib.request.Request(
        "http://%s:%d/omarchy/file" % (host, port),
        data=body,
        headers={"Content-Type": "multipart/form-data; boundary=%s" % boundary,
                 "Content-Length": str(len(body))},
        method="POST",
    )
    try:
        with opener(request, timeout=30) as response:
            if not 200 <= int(response.status) < 300:
                return None
            result = json.loads(response.read().decode("utf-8"))
            return result.get("path")
    except (OSError, ValueError, json.JSONDecodeError):
        return None


def push_theme_catalog_to_phone(state: dict, opener=urllib.request.urlopen) -> bool:
    if not state.get("connected") or not state.get("peerIp"):
        return False
    catalog = read_omarchy_theme_catalog()
    delivered = {"current": catalog.get("current", ""), "themes": []}
    for item in catalog.get("themes", []):
        preview = servable_theme_preview(item["id"])
        phone_path = ""
        if preview:
            digest = hashlib.sha256(preview.read_bytes()).hexdigest()[:16]
            name = "omarchy-theme-%s-%s%s" % (item["id"], digest, preview.suffix.lower())
            phone_path = push_file_to_phone(state, preview, name, opener) or ""
        delivered["themes"].append({
            "id": item["id"],
            "label": item["label"],
            "previewPath": phone_path,
            "palette": item["palette"],
        })
    host = str(state["peerIp"])
    if ":" in host and not host.startswith("["):
        host = "[%s]" % host
    try:
        port = int(state.get("peerPort", 8753))
        body = json.dumps(delivered).encode("utf-8")
        request = urllib.request.Request(
            "http://%s:%d/omarchy/themes/catalog" % (host, port),
            data=body,
            headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
            method="PUT",
        )
        with opener(request, timeout=30) as response:
            return 200 <= int(response.status) < 300
    except (OSError, ValueError):
        return False


def push_background_catalog_to_phone(state: dict, opener=urllib.request.urlopen) -> bool:
    if not state.get("connected") or not state.get("peerIp"):
        return False
    catalog = read_omarchy_background_catalog()
    preview_paths = {}
    for entry in _background_entries():
        preview_path = entry.get("_previewPath", "")
        # Audio effects often have a representative animated preview while
        # their static poster is only a nearly black first frame.
        if entry.get("type") == "audio" and _bounded_file(entry.get("_media"), _MAX_BACKGROUND_BYTES):
            preview_path = entry["_media"]
        if preview_path:
            preview_paths[entry["id"]] = preview_path
    delivered = {"current": dict(catalog.get("current") or {}), "backgrounds": []}
    for item in catalog.get("backgrounds", []):
        preview = (
            background_preview_thumbnail(item["id"], preview_path=preview_paths.get(item["id"]))
            if item.get("hasPreview")
            else None
        )
        phone_path = ""
        if preview:
            digest = hashlib.sha256(preview.read_bytes()).hexdigest()[:16]
            name = "omarchy-background-%s-%s.jpg" % (item["id"], digest)
            phone_path = push_file_to_phone(state, preview, name, opener) or ""
        delivered["backgrounds"].append({
            "id": item["id"],
            "label": item["label"],
            "type": item["type"],
            "previewPath": phone_path,
        })
    host = str(state["peerIp"])
    if ":" in host and not host.startswith("["):
        host = "[%s]" % host
    try:
        port = int(state.get("peerPort", 8753))
        body = json.dumps(delivered).encode("utf-8")
        request = urllib.request.Request(
            "http://%s:%d/omarchy/backgrounds/catalog" % (host, port),
            data=body,
            headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
            method="PUT",
        )
        with opener(request, timeout=30) as response:
            return 200 <= int(response.status) < 300
    except (OSError, ValueError):
        return False


def process_phone_theme_selection(state: dict, opener=urllib.request.urlopen) -> bool:
    if not state.get("connected") or not state.get("peerIp"):
        return False
    host = str(state["peerIp"])
    if ":" in host and not host.startswith("["):
        host = "[%s]" % host
    port = int(state.get("peerPort", 8753))
    try:
        with opener("http://%s:%d/omarchy/themes/selection" % (host, port), timeout=5) as response:
            pending = json.loads(response.read().decode("utf-8"))
        if not pending.get("pending"):
            return False
        theme_id = pending.get("id", "")
        if not select_omarchy_theme(theme_id):
            return False
        body = json.dumps({"id": theme_id, "status": "applied"}).encode("utf-8")
        request = urllib.request.Request(
            "http://%s:%d/omarchy/themes/selection/ack" % (host, port),
            data=body,
            headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
            method="PUT",
        )
        with opener(request, timeout=5) as response:
            if not 200 <= int(response.status) < 300:
                return False
        push_theme_to_phone(state, read_omarchy_theme(), opener)
        return True
    except (OSError, ValueError, json.JSONDecodeError):
        return False


def process_phone_background_selection(state: dict, opener=urllib.request.urlopen) -> bool:
    if not state.get("connected") or not state.get("peerIp"):
        return False
    host = str(state["peerIp"])
    if ":" in host and not host.startswith("["):
        host = "[%s]" % host
    port = int(state.get("peerPort", 8753))
    try:
        with opener("http://%s:%d/omarchy/backgrounds/selection" % (host, port), timeout=5) as response:
            pending = json.loads(response.read().decode("utf-8"))
        if not isinstance(pending, dict) or not pending.get("pending"):
            return False
        background_id = pending.get("id", "")
        if not isinstance(background_id, str) or not _BACKGROUND_ID.fullmatch(background_id):
            return False
        if not select_omarchy_background(background_id):
            return False
        body = json.dumps({"id": background_id, "status": "applied"}).encode("utf-8")
        request = urllib.request.Request(
            "http://%s:%d/omarchy/backgrounds/selection/ack" % (host, port),
            data=body,
            headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
            method="PUT",
        )
        with opener(request, timeout=5) as response:
            if not 200 <= int(response.status) < 300:
                return False
        push_theme_to_phone(state, read_omarchy_theme(), opener)
        return True
    except (OSError, ValueError, json.JSONDecodeError):
        return False


def theme_catalog_signature() -> str:
    rows = []
    for item in read_omarchy_theme_catalog().get("themes", []):
        preview = theme_preview_path(item["id"])
        stat = preview.stat() if preview else None
        rows.append((item["id"], str(preview or ""), stat.st_size if stat else 0, stat.st_mtime_ns if stat else 0))
    return hashlib.sha256(json.dumps(rows, sort_keys=True).encode("utf-8")).hexdigest()


def background_catalog_signature() -> str:
    catalog = read_omarchy_background_catalog()
    preview_paths = {
        item["id"]: item["_previewPath"]
        for item in _background_entries()
        if item.get("_previewPath")
    }
    rows = []
    for item in catalog.get("backgrounds", []):
        preview = _bounded_file(preview_paths.get(item["id"]), _MAX_BACKGROUND_BYTES)
        stat = preview.stat() if preview else None
        rows.append((item["id"], str(preview or ""), stat.st_size if stat else 0, stat.st_mtime_ns if stat else 0))
    signature_data = {"catalog": catalog, "previews": rows}
    return hashlib.sha256(json.dumps(signature_data, sort_keys=True).encode("utf-8")).hexdigest()


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
    last_catalog = ""
    last_background_catalog = ""
    last_connection = None
    while not stopped.wait(1.0):
        payload = read_omarchy_theme()
        encoded = json.dumps(payload, sort_keys=True)
        updated = sync_theme_once(last_payload, payload_reader=lambda: payload)
        if updated != last_payload:
            log("theme sync -> %s (ok)" % payload.get("name"))
        elif encoded != last_payload:
            log("theme sync -> %s (not connected)" % payload.get("name"))
        last_payload = updated
        state = read_state()
        if state.get("connected"):
            connection = (state.get("peerIp"), state.get("peerPort", 8753), state.get("linkNonce", ""))
            if connection != last_connection:
                last_catalog = ""
                last_background_catalog = ""
                last_connection = connection
            if process_phone_theme_selection(state):
                log("theme selection applied from phone")
            if process_phone_background_selection(state):
                log("background selection applied from phone")
            signature = theme_catalog_signature()
            if signature != last_catalog and push_theme_catalog_to_phone(state):
                last_catalog = signature
                log("theme catalog sync -> ok")
            background_signature = background_catalog_signature()
            if (background_signature != last_background_catalog
                    and push_background_catalog_to_phone(state)):
                last_background_catalog = background_signature
                log("background catalog sync -> ok")
        else:
            # A later reconnect must receive the catalogs even when unchanged.
            last_catalog = ""
            last_background_catalog = ""
            last_connection = None


class Handler(BaseHTTPRequestHandler):
    def _authorized(self) -> bool:
        if self.client_address[0] in ("127.0.0.1", "::1"):
            return True
        try:
            expected = TOKEN_FILE.read_text(encoding="utf-8").strip()
        except OSError:
            expected = ""
        supplied = self.headers.get("X-Omarchy-Link-Token", "")
        return bool(expected and supplied and hmac.compare_digest(expected, supplied))

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
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Omarchy-Link-Token")

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self._cors()
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _file(self, path: Path) -> None:
        body = path.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", mimetypes.guess_type(path.name)[0] or "application/octet-stream")
        self._cors()
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        if not self._authorized():
            self._json(401, {"ok": False, "error": "unauthorized"})
            return
        path = urlparse(self.path).path
        if path == "/omarchy/link":
            self._json(200, read_state())
        elif path == "/omarchy/screen/status":
            self._json(200, read_screen_state())
        elif path == "/omarchy/clipboard":
            self._json(200, {"text": _wl_get_clipboard()})
        elif path == "/omarchy/theme":
            self._json(200, read_omarchy_theme())
        elif path == "/omarchy/themes":
            self._json(200, read_omarchy_theme_catalog())
        elif path == "/omarchy/backgrounds":
            self._json(200, read_omarchy_background_catalog())
        elif path.startswith("/omarchy/backgrounds/") and path.endswith("/preview"):
            encoded_id = path[len("/omarchy/backgrounds/"):-len("/preview")].strip("/")
            preview = servable_background_preview(unquote(encoded_id))
            if preview:
                self._file(preview)
            else:
                self._json(404, {"error": "preview_not_found"})
        elif path.startswith("/omarchy/themes/") and path.endswith("/preview"):
            encoded_id = path[len("/omarchy/themes/"):-len("/preview")].strip("/")
            preview = servable_theme_preview(unquote(encoded_id))
            if preview:
                self._file(preview)
            else:
                self._json(404, {"error": "preview_not_found"})
        elif path == "/omarchy/theme/background":
            background = transferable_omarchy_background()
            if background:
                self._file(background)
            else:
                self._json(404, {"error": "background_not_found"})
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
        if not self._authorized():
            self._json(401, {"ok": False, "error": "unauthorized"})
            return
        if self.path == "/omarchy/backgrounds/select":
            try:
                data = json.loads(self._read_body(4096).decode("utf-8") or "{}")
                if not isinstance(data, dict) or set(data) != {"id"} or not isinstance(data["id"], str):
                    raise ValueError("JSON body must contain only a string id")
                background_id = data["id"]
            except (PayloadTooLarge, UnicodeDecodeError, json.JSONDecodeError, ValueError):
                self._json(400, {"ok": False, "error": "invalid_request"})
                return
            if not select_omarchy_background(background_id):
                self._json(400, {"ok": False, "error": "unknown_or_failed_background"})
                return
            self._json(200, {"ok": True, "current": read_omarchy_background_catalog()["current"]})
        elif self.path == "/omarchy/themes/select":
            try:
                data = json.loads(self._read_body(4096).decode("utf-8") or "{}")
                theme_id = data.get("id", "")
            except (PayloadTooLarge, UnicodeDecodeError, json.JSONDecodeError):
                self._json(400, {"ok": False, "error": "invalid_request"})
                return
            if not select_omarchy_theme(theme_id):
                self._json(400, {"ok": False, "error": "unknown_or_failed_theme"})
                return
            payload = read_omarchy_theme()
            threading.Thread(
                target=lambda: push_theme_to_phone(read_state(), payload),
                daemon=True,
            ).start()
            self._json(200, payload)
        elif self.path == "/omarchy/notify":
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
                "linkNonce": os.urandom(8).hex(),
            }
            write_state(state)
            threading.Thread(
                target=lambda: (push_theme_catalog_to_phone(state), push_theme_to_phone(state, read_omarchy_theme())),
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
            catalog_ok = push_theme_catalog_to_phone(state)
            ok = push_theme_to_phone(state, payload)
            self._json(200 if ok and catalog_ok else 503, {"ok": ok and catalog_ok, "theme": payload.get("name", "Omarchy")})
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
