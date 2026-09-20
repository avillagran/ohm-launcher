# OhmLauncher

OhmLauncher is a native Android home-screen launcher inspired by Omarchy, with synchronized themes, TTFX backgrounds, widgets, a Quake terminal, configurable edge boxes, and direct integration with Omarchy Linux.

[Watch OhmLauncher videos on X](https://x.com/avillagran/status/2101107848898044190).

## Download and install

- **Google Play edition:** the store build is being prepared with authenticated
  QR pairing and user-approved Omarchy Link features. Its listing URL will be
  added here after publication.
- **Full GitHub edition:** use the release APK below for the complete integration,
  including advanced local administration and remote-control capabilities that
  are not distributed through Google Play.
- [Download OhmLauncher 0.0.3 release APK](https://github.com/avillagran/ohm-launcher/releases/download/v0.0.3/OhmLauncher-0.0.3-release.apk)
- [View the 0.0.3 release and notes](https://github.com/avillagran/ohm-launcher/releases/tag/v0.0.3)

Install or update the downloaded APK with ADB:

```bash
adb install -r OhmLauncher-0.0.3-release.apk
```

Install Omarchy Link on Omarchy Linux, then restart the shell:

```bash
git clone https://github.com/avillagran/omarchy-link.git \
  ~/.config/omarchy/plugins/cl.villagranquiroz.omarchy-link
omarchy-restart-shell
```

Update an existing Omarchy Link installation:

```bash
git -C ~/.config/omarchy/plugins/cl.villagranquiroz.omarchy-link pull --ff-only
omarchy-restart-shell
```

After installation, enable **Omarchy Link** in Omarchy's plugin manager, add its
widget to the bar, and scan its QR code from the phone.

- Android package: `cl.villagranquiroz.ohm_launcher`
- Minimum Android version: Android 7.0 / API 24
- Compile and target SDK: 36
- UI: native Android Views and Canvas
- Native terminal backend: Kotlin + JNI PTY
- TTFX backend: the real Rust TTFX binaries, using framed `--parity-dump --pace-dump` output

## Highlights

### Native launcher

- Android HOME and LAUNCHER activity.
- Fast, pre-indexed application search by label and package name.
- Favorites and edge boxes with persistent JSON configuration.
- Favorite applications can be reordered directly inside the Fav Apps menu.
- Swipe up from the lower half opens the same Fav Apps menu as the bar button.
- Multiple desktops with animated horizontal transitions.
- Direct widget editing with drag, four-corner resize handles, and grid-aware persistence.
- Android `AppWidgetHost` integration for real system widgets.
- Unified Omarchy menu with searchable nested sections and native application icons.
- Gesture arbitration between desktops, app drawer, and Quake terminal.

### TTFX

- Real TTFX effects rendered through the bundled Rust binaries.
- Official Omarchy 81×19 wordmark and five-band final gradient.
- Live effect, text, size, resolution, speed, position, audio intensity, and reactivity controls.
- Full-screen live editing plus an embedded preview using the same runtime as the desktop.
- Compact floating TTFX editor for cycling effects and adjusting size and X/Y without opening settings, with per-desktop visibility control.
- Changes to X/Y and audio parameters update without restarting the process; engine-input changes are restarted and coalesced safely.
- Audio-reactive coloring through Android's audio spectrum APIs.

### Widgets and plugins

- Lossless parsing of the public widget configuration.
- Native clock, particle/hourglass clock, text, battery, app grid, container, tiling, plugin, runtime, and Android system widgets.
- The particle clock keeps stable per-character particle pools, so only changed digits reorganize.
- Omarchy plugin discovery, validation, enable/disable, deletion, installation, and desktop insertion.
- QML tokenizer, parser, expression runtime, render model, and Android Views renderer for the supported Omarchy subset.
- Recursive installation and validation of local QML and JavaScript dependencies.
- Invalid or incomplete plugins remain visible with diagnostics instead of silently rendering an empty widget.

### Quake terminal and local API

- Persistent native PTY shell session.
- Ctrl, Alt, Esc, Tab, and arrow controls.
- Keyboard-aware panel sizing using Android window insets.
- Swipe-up dismissal without accidentally opening the app drawer.
- Local HTTP and WebSocket API for health, commands, widgets, terminal control, clipboard, files, input, themes, and screen sharing.

### Omarchy integration

- `omarchy://` deep links.
- `_ohm._tcp` mDNS advertisement and discovery.
- BLE discovery fallback.
- Clipboard and theme synchronization.
- MediaProjection screen capture.
- Canonical Omarchy theme palette support, including live application to launcher chrome, TTFX, widgets, and allowed Android system-bar appearance.
- Companion desktop plugin under [`omarchy-link/`](omarchy-link/).

The Google Play and full GitHub editions share the launcher experience, bundled
themes, and authenticated Omarchy pairing. Features that require broad storage,
continuous background access, Accessibility remote control, or developer
administration remain exclusive to the full GitHub edition. See
[`docs/play-omarchy-link-plan.md`](docs/play-omarchy-link-plan.md) for the exact
distribution boundary.

## Public data

OhmLauncher uses this public data root:

```text
/sdcard/OhmLauncher/
```

Supported files and directories include:

```text
widgets_config.json
settings.json
favorites.json
runtime_widgets.json
plugins/
plugins.disabled/
```

Unknown JSON fields are preserved when configuration is edited.

## Project layout

```text
app/src/main/kotlin/cl/villagranquiroz/ohm_launcher/
  MainActivity.kt             Launcher lifecycle and Android integrations
  NativeLauncherView.kt       Desktops, widgets, bars, drawer, gestures, and menus
  NativeTtfxView.kt           Framed TTFX process and Canvas renderer
  TtfxSettingsDialog.kt       Full live TTFX editor and preview
  TtfxMiniControlsView.kt     Compact on-desktop TTFX editor
  QuakeTerminalView.kt        Terminal UI and persistent session
  NativePtySession.kt         JNI PTY wrapper
  LocalApiServer.kt           Local REST and WebSocket server
  PluginRepository.kt         Plugin discovery, validation, and lifecycle
  qml/                        QML parser, runtime, model, and renderer

app/src/main/cpp/
  pty.cpp                     Native PTY implementation

app/src/test/                 JVM unit tests
app/src/androidTest/          Android instrumentation tests
omarchy-link/                 Omarchy desktop companion plugin
docs/                         Project documentation
```

## Build

Requirements:

- JDK 17
- Android SDK 36
- Android NDK `28.2.13676358`
- CMake 3.22.1

Build and run unit tests:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it on a connected Android device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Set OhmLauncher as the HOME activity during development:

```bash
adb shell cmd package set-home-activity \
  cl.villagranquiroz.ohm_launcher/.MainActivity
adb shell input keyevent HOME
```

## Verification

Run the JVM suite:

```bash
./gradlew testDebugUnitTest
```

Build the APK:

```bash
./gradlew assembleDebug
```

Run connected instrumentation tests when an emulator or device is available:

```bash
./gradlew connectedDebugAndroidTest
```

OhmLauncher has been exercised on both an Android emulator and a physical Xiaomi device. Features that affect touch routing, AppWidgets, the soft keyboard, TTFX rendering, and launcher behavior should still be validated on a real device before release.

## Companion Omarchy plugin

The desktop-side companion is maintained separately at:

[github.com/avillagran/omarchy-link](https://github.com/avillagran/omarchy-link)

The copy under `omarchy-link/` documents and implements the desktop side of discovery, linking, theme synchronization, clipboard exchange, and remote launcher operations. See [Install Omarchy Link on Omarchy](#install-omarchy-link-on-omarchy) for installation and update commands.

## License

No license has been declared for the native rewrite yet. Add the intended license before publishing binary releases or accepting external contributions.
