# OhmLauncher

A self-managed minimalist Android launcher whose UI is generated reactively from
JSON stored on external storage (`/sdcard/OhmLauncher/widgets_config.json`),
with hot reload on save. It also bridges the [Omarchy](https://omarchy.org)
plugin ecosystem (Quickshell/QtQuick QML) and adds a direct OhmLauncher ↔ Omarchy
connection (file sharing, clipboard/theme sync, photo backup, screen sharing)
over the LAN, Bluetooth, or a `omarchy://` QR code.

## Highlights

- Reactive widgets from JSON (`desktops[]` → container/text/clock/apps-grid/
  battery/plugin-widget nodes, with `span` support).
- QML plugin bridge: interpret Omarchy plugins (manifest.json + QML) live.
- Gesture navigation: swipe up → app drawer, swipe down → Quake terminal,
  swipe left/right → desktop switch. Edge boxes with a 3-step drag ladder
  (1s item / 3s box / 5s settings).
- Quake terminal (flutter_pty + xterm) with native selection handles and an
  on-screen key row (Ctrl/Alt/Esc/Tab/arrows).
- Omarchy integration: mDNS auto-discovery (`_ohm._tcp`), QR fallback
  (`ohm://<phone-ip>:8753`), `omarchy://` QR scanned with the system camera,
  and Bluetooth BLE scan. Shared REST+WS contract in `lib/parts/omarchy_link.dart`.

## Project layout

```
lib/
  main.dart                 # library root (part files below)
  parts/
    home_screen.dart        # home state: desktops, boxes, drawer, Quake, settings
    omarchy_link.dart       # OhmLauncher <-> Omarchy REST+WS contract
    omarchy_discovery.dart  # mDNS announce (nsd) + QR dialog + BLE scanner
    screen_capture.dart     # MediaProjection -> WS frame bridge
    quake_terminal.dart     # Quake terminal + native selection handles
    ...
  l10n/                     # ARB localization (app_en.arb, app_es.arb)
android/.../MainActivity.kt # native channel: apps, icons, battery, screen capture
examples/plugins/io.github.ohm.demo.clock/   # seed plugin shipped to the device
examples/plugins/io.github.ohm.demo.weather/
omarchy-link/                          # standalone Omarchy (Linux) plugin
```

## Install the Omarchy plugin

`omarchy-link/` is a normal Omarchy plugin (Quickshell/QtQuick QML) that lives
in its own repository: **https://github.com/avillagran/omarchy-link**. Install
it like any other Omarchy plugin:

```bash
# Option A — clone straight into your Quickshell plugins directory:
git clone https://github.com/avillagran/omarchy-link \
      ~/.config/quickshell/plugins/cl.villagranquiroz.omarchy-link

# Option B — copy from this repo (same layout):
mkdir -p ~/.config/quickshell/plugins/cl.villagranquiroz.omarchy-link
cp -r omarchy-link/* ~/.config/quickshell/plugins/cl.villagranquiroz.omarchy-link/

# Then enable "cl.villagranquiroz.omarchy-link" from the Omarchy plugin list.
```

The panel shows a QR `omarchy://<pc-ip>:8753?id=<host>`. **Scan it with the
phone's camera** (the normal system camera app) — Android routes the
`omarchy://` intent to OhmLauncher, which connects to your PC. See
`omarchy-link/README.md` for the full contract and the C++ QR image-provider
snippet.

## Build & verify

```bash
flutter pub get
flutter gen-l10n
flutter analyze        # must be clean (0 errors)
flutter test           # smoke + QML + parser/clock tests
flutter build apk --release
adb install -r build/app/outputs/flutter-apk/app-release.apk
```

See `AGENTS.md` for architecture details, conventions, and the verification
checklist.
