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
```

## Common failure modes
- **Panel won't open**: missing `manageIpc: false` or wrong root type (`Panel`,
  not `Scope`/`PanelWindow`). Use the official Omarchy `Panel` root.
- **`WidgetButton` undefined**: missing `import qs.Ui` (or `qs.Commons`).
- **QR blank**: the `image://qrcode/...` provider must be registered by
  Quickshell/Omarchy; if absent, the Image just stays empty (non-fatal).
- **connect() fails**: phone API server not running, or wrong port (must be
  8753), or firewall blocking LAN. Check `curl /omarchy/discover` first.
