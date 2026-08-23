# OhmLauncher — AGENTS.md

Guide for agents working on this project.

## What this is

**OhmLauncher** is an Android launcher written in Flutter whose interface is
generated **reactively from JSON** stored in external storage
(`/sdcard/OhmLauncher/widgets_config.json`), with hot reload on save.
It includes a **QML bridge** that runs on Android the plugins written for the
Omarchy ecosystem (Quickshell/QtQuick), a plugin marketplace
(omarchyplugins.com), real installed apps, favorites, multi-desktop, and
launcher-style widget editing (border, drag, reorder, resize).

> Brand note: the project was called "Omarchy Launcher". It was renamed to
> **OhmLauncher** because DHH did not authorize the use of the brand. The code
> remains *compatible with the Omarchy plugin contract*, but all visible
> branding uses "Ohm".

## Identifiers (package / IDs)

| Concept | Value |
|---|---|
| Dart package (pubspec) | `ohm_launcher` |
| Android namespace / applicationId | `cl.villagranquiroz.ohm_launcher` |
| Native MethodChannel | `com.ohm/ohm` |
| Android label (launcher) | `Ohm Launcher` |
| Public data path | `/sdcard/OhmLauncher` |
| Legacy path (migrated once) | `/sdcard/OmarchyLauncher` |
| Seed plugin | `io.github.ohm.demo.clock` |

When renaming, **do not** touch: `omarchyplugins.com` (external service), the
legacy migration path `kLegacyPublicRoot`, and third-party QML component names
(`OmarchyMenuScan`, etc.) that match real plugin types.

## Structure

```
lib/
  main.dart                     # esqueleto: StorageService + DynamicWidgetEngine +
                                # discovery de plugins + `part` de lib/parts/*.dart
  parts/
    home_screen.dart            # estado principal de la home (escritorios, cajas,
                                # cajón, terminal Quake, config, drag & drop de cajas)
    edge_boxes.dart             # _EdgeBox: cajas de borde + interacción 1s/3s/5s
    quake_terminal.dart         # terminal Quake (flutter_pty + xterm); swipe-down
                                # desde la mitad superior; cierra con exit/swipe-up/X
    local_api_server.dart       # servidor HTTP local (puerto 8753) para instalar
                                # bins, ejecutar comandos y abrir el Quake
    shell_executor.dart         # bypass noexec: ejecuta ELF vía linker64 y scripts
                                # vía sh; genera el .ohm_bashrc de funciones
    dynamic_engine.dart         # parser JSON -> árbol de widgets + helpers de color
    settings.dart               # hojas de configuración (escritorio / launcher)
    pickers.dart                # pickers de apps, widgets, plugins y cajas
    ...                         # storage, watcher, barras, radial, drawer, etc.
  qml_bridge/
    qml_parser.dart             # tokenizer + parser de QML a AST
    qml_runtime.dart            # evaluación de expresiones/propiedades QML
    qml_widgets.dart            # AST -> widgets Flutter
  plugin_network.dart           # HttpUtil + PluginRemoteFetcher (Dart puro)
  clock_widget.dart             # ClockText + formatClock (tokens tipo "dddd")
  clock_styles.dart             # estilos del reloj: "particles" (arena, tipo
                                # Arrival) y "ticker" (dígitos rodantes)
android/
  app/src/main/kotlin/com/ohm/ohm_launcher/MainActivity.kt  # canal nativo:
                                # apps instaladas, iconos, batería, launcher por defecto
examples/
  plugins/io.github.ohm.demo.clock/  # plugin de ejemplo sembrado en el dispositivo
test/
  widget_test.dart              # smoke test de la app
  qml_real_test.dart            # 4 tests con QML real de plugins
  fixtures/*.qml                # QML reales usados por los tests
tool/
  qml_parse_check.dart          # CLI: parsea un .qml y reporta
  installer_check.dart          # CLI: prueba el fetcher del marketplace
```

## Data flow

1. `StorageService.ensureInitialized()` → migrates `/sdcard/OmarchyLauncher` if
   it exists, tries the public path `/sdcard/OhmLauncher` (with
   `MANAGE_EXTERNAL_STORAGE`); if permissions are missing, falls back to the
   app's private external storage.
2. `ensureConfigFile()` creates the default `widgets_config.json` if missing.
3. `FileWatcherEngine` (400 ms debounce) watches the JSON and the plugins folder
   and triggers `_reloadConfig()` / `_rescanPlugins()`.
4. `DynamicWidgetEngine.parseDesktop()` builds the widget tree
   (`desktops[]` → `_DesktopPager`; nodes: container, text, clock,
   tiling_layout, spacer, apps_grid, battery, plugin_widget; `span` support).
5. `plugin_widget` nodes are resolved via `PluginSnapshot.latest` and rendered
   with the QML bridge.

## App drawer (swipe up)

- The app list is **NOT** a desktop. It appears when swiping up from the bottom
  bar (`_AppDrawer`).
- The gesture follows the finger: `AnimationController` updated directly in
  `onVerticalDragUpdate`; if reversed before release, the drawer hides again.
- Once open, it can be closed by tapping the dimmed background or dragging the
  top *handle* downward.
- Apps are loaded without icons at startup (lightweight `getInstalledApps`) and
  PNG icons are fetched on demand (`OhmPlatform.getAppIcon`) to avoid blocking
  the main thread.

## Home gestures

- **Swipe up** (from ~45% of the bottom of the screen) → opens the app drawer
  (`_AppDrawer`). **Swipe down** (from the top half) → opens the Quake terminal.
  **Horizontal swipe** → desktop switch (native PageView of
  `_desktop`; `_GestureNavigationOverlay` adds edge swipe when
  `gestureFallback` is active).
- These three `RawGestureDetector`s (drawer + Quake + `_GestureNavigationOverlay`)
  **must stay ABOVE the bars in the `Stack`** (they are inserted after
  `..._buildEdgeBoxes()`), with `HitTestBehavior.translucent`. If placed below,
  the favorites bar (bottom) and the plugins bar (top) intercept the swipes and
  **the gestures stop working** even though the bars are visible.
- `gestureFallback = _systemNavigationMode != 2 && !_accessibilityServiceEnabled
  && gestureNavigationEnabled`. The **accessibility service must stay
  DISABLED** (`accessibility_enabled=0`) so that in-app horizontal swipe works:
  when renaming the package, the service component changes from
  `com.ohm.ohm_launcher` to `cl.villagranquiroz.ohm_launcher` and the system
  setting `enabled_accessibility_services` keeps pointing at the old component →
  the service ends up disabled and, if forced on, `gestureFallback`
  becomes `false` and desktop switching breaks. Keep it disabled.

## Quake terminal and embedded binaries (no Termux)

The launcher runs tools directly (bun, tmux, ssh/dropbear) **without
depending on Termux**. Everything lives in
`/data/data/cl.villagranquiroz.ohm_launcher/files/bin` and **persists** across
`adb install -r`.

- **noexec:** `filesDir/bin` is `noexec` under SELinux, so an ELF does not run
  directly (exit 126). `shell_executor.dart` runs ELF via
  `/system/bin/linker64 <path>` and scripts via `sh <path>`.
- **`.ohm_bashrc`** (auto-generated in `filesDir`) defines a shell function per
  bin (`bun()`, `tmux()`, `ssh()`, …) that invokes the correct loader; the Quake
  terminal loads it via `ENV`. It sets `PATH`, `LD_LIBRARY_PATH`, `SHELL`, `HOME`,
  `TMUX_TMPDIR`, `TMPDIR`, `TERMINFO`.
- **Bundled bins:** `bun` (1.4.0 android), `tmux` (3.7c) +
  `libandroid-support.so`,`libandroid-glob.so`,`libncursesw.so.6`,
  `libevent_core-2.1.so`; `dropbearmulti` + symlinks `ssh`/`dbclient`/`scp`/
  `dropbearkey` + `libtermux-auth.so`,`libz.so.1`,`libcrypto.so.3`.
- **terminfo:** `assets/terminfo/x/xterm-256color` is copied to
  `filesDir/.terminfo` and `TERMINFO` is exported (tmux needs the database).
- **Local API** (`local_api_server.dart`, port `8753`,
  `apiServerEnabled`/`apiServerPort` in `settings.json`): `/command`, `/widget`,
  `/ai`, `/health`, `/install-bin` (base64), `/install-bin-raw?name=` (streaming),
  `/bins`, `/uninstall-bin`, `/quake` (`{open:true|false}`). `_validBinName`
  validates `^[A-Za-z0-9._-]+$`.
- **Quake:** `flutter_pty` + `xterm`. `onExit` closes and reaps the shell (SIGKILL);
  the bottom handle allows drag/tap to close. `toggleCopySelection`
  copies the selection.

## Widget editing

- Long-press on a widget → edit mode (`_editDesktop`/`_editWidget` in
  `_OhmHomeScreenState`), each widget is wrapped in `_EditableWidgetTile`
  (border, drag to reorder, resize cycling `span` 1→2→4→1,
  delete).
- Long-press on the background → radial menu (`_RadialDesktopMenu`): add
  left/right desktop, add widget (`_WidgetPickerSheet`, includes
  installed bar-widget plugins) and settings (`_LauncherSettingsSheet`:
  typography, text scale, background color, default launcher).
- Settings are persisted to `settings.json` via
  `StorageService.loadSettings/saveSettings` and applied with `MediaQuery`
  (textScaler) + `Theme` (fontFamily) in the home's `build`.

## Drag & drop (standard interaction, system-wide)

Every draggable element (edge-box items, boxes, and in the future desktop
widgets) uses the **3-phase ladder** with long-press:

- **1s without moving** → the item under the finger is **highlighted** (phase *item*). Dragging
  reorders the item live within its container; if the finger leaves the
  container, a preview follows the finger and holding it **1s inside another
  container** moves the item there (release confirms).
- **3s without moving** → the highlight moves from the item to the **container edge**
  (phase *box*). Dragging moves the container by its pole (reorders the group) or,
  holding **1s over another pole**, the container snaps and follows the finger
  until release.
- **5s without moving** → opens the element's **settings**.

Implementation in `_EdgeBoxState` (`lib/parts/edge_boxes.dart`):
`RawGestureDetector` with a `LongPressGestureRecognizer` configured with
**`postAcceptSlopTolerance: double.infinity`** — essential: without this the
recognizer **cancels the long-press when the finger moves** after accepting it
and the drag "gets lost" (the edge highlights but nothing happens on move). The
box drag is tracked with `onLongPressMoveUpdate` (box phase → `_handleBoxMove`),
which reports movement across the WHOLE screen as long as the recognizer keeps
the pointer. **CRITICAL so the gesture is not lost:** do NOT call `setState` in
the home during the drag. The poles wireframe uses `ValueNotifier`s
(`_barDragTarget`/`_boxDragAccent`/`_boxDragSourceEdge`) and is rebuilt with
`ValueListenableBuilder` in `_wireframeOverlay`; if `setState` were called in the
home on every `onPointerMove`, the `_EdgeBox` would be rebuilt and the
`LongPressGestureRecognizer` would lose the active pointer (the box stops
following the finger). Only live reordering (`_reorderBoxesLive`) uses `setState`,
and only when the order changes. The ladder
advances **only with the finger still** (`_scheduleLadder`,
`Timer.periodic` 100ms accumulating time since `_stillSince`; `_markMoved`
resets the accumulator once `_kTouchSlop` is exceeded). Thus, if the user moves
the finger, the current mode **persists until release** without jumping to the
next phase: moving after 1s keeps the item drag until release; moving after 3s
keeps the box drag until release. The finger-following preview is an `OverlayEntry` inserted in `Overlay.of(context)`. All reordering is animated:
items within a box via `AnimatedSwitcher` with key `_itemsKey` (+
`AnimatedContainer` on borders/highlights), boxes within their pole via
`AnimatedSwitcher` with the group's order key. Reordering across boxes
uses the global rect registry `_boxRects` (`home_screen.dart`), reported by
each box via `onReportRect`. Reordering an item within a box is committed
with `onItemsReordered`; moving it to another box with `onItemDropped`; the
reordering of a box within its pole is computed in `_onEdgeBoxDragEnd`
comparing the finger coordinate against the group's centers (`_boxRects`).
During a box drag (box phase): the original box fades to 25%
opacity and a **preview** of the full box (all its items, with the accent glow)
follows the finger via `OverlayEntry`. Box-phase finger tracking does NOT
depend on the `LongPressGestureRecognizer` (which may stop reporting
`onLongPressMoveUpdate` when leaving the box): on entering box mode,
`_startBoxCapture()` inserts a **full-screen `OverlayEntry` with a
`Listener`** (`behavior: HitTestBehavior.translucent`) that captures
`onPointerMove`/`onPointerUp` globally, guaranteeing the box follows the
finger across the whole screen (including outside the box). The **poles** are
wide bands (~90px) of **gradient** along the 4 screen edges
(Samsung curved-edge style), drawn in `_wireframeOverlay`/`_PoleGlow`
(`home_screen.dart`): a `LinearGradient` fading from the edge toward
the center; the destination edge (under the finger) is tinted with the **dragged
box's color** (`_boxDragAccent`), the origin stays white, and the rest is very
faint; the glow pulses with a repeating `AnimationController` (neon/glow). The
`boxShadow` gets clipped at screen edges — use `LinearGradient`, not
`boxShadow`, for the edge glow. The box drag uses the favorites-bar pattern
(pan movement following the finger across the whole screen), replicated
with `onLongPressMoveUpdate` + the wireframe's `ValueNotifier`s.

**Reordering boxes within the same edge is live** (moving a box above or below
another on the same side): `_reorderBoxesLive` (home_screen.dart) recomputes
the group's order from the finger coordinate against the centers (`_boxRects`)
and stores it in `_liveBoxOrder`, which `_buildEdgeBoxes` uses temporarily
while dragging; on release, `_onEdgeBoxDragEnd` persists `_liveBoxOrder` (if
the destination is still the source edge) or changes edge (if it is another).

**Rule:** NEVER delete an element via long-press; deletion only happens
from its settings.

## Verification (mandatory after every change)

```bash
cd /home/node/Desarrollo/OhmLauncher
flutter analyze        # debe quedar limpio (0 issues)
flutter test           # 15 tests: smoke + 4 QML reales + parser/clock
dart run tool/qml_parse_check.dart <archivo.qml>   # al tocar el parser QML
```

On device (Redmi 23090RA98G over wireless ADB, serial
`adb-J75T59BAZD45TG85-xfRrxx._adb-tls-connect._tcp`, unstable connection):
`adb install`, `uiautomator dump`, or `exec-out screencap -p` + pixel
analysis with Python/PIL.

## Conventions

- Comments and UI text in **Spanish**.
- Minimal changes; follow the existing style.
- `lib/main.dart` is a `part` skeleton (the files in `lib/parts/*.dart`
  use `part of 'package:ohm_launcher/main.dart';`); new standalone pieces go in
  separate files (`qml_bridge/`, `plugin_network.dart`…).
- Never rename external references to Omarchy (marketplace, contract,
  plugin QML components); do rename all of our own branding to "Ohm".

## Known pending state

- Package `cl.villagranquiroz.ohm_launcher`: **verified on device**. The
  bins installed via API (bun/tmux/dropbear) live in
  `/data/data/cl.villagranquiroz.ohm_launcher/files/bin` and **persist** across
  `adb install -r`.
- **Accessibility service must stay DISABLED** (see Gestures section):
  after the package rename its component changes and, if enabled, breaks in-app
  horizontal swipe. Do not re-enable except by re-pointing to the new component.
- Unstable wireless ADB: sometimes it drops and the phone jumps to Settings. The
  serial is renumbered on reconnect; re-check `adb devices` and redo the forward.
- `favoritesBarVisible` / `bottomBarVisible` in `settings.json`: the favorites
  and plugins bars **must never be hidden** by accident (the bar UI no longer
  collapses them; hiding only happens via settings).
- The app drawer closes correctly by tapping the dimmed background; the
  *handle* drag to close depends on the gesture landing on the
  handle/title area (the app grid consumes the scroll).
  This can be improved with an overscroll `NotificationListener` to close on
  pull-down from the grid.
