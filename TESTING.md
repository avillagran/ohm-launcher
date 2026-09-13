# Testing Omarchy Link (for agents / local LLMs)

This guide lets an LLM or a developer verify the plugin **end to end** against a
real OhmLauncher phone, without guessing the API.

## Prerequisites
1. A Linux machine running **Omarchy** (Quickshell). The plugin must be enabled:
   ```bash
   git clone https://github.com/avillagran/omarchy-link \
         ~/.config/quickshell/plugins/cl.villagranquiroz.omarchy-link
   ```
   Then enable `cl.villagranquiroz.omarchy-link` in the Omarchy plugin list and
   reload the shell (`quickshell reload` or log out/in).
2. An Android phone with **OhmLauncher** installed and its local API server
   running (LAN mode). The phone shows its `ohm://<phone-ip>:8753` in the
   "Conectar Omarchy" dialog.
3. Both machines on the same LAN.

## Smoke test (no phone needed)
- The bar shows an `Ohm` button. Clicking it opens the panel (KeyboardPanel).
- The panel shows a QR `omarchy://<pc>:8753?id=omarchy-pc`. This QR is for the
  **phone** to scan; it does NOT need a phone to render.
- If the panel throws a QML error, the contract/UI is wrong — fix the QML, not
  the phone.

## End-to-end test (with phone)
1. On the phone, open the Omarchy connect dialog and note the `ohm://<ip>:8753`.
2. In the plugin panel, call `setPeer("ohm://<ip>:8753")` then `connect()`.
   → log shows `connected: <phone name>`.
3. `pushClipboard("hello from Omarchy")` then check the phone clipboard.
4. `pullClipboard()` → log prints the phone's current clipboard text.
5. `startScreen()` → phone begins sending JPEG frames over WS (see README for
   the frame format). `stopScreen()` stops it.
6. `backupPhotos()` → phone lists DCIM photos (peer downloads via `/omarchy/file`).

## Reloading Quickshell on Omarchy (so the plugin is picked up)

Omarchy 0.3.1 (Quickshell 0.3.1) has no `quickshell reload`. To make a new or
edited plugin load, restart the shell process. **The process must inherit the
graphical session environment**, or it crashes with
`no Qt platform plugin could be initialized`.

From a shell on the Omarchy machine (same user / same session):

```bash
# 1) Find and kill the running shell
pkill -f "quickshell -n -p /usr/share/omarchy/shell"
sleep 2

# 2) Relaunch with the Wayland session environment (adjust ids if needed)
setsid nohup env \
  XDG_RUNTIME_DIR=/run/user/1001 \
  WAYLAND_DISPLAY=wayland-1 \
  QT_QPA_PLATFORM=wayland \
  QT_QPA_PLATFORMTHEME=gtk3 \
  LANG=C.UTF-8 \
  quickshell -n -p /usr/share/omarchy/shell \
  > /tmp/qs.log 2>&1 < /dev/null & disown

# 3) Check it survived (no FATAL in the log)
sleep 5
pgrep -af "quickshell -n -p" | grep -v bash
```

Verify the plugin loads by **opening its panel** from the bar widget. Quickshell
writes its log to `/run/user/<uid>/quickshell/by-id/*/log.qslog`; on a QML
error you will see `TypeError` / `ReferenceError` there.

> Note: `QT_QPA_PLATFORM` must be `wayland`, not `wayland;xcb` — the `xcb`
> fallback fails when there is no X display and crashes the shell.

## Verifying the contract directly (curl from the PC)

Replace `<phone-ip>` with the phone's LAN IP.

```bash
# Discovery
curl http://<phone-ip>:8753/omarchy/discover
# → {"name":"...","model":"...","lan_ip":"...","port":8753,"capabilities":[...]}

# Clipboard round-trip
curl -X PUT http://<phone-ip>:8753/omarchy/clipboard \
     -H 'Content-Type: application/json' -d '{"text":"hi from pc"}'
curl http://<phone-ip>:8753/omarchy/clipboard
# → {"text":"hi from pc"}

# Photos backup (lists DCIM)
curl -X POST http://<phone-ip>:8753/omarchy/photos/backup

# File browser (list a directory; confined to /sdcard, /storage/emulated/0)
curl "http://<phone-ip>:8753/omarchy/files?path=/sdcard"
# → {"path":"/sdcard","parent":"","entries":[{name,path,isDir,size,modified}...]}

# Download a file
curl -O "http://<phone-ip>:8753/omarchy/file?path=/sdcard/OhmLauncher/shared/x.txt"

# Remote control (needs the Ohm accessibility service ENABLED on the phone)
curl -X POST http://<phone-ip>:8753/omarchy/input \
     -H 'Content-Type: application/json' -d '{"action":"tap","x":540,"y":1200}'
curl -X POST http://<phone-ip>:8753/omarchy/input \
     -H 'Content-Type: application/json' \
     -d '{"action":"swipe","x1":540,"y1":1800,"x2":540,"y2":900,"durationMs":400}'
curl -X POST http://<phone-ip>:8753/omarchy/input \
     -H 'Content-Type: application/json' -d '{"action":"key","key":"back"}'
# → {"ok":true} | {"ok":false,"error":"accessibility_disabled"}
# Same payload as a WS message {"type":"input", ...} on /omarchy/ws
# (replies {"type":"input_result", ...}).
```

## Common failure modes
- **Panel won't open**: missing `manageIpc: false` or wrong root type (`Panel`,
  not `Scope`/`PanelWindow`). Use the official Omarchy `Panel` root.
- **`WidgetButton` undefined**: missing `import qs.Ui` (or `qs.Commons`).
- **QR blank**: the `image://qrcode/...` provider must be registered by
  Quickshell/Omarchy; if absent, the Image just stays empty (non-fatal).
- **connect() fails**: phone API server not running, or wrong port (must be
  8753), or firewall blocking LAN. Check `curl /omarchy/discover` first.
- **Link state stuck at `peerName: "phone"` / empty POST bodies**: dart:io's
  `HttpClient` sends `Transfer-Encoding: chunked` unless `contentLength` is
  set, and Python's `http.server` cannot decode chunked bodies (it reads 0
  bytes). The launcher sets `contentLength` explicitly; link_server also
  decodes chunked defensively (`_read_body`). If you add another phone→PC
  POST, set `req.contentLength` or the body will silently vanish.
- **Server wedges after ~30 screen frames**: `write_screen_frame()` holds the
  state lock and calls `log()` every 30th frame — with a plain
  `threading.Lock` that self-deadlocks. It must stay a `threading.RLock`.
- **Clipboard push fails with "Cleartext HTTP traffic not permitted"**: the
  phone app needs `android:usesCleartextTraffic="true"` (the contract is
  plain HTTP on the LAN).
- **Remote input returns `accessibility_disabled`**: enable the Ohm
  accessibility service (Settings → Accessibility). It requires
  `android:canPerformGestures="true"` in the service config xml — without it
  `dispatchGesture()` returns true but the gesture is silently dropped and
  the `GestureResultCallback` never fires. Also: do not block waiting for
  that callback to answer the caller (it may never fire); report the
  synchronous dispatch result instead. After a force-stop the service does
  not rebind by itself — re-toggle `enabled_accessibility_services`.
- **Panel file browser empty / 404**: the phone needs the `/omarchy/files`
  endpoint (contract v2). Downloads use `GET /omarchy/file?path=`.

## Emulator lab (Omarchy in QEMU + Android emulator inside the guest)

Fully offline end-to-end setup used to develop this contract:

1. In the Omarchy guest: install cmdline-tools + `emulator` +
   `system-images;android-34;default;x86_64` (a portable JRE in `$HOME/jdk`
   works for sdkmanager; nested KVM must expose `/dev/kvm`).
2. Start the emulator headless:
   `emulator -avd ohmtest -no-window -no-audio -gpu swiftshader_indirect -accel on`
   and install the OhmLauncher APK with `adb install -r`.
3. Grant storage: `adb shell appops set cl.villagranquiroz.ohm_launcher MANAGE_EXTERNAL_STORAGE allow`.
4. Networking (emulator NAT, no LAN):
   - Guest → phone: `adb forward tcp:8753 tcp:8753` (guest `127.0.0.1:8753`).
   - Phone → guest: the emulator reaches the guest at `10.0.2.2`.
   - Run link_server on a different port with a peer rewrite so the panel
     calls the phone through the forward:
     `OMARCHY_LINK_PORT=8763 OMARCHY_LINK_PEER=127.0.0.1:8753 python3 link_server.py`
5. Simulate scanning the QR (no camera in the emulator):
   `adb shell am start -a android.intent.action.VIEW -d "omarchy://10.0.2.2:8763?id=<hostname>"`
6. MediaProjection consent: `POST /omarchy/screen/start`, then dump
   `uiautomator` and `input tap` the "START NOW" button. Frames land in
   `/tmp/omarchy-screen.jpg` (`GET /omarchy/screen/status` counts them).
7. Drive the VM bar/panel without a VNC viewer: QMP `screendump` +
   `input-send-event` (absolute pointer, range 0..32767); find the green
   Android icon by pixel scan (#4caf50) instead of guessing coordinates.
