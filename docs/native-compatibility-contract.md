# OhmLauncher Native Compatibility Contract

## 1. Purpose and authority

This document is the migration contract for the native Android rewrite of OhmLauncher. It consolidates the observable Flutter launcher behavior, Android bridge behavior, persistence formats, and Omarchy interoperability contract. Unless a section is explicitly marked as a defect to fix, the native application must preserve these values and behaviors.

This contract describes two different states:

- **Required contract**: compatibility that the completed native rewrite must provide.
- **Current native status**: what is present in the Kotlin source at the time of inspection. **Implemented** means it is wired into the running native application unless the text explicitly says “library only.” **Partial** means some code exists but the end-to-end behavior is incomplete. **Pending** means the behavior is absent from the running native application.

The Flutter implementation remains the behavioral reference when this document does not explicitly resolve a discrepancy. Additional JSON properties must remain tolerated and preserved where practical. Unknown widget types and malformed user configuration must produce visible diagnostics rather than terminate the launcher.

Inspection basis:

- Flutter inventory report: `/home/node/.hermes/cache/delegation/subagent-summary-0-20260912_153219_641849.txt`
- Android/API inventory report: `/home/node/.hermes/cache/delegation/subagent-summary-1-20260912_153219_645658.txt`
- Flutter reference guide: `/home/node/Desarrollo/ohm-launcher-flutter/AGENTS.md`
- Native source: `/home/node/Desarrollo/ohm-launcher/app/src/main`

## 2. Current native implementation summary

| Area | Current native status | Concrete evidence and remaining work |
|---|---|---|
| Identity and manifest | **Implemented** | Namespace/application ID, HOME/LAUNCHER/deep-link filters, cleartext, legacy storage, service declarations, Termux visibility, and `ACTION_PROCESS_TEXT` queries are present. |
| Storage migration and `widgets_config.json` | **Implemented** | Legacy migration, public/private fallback, atomic config/favorites/settings/runtime writes, built-in plugin seeding, and reactive config/plugin watching with 400 ms plugin debounce are wired. |
| Desktop/model parsing | **Implemented/partial** | Wallpaper, backgrounds, desktops, complete widget geometry, edge boxes, TTFX, and the 14×10 grid parse and render. Exact Flutter error-card styling remains to be matched. |
| TTFX and audio | **Implemented** | Native framed TTFX Canvas rendering, official Omarchy art, restart policy, position controls, runtime microphone permission, FFT bands, beat and audio-reactive tint are mounted. |
| Apps and favorites | **Implemented/partial** | Installed apps, search, launch, ordered persistence, toggle UI, visibility, bar edge and layout modes are wired. Finger-follow drawer motion and favorite drag reordering still need exact parity. |
| Android AppWidgets | **Implemented** | Provider inventory, system authorization, host lifecycle, persisted host IDs, sizing, creation and deletion are wired and verified with the Android Analog widget. |
| Plugins and QML | **Implemented** | Validation, enable/disable/delete, seed plugin, QML/JSON rendering, hot reload, picker, manager, marketplace fetch and atomic install are wired. |
| Edge boxes and desktop editing | **Implemented/partial** | Edge parsing/rendering, four-edge placement, compact/title controls, 14×10 placement, explicit edit overlay, move/resize/reorder/delete operations and drag target persistence exist. Exact 1 s/3 s/5 s edge-box gesture parity remains. |
| Gestures | **Partial** | Desktop switching, drawer gestures, Quake gesture, explicit widget edit mode and accessibility Back/Home/Recents exist. Finger-follow drawer and exact edge-box timing remain. |
| Shell, bins, API, Quake | **Implemented/partial** | Shell, noexec bin wrappers, install/list/remove API, running LAN server and persistent Quake UI with control row are verified. The shell is pipe-backed rather than a true PTY; `/ai` and Termux broadcast execution remain. |
| Omarchy peer/deep link | **Implemented** | Strict links, root-level `settings.json` persistence, notification, clipboard service, peer probe/disconnect and MediaProjection frame upload are wired. |
| Phone Omarchy REST | **Implemented** | Discover, clipboard, theme, files, upload/download, input, screen and photos routes run through the Android adapter with bounds and path confinement. |
| Omarchy WebSocket | **Implemented** | RFC6455 upgrade, peer hello, protocol ping/pong/close, JSON ping, input results, screen control and REST-originated broadcasts are implemented and tested. |
| Discovery | **Implemented/partial** | A single `_ohm._tcp` mDNS service and BLE named-peer scanning are wired. A rendered phone QR remains pending. |
| Clipboard, screen, remote input | **Implemented** | Clipboard GET/PUT/push, MediaProjection start/stop/frame upload, REST/WS input, and accessibility injection are connected end to end. |

## 3. Identity, Android application, and storage

### 3.1 Fixed identity

The following identifiers are compatibility-sensitive and must not change:

| Concept | Required value | Current native status |
|---|---|---|
| Android namespace | `cl.villagranquiroz.ohm_launcher` | **Implemented** |
| Android application ID | `cl.villagranquiroz.ohm_launcher` | **Implemented** |
| Visible application name | `Ohm Launcher` | **Implemented** through `@string/app_name` |
| Public data root | `/sdcard/OhmLauncher` | **Implemented** |
| Legacy migration root | `/sdcard/OmarchyLauncher` | **Implemented** |
| Private binary root | `/data/data/cl.villagranquiroz.ohm_launcher/files/bin` (equivalently `filesDir/bin`) | **Library only** |
| Seed plugin ID | `io.github.ohm.demo.clock` | **Pending** |
| Legacy Flutter method channel | `com.ohm/ohm` | **Pending/not needed internally**, but required if a Flutter compatibility shell is retained |
| Legacy audio event channel | `com.ohm/audio_spectrum` | **Pending/not needed internally**, but required by a compatibility shell |
| Legacy screen channel | `ohm/screen` | **Pending/not needed internally**, but required by a compatibility shell |
| Legacy AppWidget platform view type | `com.ohm/appwidget` | **Pending** |

Do not rename external Omarchy identities, `omarchyplugins.com`, the legacy migration path, or third-party QML type names.

### 3.2 Root selection and bootstrap

Required boot order:

1. If `/sdcard/OmarchyLauncher` exists and `/sdcard/OhmLauncher` does not, migrate the entire legacy directory to the new path exactly once.
2. If all-files/public-storage access is available, use `/sdcard/OhmLauncher` and verify it is writable.
3. Otherwise use the app-specific external storage directory plus `/OhmLauncher`.
4. If app-specific external storage is unavailable, use the private documents directory plus `/OhmLauncher`.
5. Create the selected root and the default `widgets_config.json` if absent.
6. Seed `plugins/io.github.ohm.demo.clock` if absent.
7. Load `settings.json`, `favorites.json`, and `runtime_widgets.json`.
8. Discover active and disabled plugins.
9. Load installed launchable apps.
10. Observe `widgets_config.json` and `plugins/` with a 400 ms debounce and reload reactively. Changes to `plugins.disabled/` should also trigger a rescan in the rewrite.

Current native status: steps 1–3 and config creation are implemented; step 4 is pending; steps 6–8 are not wired; installed apps are loaded; only `widgets_config.json` is watched, without debounce.

### 3.3 Exact persistent files and directories

All paths below are relative to the selected launcher root unless absolute:

| Path | Required purpose | Current native status |
|---|---|---|
| `widgets_config.json` | Desktops, desktop widgets, wallpaper, and edge boxes | **Partial** |
| `settings.json` | Global preferences and persisted Omarchy peer/control state | **Pending** |
| `favorites.json` | Ordered app favorite keys | **Implemented** |
| `runtime_widgets.json` | API/AI-injected runtime overlay widgets | **Pending** |
| `plugins/<id>/manifest.json` | Active plugin manifest | **Library only** |
| `plugins/<id>/<entryPoint>` | Active plugin QML surfaces/assets | **Library only** |
| `plugins.disabled/<id>/...` | Disabled plugins | **Library only** |
| `shared/<filename>` | Files uploaded through `POST /omarchy/file` | **Pending adapter** |
| `filesDir/bin/<name>` | Installed private commands | **Library only** |
| `filesDir/.terminfo/x/xterm-256color` | Quake/tmux terminal definition | Asset exists; copy/bootstrap **pending** |
| `filesDir/.ohm_bashrc` | Generated shell wrappers/environment | **Pending** |
| `filesDir/ttfx-native/ttfx` | Extracted architecture-specific TTFX executable | **Implemented** |

UI-owned JSON writes must replace the complete JSON document with two-space indentation. Writes should be atomic. Additional properties must not be discarded merely because the current UI does not understand them. Corrupt JSON must leave the process alive and display an error card/diagnostic. The current native application uses atomic replacement for config/favorites, but currently reports malformed config with a Toast and keeps the last config rather than rendering an error card.

## 4. `widgets_config.json` contract

### 4.1 Root, desktop, geometry, and edge-box fields

All objects allow additional properties.

| Scope | Field | Type/allowed values | Effective default or rule |
|---|---|---|---|
| root | `wallpaper` | color string | `#0B0F14` |
| root | `desktops` | array of desktop objects | At least one effective desktop; create a default if absent/empty |
| root | `edgeBoxes` | array of edge-box objects | `[]` |
| desktop | `name` | string | `Escritorio N` |
| desktop | `widgets` | array of widget nodes | `[]` |
| desktop | `background` | color string | root `wallpaper`, then `#0B0F14` |
| desktop | `backgroundImage` | string path/URI | `""` |
| desktop | `fontFamily` | string | `""` |
| desktop | `titleFont` | string | `""` |
| desktop | `gridColumns` | integer, minimum 1 | `14` |
| desktop | `gridRows` | integer, minimum 1 | `10` |
| desktop | `ttfxBackground` | boolean | `true` |
| desktop | `ttfxEffect` | string | `"matrix"` |
| desktop | `ttfxText` | string, maximum 160 characters | `"OHM"` |
| desktop | `ttfxTextSize` | integer `1..7` | `3` |
| desktop | `ttfxTextX` | number `0..1` | `0.5` |
| desktop | `ttfxTextY` | number `0..1` | `0.5` |
| desktop | `ttfxAudio` | boolean | `true` |
| desktop | `ttfxIntensity` | integer `0..10` | `5` |
| desktop | `ttfxSpeed` | number | `1.0`; native currently clamps to `0.2..5.0` |
| desktop | `ttfxResolution` | integer `1..8` | `2` |
| desktop | `ttfxReactivity` | integer `0..5` | `2` |
| desktop | `ttfxAccent` | color string | `#66E0FF` |
| widget geometry | `x` | integer | `0` |
| widget geometry | `y` | integer | widget array index |
| widget geometry | `w` | integer, minimum 1 | `w`, else `span`, else `4` |
| widget geometry | `h` | integer, minimum 1 | `1` |
| widget geometry | `span` | integer | Legacy width fallback; preserve if present |
| edge box | `id` | string | Required; new box: `box_<epochMillis>` |
| edge box | `name` | string | New box: `Caja N` |
| edge box | `edge` | `top`, `bottom`, `left`, `right` | `bottom` |
| edge box | `direction` | `horizontal`, `vertical`, `grid`, `list` | `horizontal`; new left/right boxes use `vertical`, top/bottom use `horizontal` |
| edge box | `visible` | boolean | `true` |
| edge box | `showTitle` | boolean | `true` |
| edge box | `compact` | boolean | `false` |
| edge box | `compactItem` | integer, minimum 0 | `0` |
| edge box | `color` | color string | `#66E0FF` |
| edge box | `items` | array of edge items | Required by canonical schema; new box uses `[]` |
| edge item | `type` | `app`, `system_widget`, `plugin` | Required |
| edge item | `package` | string | `""` when not applicable |
| edge item | `activity` | string | `""` when not applicable |
| edge item | `label` | string | `""` when absent |
| edge item | `provider` | flattened component string | `""` when not applicable |
| edge item | `pluginId` | string | `""` when not applicable |

Colors accept exactly the existing parser forms `#RGB`, `#RRGGBB`, and `#AARRGGBB`. Parsers must apply defaults and tolerate extra properties.

Current native status: the root wallpaper and desktop `name`, `widgets`, `background`, `backgroundImage`, and all TTFX fields except `ttfxAccent` parse. `fontFamily`, `titleFont`, grid dimensions, geometry, and all edge boxes/items are pending. Native desktop background currently defaults directly to `#0B0F14` rather than falling back through root `wallpaper`.

### 4.2 Complete widget-node field vocabulary

`type` is required by the canonical format. Accepted types are:

- `container`
- `text`
- `clock`
- `tiling_layout`
- `spacer`
- `apps_grid`
- `battery`
- `plugin_widget`
- `system_widget`
- `box`

Every node may contain the geometry fields `x`, `y`, `w`, `h`, and `span`, plus the following complete field vocabulary. Fields irrelevant to a node type are ignored but preserved.

| Field | Type/allowed values | Default/semantics |
|---|---|---|
| `type` | enum above | Required; unknown type produces an error card |
| `x` | integer | `0` |
| `y` | integer | node index |
| `w` | integer ≥ 1 | `span`, then `4` |
| `h` | integer ≥ 1 | `1` |
| `span` | integer | legacy width/flex hint |
| `children` | array of nodes | `[]` |
| `items` | array of edge items | `[]`; used by `box` compatibility nodes |
| `value` | string | `""`; canonical text content |
| `content` | string | `""`; accepted content alias |
| `format` | string | clocks: `HH:mm` |
| `style` | `text`, `particles`, `ticker` | clocks: `text` |
| `orientation` | `row`, `column` | layout/spacer: `column` |
| `direction` | `up`, `down`, `horizontal`, `vertical`, `grid`, `list` | ticker: `up`; collection-specific otherwise |
| `color` | color string | type-specific |
| `fontSize` | number | `14` |
| `fontWeight` | string | text: `w400`; battery: `w500` |
| `letterSpacing` | number | `0` |
| `lineHeight` | number | platform/default line height |
| `textAlign` | `left`, `center`, `right`, `justify` | type/layout default |
| `role` | string | `""` |
| `maxLines` | integer ≥ 1 | unconstrained unless supplied |
| `width` | number | unconstrained unless supplied |
| `height` | number | unconstrained unless supplied |
| `padding` | number, string, or object | `0` |
| `borderRadius` | number | `0` |
| `alignment` | `topLeft`, `topCenter`, `topRight`, `centerLeft`, `center`, `centerRight`, `bottomLeft`, `bottomCenter`, `bottomRight` | `center` where a default is required |
| `mainAxisAlignment` | `start`, `center`, `end`, `spaceBetween`, `spaceAround`, `spaceEvenly` | `start` |
| `crossAxisAlignment` | `start`, `center`, `end`, `stretch`, `baseline` | `center` |
| `spacing` | number | `0` for layouts |
| `flex` | integer | `0`; values > 0 expand a tiling child |
| `columns` | integer ≥ 1 | apps grid: `4` |
| `showIcon` | boolean | battery: `true` |
| `density` | number | particle clock: `3` |
| `particleSize` | number | particle clock: `1.6` |
| `wobble` | number | particle clock: `1` |
| `shake` | boolean | particle clock: `true` |
| `digitWidth` | number | ticker clock: `0` |
| `pluginId` | string | Required to resolve `plugin_widget` |
| `kind` | string | plugin widget: `bar-widget` |
| `provider` | flattened component string | system-widget provider |
| `package` | string | app/provider package |
| `label` | string | system widget: `Widget del sistema` |
| `minWidth` | integer | provider-reported minimum width |
| `minHeight` | integer | provider-reported minimum height |

Insets behavior:

- Numeric `padding`: all sides.
- String with one value: all sides.
- String with two values: vertical, horizontal.
- String with four values: top, right, bottom, left.
- Object form allows numeric `left`, `top`, `right`, and `bottom`; absent sides default to zero; additional keys are tolerated.

Type defaults and behavior:

- `container`: transparent, zero padding; multiple children are a centered column.
- `text`: size 14, color `#E8F1F8`, weight `w400`, letter spacing 0. Read `value` as canonical content; `content` may be accepted for compatibility. The current native implementation incorrectly reads `text` and `size`; it must add the canonical fields without deleting alias tolerance.
- `clock`: `HH:mm`, `text`, size 14, updates every second. `particles` uses density 3, particle size 1.6, wobble 1, shake true. `ticker` uses direction `up`, digit width 0.
- `tiling_layout`: column, spacing 0, main axis start, cross axis center; child `flex > 0` expands.
- `spacer`: column, size 16.
- `apps_grid`: four columns, no nested scrolling, fixed spacing 14.
- `battery`: size 14, `#7EE787`, weight `w500`, icon shown.
- `plugin_widget`: `kind="bar-widget"`; a missing/invalid plugin renders an error card.
- `system_widget`: default label `Widget del sistema`; render a real Android AppWidgetHostView after binding.
- `box`: compatibility wrapper for edge-style item collections.

Current native status: only basic `clock`, `text`, `battery`, `apps_grid`, and placeholder `plugin_widget` views are wired. No recursive container/layout renderer, geometry, particle/ticker clocks, system widgets, boxes, or visual unknown-type errors are wired. A standalone QML renderer exists but is not connected to widget nodes.

### 4.3 Canonical initial configuration

The completed rewrite must create this exact initial document when no config exists:

```json
{
  "wallpaper": "#0B0F14",
  "desktops": [
    {
      "name": "Inicio",
      "widgets": [
        {
          "type": "clock",
          "format": "HH:mm",
          "fontSize": 56,
          "color": "#66E0FF",
          "fontWeight": "w300",
          "letterSpacing": 4
        },
        {
          "type": "text",
          "value": "OHM",
          "fontSize": 14,
          "color": "#3A4654",
          "fontWeight": "w600",
          "letterSpacing": 8
        },
        {
          "type": "text",
          "value": "Desliza para cambiar de escritorio · mantén pulsado el fondo",
          "fontSize": 14,
          "color": "#9AA7B4"
        }
      ]
    },
    {
      "name": "Sistema",
      "widgets": [
        {
          "type": "clock",
          "format": "HH:mm:ss",
          "fontSize": 36,
          "color": "#E8F1F8",
          "fontWeight": "w300"
        },
        {
          "type": "battery"
        }
      ]
    }
  ]
}
```

Current native status: **not compatible**. `ConfigStorage.DEFAULT_CONFIG` currently creates one empty `Inicio` desktop and materializes TTFX fields. Replace it only as part of an implementation change, not as a documentation-only change.

## 5. `settings.json`, favorites, runtime widgets, and plugins

### 5.1 Complete `settings.json` fields and defaults

The file may be absent. Effective state is `{}` plus every default below. Additional properties are allowed and must be preserved.

| Field | Type/allowed values | Default | Required semantics |
|---|---|---|---|
| `fontFamily` | string | `Predeterminada` | Global fallback font family |
| `textScale` | number `0.8..1.4` | `1.0` | Global text scale |
| `boxSpacing` | number `0..2` | `1.0` | Edge-box spacing scale |
| `boxRadius` | number `0..28` | `14` | Edge-box corner radius |
| `barRadius` | number `0..28` | `18` | Favorites/command bar radius |
| `language` | `auto`, `default` | `auto` | Existing selector values; locale follows system with Spanish fallback |
| `favoritesBarVisible` | boolean | `true` | Collapse/visibility state; keep a reopen handle when collapsed |
| `favoritesBarPosition` | `top`, `bottom`, `left`, `right` | `top` | Persist nearest edge after drag |
| `favoritesBarMode` | `horizontal`, `vertical`, `grid`, `list`, or null | `null` | Null means horizontal at top/bottom and vertical at left/right |
| `bottomBarVisible` | boolean | `true` | Command/plugin bar state; keep a reopen handle when collapsed |
| `bottomBarPosition` | `top`, `bottom`, `left`, `right` | `bottom` | Persist nearest edge after drag |
| `gestureNavigationEnabled` | boolean | `false` | Enables local fallback only under the conditions in §8 |
| `showTapBoxes` | boolean | `false` | Gesture/debug hit-box visualization |
| `apiServerEnabled` | boolean | `true` | Start local/LAN API when true |
| `apiServerPort` | integer | `8753` | Persist changes; existing runtime does not auto-restart on port change |
| `shellPreferTermux` | boolean | `false` | Prefer Termux, then embedded fallback |
| `quakeTerminal` | boolean | `true` | Enable Quake terminal/gesture |
| `aiBaseUrl` | string | `""` | AI endpoint |
| `aiApiKey` | string | `""` | AI credential; treat as sensitive |
| `aiModel` | string | `""` | AI model identifier |
| `aiSystemPrompt` | string | `""` | AI system instruction |
| `omarchyControlPos` | object | `{ "dx": 8, "dy": 80 }` | Floating control position |
| `omarchyControlPos.dx` | number | `8` | Horizontal offset |
| `omarchyControlPos.dy` | number | `80` | Vertical offset |
| `omarchyPeer` | object or null | `null` | Persisted linked desktop |
| `omarchyPeer.ip` | string | no default; required when object exists | Peer host/IP |
| `omarchyPeer.port` | integer | `8753` | Peer API/link port |
| `omarchyPeer.id` | string | `omarchy-pc` | Peer identifier |

Compatibility notes:

- Persist null for nullable preferences where the existing writer does so; reading must treat missing and null identically for defaulting.
- The current native `PeerConfigEditor.kt` stores `settings.omarchyPeer` inside `widgets_config.json`. This is **not the required contract**. Migrate/read that temporary native shape once if needed, then store `omarchyPeer` at the root of `settings.json`.
- Current native status for `settings.json`: **pending**.

### 5.2 `favorites.json`

Exact shape:

```json
[]
```

It is an ordered JSON array of unique strings. Each identity is exactly:

```text
<package>/<activity>
```

Preserve array order. Entries for uninstalled apps remain on disk but are omitted from rendering. Current native status: parse, normalize, toggle, atomic write, and installed-app resolution are **implemented**; interactive reorder and full bar layout settings are **pending**.

### 5.3 `runtime_widgets.json`

Exact shape is an ordered JSON array, default `[]`. Each entry is either any complete widget node from §4.2 or:

```json
{
  "type": "qml",
  "source": "<QML source>"
}
```

`type` and `source` are required for QML entries. Runtime widgets render as dismissible cards in a scrollable overlay above the desktop layers. They do not belong to a desktop. Dismissal updates `runtime_widgets.json`. Current native status: **pending**. The `/widget` server handler interface exists but is not connected to persistence or UI.

### 5.4 Plugin manifest and filesystem rules

Complete manifest vocabulary:

| Field | Type/rule | Required/default |
|---|---|---|
| `schemaVersion` | integer | Required, exactly `1` |
| `id` | non-empty string | Required; case-insensitive prefix `omarchy.` is reserved and rejected |
| `name` | non-empty string | Required |
| `version` | non-empty string | Required |
| `author` | non-empty string | Required |
| `license` | string | Optional, default `""` |
| `description` | string | Optional, default `""` |
| `kinds` | non-empty array | Required; values: `bar-widget`, `panel`, `overlay`, `menu`, `service`, `bar` |
| `entryPoints` | object of strings | Required |
| `entryPoints.barWidget` | string path | Required when `bar-widget` is declared |
| `entryPoints.panel` | string path | Required when `panel` is declared |
| `entryPoints.overlay` | string path | Required when `overlay` is declared |
| `entryPoints.menu` | string path | Required when `menu` is declared |
| `entryPoints.service` | string path | Required when `service` is declared |
| `entryPoints.bar` | string path | Required when `bar` is declared |
| `barWidget` | object | Optional metadata |
| `barWidget.displayName` | string | Optional |
| `barWidget.category` | string | Optional |
| `barWidget.allowMultiple` | boolean | Optional |
| `barWidget.defaultSection` | string | Optional |

Every declared kind requires its corresponding entry point. Entry paths must be relative, must not contain a `..` segment, must resolve to an existing regular file, and must not use a drive path, absolute path, NUL, or backslash-root form. Plugins containing symlinks must be rejected; the native repository currently checks recursively, which is stricter and acceptable. Unknown manifest properties are tolerated.

Disable moves the matching manifest ID from `plugins/` to `plugins.disabled/`. Enable reverses the move. Delete removes matching copies from both roots. Operations key by `manifest.id`, not directory name.

Current native status: manifest parsing/validation and enable/disable/delete are **implemented as an unwired repository**. `barWidget` metadata is not parsed. Seed installation, live discovery, picker/list UI, marketplace, and QML-surface wiring are **pending**.

## 6. Desktop, widget, bar, and edge-box behavior

### 6.1 Layering and system bars

Required back-to-front logical layer order:

1. Full-screen desktop/wallpaper.
2. Screen-sharing indicator.
3. local navigation fallback.
4. drawer/Quake vertical gesture detectors.
5. desktop indicator.
6. edge boxes and favorites/command bars.
7. drag wireframe/poles.
8. app drawer.
9. debug overlay.
10. runtime widgets.
11. compact TTFX controls.
12. AI button/panel.
13. Quake terminal.
14. floating Omarchy control.

The wallpaper must draw behind status and navigation bars; do not inset the complete desktop. Status bar is transparent. The navigation bar is transparent in system gesture mode and `#0B0F14` in button mode. Top/bottom bars must respect system safe areas. The root must behave as `resizeToAvoidBottomInset=false`.

Current native status: edge-to-edge and transparent bars are implemented, but navigation-button coloring and most overlay layers are pending.

### 6.2 Desktops and grid editing

- Horizontal swipe changes desktop.
- Show the top desktop indicator only when there is more than one desktop.
- Mount/start TTFX only for the active desktop.
- A two-second background long-press opens the desktop radial menu.
- A two-second widget long-press enters edit mode and must not also open the background menu.
- Disable horizontal paging while editing.
- Radial actions: add desktop left, add desktop right, add widget, add bottom edge box, edit widgets, desktop settings, global settings, connect Omarchy, restart app, and delete desktop.
- Never delete the final desktop.
- A new desktop copies the template desktop’s widget list.
- Desktop setting commits persist immediately and reload config.

Grid contract:

- Horizontal grid inset: 16 px per side.
- Top inset: system status inset + 70 px.
- Bottom inset: 72 px.
- Default grid: 14 columns × 10 rows. Any 12×8 comment is obsolete.
- Clamp `x`, `y`, `w`, and `h` into the grid.
- Do not detect or resolve collisions; widgets may overlap.
- Paint order equals widget array order.
- Edit UI shows grid, widget borders, and `Listo`.
- Tap selects; background tap clears selection/exits effective editing.
- Drag gives immediate visual movement and rounds to the nearest cell on release.
- Bottom-right resize is available only for the selected widget; round and clamp by cells.
- `+`/`-` change width by one cell.
- Delete is available only for the selected widget.
- Every commit writes `x`, `y`, `w`, and `h`, then reloads JSON.
- Legacy reorder/`span` callbacks may remain, but grid geometry is authoritative.

Current native status: only clamped desktop-index switching by fling and a basic desktop settings popup are present. The grid, editing, radial menu, add/delete/copy actions, and geometry persistence are pending.

### 6.3 Favorites bar

- Render only when at least one favorite is currently installed.
- Tap launches the exact package/activity.
- Preserve and persist order in `favorites.json`.
- Reorder/long-press uses the edge item behavior.
- Default edge is top.
- Support horizontal, vertical, grid, list, and automatic mode.
- Keep the bar mounted when collapsed and expose a handle to reopen it.
- Default corner radius is 18.
- Free drag moves it to the nearest edge and persists `favoritesBarPosition` on release.

Current native status: installed favorites render in a fixed bottom horizontal bar, up to seven items, with launch and favorite removal. Required top default, arbitrary count, ordering UI, modes, collapse handle, radius setting, and edge drag are pending.

### 6.4 Command/plugin bar

- Default edge is bottom; remain mounted when collapsed.
- Horizontal at top/bottom; vertical at left/right.
- Expanded content: installed bar widgets, search, and storage/plugin/service status.
- Search is case-insensitive over app label/package, plugin name/ID, and marketplace ID/name/category.
- Render no more than eight results plus an additional-result count.
- Enter executes only when exactly one result exists.
- App results expose favorite toggle.
- Plugin surface behavior: overlay opens full-screen; panel/menu opens bottom sheet; bar widget opens preview/panel; preview includes Add-to-desktop.
- Plugin list supports enable/disable, confirmed delete, compatible surface launch, and marketplace access.
- Drag chooses nearest edge and persists `bottomBarPosition`.

Current native status: **pending**.

### 6.5 Edge boxes

- Empty box shows `+`; tap opens content picker.
- A collapsed box always leaves a handle.
- Compact mode renders the selected `compactItem` plus the handle.
- List mode forces icon plus name even if `showTitle=false`.
- App items launch their exact activity.
- Compatibility baseline: `plugin` and `system_widget` edge items are generic representations, not live surfaces. Improving them is allowed only if stored shape and interaction remain compatible.
- Settings include direction, icon+title versus icon-only, color, compact mode/item, add content, and delete box.
- Never delete an item or box by long-press; deletion occurs only from settings.

Current native status: **pending**.

### 6.6 Authoritative edge-box drag semantics

The code-observed Flutter behavior overrides the older AGENTS 1s/3s/5s description:

- Direct box movement starts after pointer down plus 16 px displacement; it does not wait three seconds.
- The source box drops to 25% opacity and a full preview follows the pointer.
- Item long-press accepts at 900 ms.
- Accepted items highlight and reorder live.
- Movement above 8 px resets the stillness timer.
- Long-press drag must continue outside bounds (`postAcceptSlopTolerance` equivalent is infinite).
- Two seconds of stillness measured from long-press start opens box settings. There is no separate five-second phase.
- Outside the source box, the item preview follows the pointer.
- Holding an item inside another box for one second moves and persists it immediately; do not wait for pointer-up.
- A box edge target is valid only within 120 px of that screen edge.
- Insertion compares pointer position with destination box centers.
- Destination boxes animate for 220 ms to open the insertion gap.
- Dropping on another edge updates `edge` and forces vertical direction for left/right or horizontal for top/bottom.
- Dropping without a valid target cancels.
- Edge poles are 90 px gradient bands with an 800 ms pulse; target color comes from the dragged box.

Current native status: **pending**.

## 7. Apps and Android AppWidgets

### 7.1 Installed apps and drawer

- Query activities matching `ACTION_MAIN + CATEGORY_LAUNCHER`.
- Deduplicate by exact `package/activity` and sort ascending by lowercase label.
- Initial app inventory contains `{label, package, activity}` without eagerly loading icons.
- Load icons on demand as PNG and cache by `package/activity`.
- Tap launches the exact component.
- App long-press threshold is two seconds.
- Drawer height is 75% of the screen.
- Drawer grid is fixed at four columns.
- Filter case-insensitively by label or package.
- Enter launches only when exactly one result remains.
- Launch clears search, hides keyboard, and closes drawer.
- Long-press allows add/remove favorite and add to top/bottom/left/right box.
- Scrim tap closes.
- Top handle drags downward to close.
- Pull-down closes when scroll offset is below `-40`.
- External open gesture follows the finger with sensitivity 2.5.
- On release, open when velocity is `< -150` or progress is `> 0.15`; close when velocity is `> 150` or progress is `< 0.85`.

Current native status: activity query/sort/dedup/launch, fixed four-column full-screen drawer, label filter, and favorite toggle are implemented. Lazy PNG cache, 75% sheet, package filter, Enter rule, close/reset behavior, long-press timing, edge-box actions, scrim/handle/overscroll behavior, and continuous gesture thresholds are pending.

### 7.2 Android AppWidgets

Required provider inventory item:

```json
{
  "label": "<provider label>",
  "package": "<provider package>",
  "provider": "<flattened ComponentName>",
  "minWidth": 0,
  "minHeight": 0
}
```

Required binding/hosting behavior:

- AppWidget host ID: `0x0A0B0C`.
- Bind request code in the compatibility bridge: `0xA11CE5`.
- Allocate an ID, attempt direct bind, otherwise launch `AppWidgetManager.ACTION_APPWIDGET_BIND` with `EXTRA_APPWIDGET_ID` and `EXTRA_APPWIDGET_PROVIDER`.
- Compatibility result is either an immediate integer ID or `{id,needsBind:true}`; errors are `bad_provider` and `bind_not_allowed`.
- Host view creation args are `{id:Int}`.
- Unbind deletes the allocated host ID.
- Binding result handling must coexist with MediaProjection consent.
- The rewrite should correct the Flutter defect where disposal always deleted the ID and JSON did not persist it. Persist a stable `appWidgetId` as an additional node property while continuing to read old provider-only nodes.

Current native status: **pending**.

## 8. Gestures and Quake terminal UI

### 8.1 Home gesture routing

- A vertical recognizer yields to horizontal paging when `abs(dx) > abs(dy) + 20`.
- Commit it as vertical after `abs(dy) > 20`.
- Disable drawer and Quake gestures during desktop editing.
- Drawer activation region begins at 45% of screen height and extends through bottom inset + 120 px.
- Quake activation region is the top 45% of screen.
- Quake opens after accumulated downward movement exceeds 60 px.
- Quake occupies 45% of screen height.
- Quake open/close transition is 220 ms.
- Close Quake on shell `exit`, X, bottom-handle tap, or upward vertical delta `< -6`.

Current native status: basic fling paging and drawer fling are partial. Quake gestures/UI are pending.

### 8.2 Navigation fallback and accessibility overlay

Local fallback is active only when all are true:

1. Android navigation mode is not `2`;
2. accessibility gesture service is disabled;
3. `gestureNavigationEnabled=true`.

The observable Flutter fallback only implements Back: inward swipe over 24 px from a 32 px side band. Its bottom Home/Recents UI is only a visual pill and does not implement those gestures. Do not claim Home/Recents local fallback parity until implemented and tested.

The separate accessibility service behavior is:

- Activate overlays only when secure `navigation_mode == 2`.
- Left/right width 24 dp; bottom height 44 dp.
- Remove overlays while `com.miui.home` Recents is focused.
- Reevaluate on screen-on and configuration changes.
- Left inward/right inward invoke Back.
- Left-edge upward invokes Home; right-edge upward invokes Recents.
- Bottom quick upward invokes Home; upward and hold invokes Recents.
- Remote tap duration is 50 ms.
- Remote swipe duration clamps to `50..5000` ms.
- Report synchronous `dispatchGesture()` acceptance.
- Keys map exactly to global BACK, HOME, and RECENTS.

Current native status: the accessibility behavior and remote primitives are implemented. The local fallback and settings gate are pending. The native home does not currently suppress gestures in edit mode because edit mode is absent.

## 9. Shell, binaries, terminal, and local API

### 9.1 Shell execution and private binaries

- Embedded commands run through `/system/bin/sh -c`.
- `ShellResult` fields are exactly `exitCode`, `stdout`, `stderr`, and `via`.
- `via` values are `embedded`, `termux`, or `error`.
- PATH starts with `filesDir/bin`, followed by Android system/vendor paths.
- Treat app-private storage as noexec: shebang files run through `/system/bin/sh`; native executables run through `/system/bin/linker64` or `/system/bin/linker`.
- Allowed binary name regex is `[A-Za-z0-9._-]+`; additionally reject `..`, slash, and backslash.
- Install atomically, list `{name,size,path}`, and return whether uninstall removed an existing file.
- Packaged compatibility tools may include bun, tmux and libraries, dropbearmulti and ssh/dbclient/scp/dropbearkey links, and their support libraries. If shipped, preserve wrapper behavior rather than assuming executable app storage.

Current native status: shell execution, noexec resolution, quoting, binary install/list/remove, and tests are implemented as libraries. They are not initialized or exposed by `MainActivity`.

### 9.2 Termux compatibility

Outgoing broadcast:

- action `com.termux.RUN_COMMAND`
- package `com.termux`
- extras:
  - `com.termux.RUN_COMMAND_COMMAND`
  - `com.termux.RUN_COMMAND_ARGUMENTS`
  - `com.termux.RUN_COMMAND_WORKDIR` = app `filesDir`
  - `com.termux.RUN_COMMAND_RESULT_INTENT_SENDER`

Result broadcast:

- action `cl.villagranquiroz.ohm_launcher.TERMUX_RESULT`
- dynamically registered non-exported receiver
- extras:
  - `req`
  - `com.termux.RUN_COMMAND_RESULT_BROADCAST_EXTRA_STDOUT`
  - `com.termux.RUN_COMMAND_RESULT_BROADCAST_EXTRA_STDERR`
  - `com.termux.RUN_COMMAND_RESULT_BROADCAST_EXTRA_EXIT_CODE`

Current native status: a generic `TermuxRunner` interface exists; actual broadcast integration and installation check are pending.

### 9.3 Quake PTY

- PTY persists while hidden; `/quake` changes visibility only.
- Process is `/system/bin/sh`; initial terminal geometry is 80×24.
- Environment includes `TERM=xterm-256color`, `HOME`, `PATH`, `LD_LIBRARY_PATH`, `TERMINFO`, `TMUX_TMPDIR`, `TMPDIR`, and `ENV=.ohm_bashrc`.
- Generate `.ohm_bashrc` wrappers so private ELF files run through linker and scripts through shell.
- Preserve Esc (`\x1b`), Tab (`\t`), arrows (`\x1b[A`, `\x1b[B`, `\x1b[C`, `\x1b[D`), Ctrl (`character & 0x1F`), and Alt (ESC prefix) controls.
- Preserve selection/copy behavior.

Current native status: **pending**. `QuakeTerminalView.kt` currently implements only an in-memory 100-command history helper.

### 9.4 Local API process contract

Default port is `8753`; server is enabled by default; production binds `0.0.0.0` (LAN mode), not localhost. It has no authentication or TLS and uses open CORS. Binding failure leaves `isRunning=false`. Security hardening may add opt-in authentication and canonical path confinement, but a compatibility mode must preserve request/response shapes.

| Method | Path | Request | Success response | Current native status |
|---|---|---|---|---|
| GET | `/health` | none | `{ok:true,name:"OhmLauncher"}` | Implemented library, not running |
| POST | `/command` | `{command,args?}` | `{exitCode,stdout,stderr,via}` | Implemented library, not wired |
| POST | `/widget` | `{source,format?}`; `format="json"` default | `{ok:true}` | Parser/handler dispatch only; persistence/UI pending |
| POST | `/ai` | `{prompt,history?:[{role,content}]}` | `{text,widgetSource,widgetFormat}` | **Pending** |
| POST | `/install-bin` | `{name,base64}` | `{ok,name,size,path}` | Implemented library, not wired |
| POST | `/install-bin-raw?name=<name>` | raw executable bytes | `{ok,name,size,path}` | Implemented library, not wired |
| GET or POST | `/bins` | none | `{bins:[{name,size,path}]}` | Implemented library, not wired |
| POST | `/uninstall-bin` | `{name}` | `{ok:true,name,removed}` | Implemented library, not wired |
| POST | `/quake` | `{open?:Boolean}`, default true | `{ok:true,open}` | Dispatch exists; terminal pending |

Preserve these compatibility error strings where applicable:

- `missing_command`
- `missing_source`
- `missing_prompt`
- `invalid_name`
- `missing_base64`
- `bad_base64`
- `install_not_supported`
- `install_failed`
- `ai_not_configured`
- `quake_not_supported`
- `method_not_allowed`
- `not_found`
- `server_error`

The current native parser also intentionally returns stricter transport errors such as `bad_request`, `headers_too_large`, `bad_header`, `ambiguous_body_length`, `unsupported_transfer_encoding`, `invalid_content_length`, `payload_too_large`, `invalid_json`, `invalid_clipboard`, and `bad_multipart`; retain these safety improvements.

## 10. Omarchy phone API, WebSocket, discovery, clipboard, screen, and input

### 10.1 Phone-side REST endpoints

| Method | Path | Exact request | Exact success behavior | Current native status |
|---|---|---|---|---|
| GET | `/omarchy/discover` | none | `{name:"OhmLauncher",model:"Android",version:1,lan_ip,port,capabilities:["clipboard","file","files","theme","screen","photos","input"]}` | Models/route only; adapter/server pending |
| GET | `/omarchy/clipboard` | none | `{text}` | Pending adapter |
| PUT | `/omarchy/clipboard` | `{text}` | `{ok:true}` and WS `clipboard_changed` | Validation only; mutation/event pending |
| GET | `/omarchy/theme` | none | `{colors:{...}}` | Pending adapter |
| PUT | `/omarchy/theme` | arbitrary JSON settings map | Persist every top-level key; `{ok:true}` | Pending adapter/settings |
| POST | `/omarchy/file` | multipart filename and bytes | `{ok:true,path,bytes}` stored below `shared/` | Multipart parser exists; adapter pending |
| GET | `/omarchy/file?path=<path>` | none | `application/octet-stream`; absent path gives `missing_path` | Dispatch exists; adapter pending |
| GET | `/omarchy/files?path=<dir>` | defaults to `/sdcard` | `{path,parent,entries:[{name,path,isDir,size,modified}]}` | Default/dispatch exists; adapter pending |
| POST | `/omarchy/input` | structure in §10.3 | input result map | Route/parser models exist; service wiring pending |
| POST | `/omarchy/screen/start` | empty | `{status:"started"|"denied"}` and WS `screen_started` | Capture controller exists; endpoint wiring pending |
| POST | `/omarchy/screen/stop` | empty | `{ok:true}` and WS `screen_stopped` | Capture controller exists; endpoint wiring pending |
| POST | `/omarchy/photos/backup` | empty | `{status:"ok",count,photos:[{path,name,size,modified}]}` | Pending adapter |
| WS | `/omarchy/ws` | HTTP upgrade | event/control channel | Pending |

Unsupported configured capability:

```json
{"error":"unsupported","capability":"<name>"}
```

Unknown Omarchy route:

```json
{"error":"not_found","path":"<path>"}
```

File list fields are all mandatory in responses: `name` string, absolute `path` string, `isDir` boolean, `size` integer bytes (zero for directories), and `modified` epoch milliseconds. Sort directories before files, then case-insensitively by name. Nominal roots are `/sdcard` and `/storage/emulated/0`. Canonicalize before root checks; do not reproduce lexical `..` traversal defects. Photo scan extensions are `jpg`, `jpeg`, `png`, `heic`, `webp`, `mp4`, and `mov`; deduplicate overlapping roots.

### 10.2 WebSocket contract

Immediately after connection, send every field below:

```json
{
  "type": "peer_hello",
  "name": "OhmLauncher",
  "model": "Android",
  "version": 1,
  "lan_ip": "<phone LAN IP>",
  "port": 8753,
  "capabilities": ["clipboard", "file", "files", "theme", "screen", "photos", "input"]
}
```

Inbound:

- `{type:"ping"}` → `{type:"pong"}`.
- `{type:"input", ...}` → `{type:"input_result", ...}`.
- `{type:"screen_start"}` starts screen sharing through the same consent-aware controller as REST.
- `{type:"screen_stop"}` stops it.

Broadcast events:

- `{type:"clipboard_changed",text}`
- `{type:"screen_started"}`
- `{type:"screen_stopped"}`

Actual compatibility screen frames are sent phone-to-PC with HTTP `POST /omarchy/screen/frame?w=<width>&h=<height>` and a raw JPEG body. Do not rely on the unused Flutter JSON `screen_frame` helper. Current native status: all phone WebSocket behavior is **pending**; HTTP frame upload is implemented and called by the capture controller when a peer exists.

### 10.3 Remote input structures

Accepted REST or WebSocket input payloads:

```json
{"action":"tap","x":540,"y":1200}
{"action":"swipe","x1":1,"y1":2,"x2":3,"y2":4,"durationMs":300}
{"action":"key","key":"back"}
```

Allowed keys are exactly `back`, `home`, and `recents`. Swipe duration defaults to 300 ms and is clamped by the accessibility service to `50..5000` ms. Unknown action response:

```json
{"ok":false,"error":"unknown_action","action":"..."}
```

If accessibility is unavailable, tap/swipe return `{ok:false,error:"accessibility_disabled"}`. Key result is `{ok:Boolean}`. Current native status: validated input models and accessibility primitives exist; REST/WS adapter wiring is pending.

### 10.4 Discovery and connection state

- Register mDNS service type `_ohm._tcp`, name `OhmLauncher`, configured API port, TXT `ohm=1` and `port=<port>`.
- Advertise only after the API socket has successfully bound; unregister when it stops.
- Phone QR is `ohm://<phone-lan-ip>:<port>`.
- Accepted PC QR/deep link is `omarchy://<pc-lan-ip>:<link-port>?id=<id>`.
- PC host and explicit nonzero port are required; missing `id` defaults to host.
- Process deep links on cold start and `onNewIntent`; buffer until the UI/control layer is ready.
- Persist `{ip,port,id}` as `omarchyPeer` in `settings.json`.
- Any incoming Omarchy REST/WS request calls peer-seen with remote IP and default peer port 8753; do not overwrite an existing peer.
- On connect, start clipboard sync and probe every 15 seconds using `GET http://<pc>/omarchy/link`.
- A valid `{connected:false}` clears the peer; network failure retains it.
- Notify a scanned peer using `POST /omarchy/link` with `{ip:<phone LAN IP>,port:<phone API port>,name:<scanned id>}` and explicit `Content-Length`.
- Disconnect sends `POST /omarchy/link/bye`.
- BLE compatibility may list named candidates, but the existing Flutter BLE path does not establish a connection or derive an IP. Replace the invalid historical `0000ohm0-...` UUID with a valid UUID before claiming BLE transport support.

Current native status: strict deep-link parsing, cold/new intent processing, peer state, fixed-length `/omarchy/link` notification, and clipboard-service startup exist. Persistence uses the wrong file, existing peer replacement is currently forced, probe scheduling/clear rules, bye, peer-seen, mDNS, QR presentation, and BLE are pending.

### 10.5 Clipboard behavior

- Foreground service is non-exported, type `dataSync`, and `START_STICKY`.
- Start extras are `peerIp:String` and `peerPort:Int` default 8753.
- Ignore duplicate primary clipboard text.
- Send `PUT http://<peerIp>:<peerPort>/omarchy/clipboard` with JSON `{text}`.
- Notification channel `ohm_clipboard`, notification ID `9001`, low importance, ongoing.
- Stop via `stopService`; also recognize start extra `stop=true`.
- Incoming REST clipboard writes must update Android clipboard and broadcast `clipboard_changed` without creating an endless echo loop.

Current native status: outgoing foreground monitor is implemented and started on deep-link connection. Incoming clipboard/API/WS behavior and explicit disconnect stop are pending.

### 10.6 Screen capture behavior

- Foreground service is non-exported, type `mediaProjection`, `START_STICKY`.
- Enter foreground before `getMediaProjection()` on Android 14+.
- Start uses explicit service component; stop extra is `stop=true`.
- Notification channel `ohm_screen`, ID `9002`, low importance, ongoing.
- A latch may synchronize startup; timeout is three seconds.
- Capture full physical display, `RGBA_8888`, `ImageReader` buffer count 2, virtual display `ohm-screen`, auto-mirror.
- Encode JPEG quality 60 and use latest-image acquisition on a worker thread.
- Preserve width/height and crop row-padding before encoding.
- Require user MediaProjection consent. Do not report `started` until consent and capture startup actually succeed; denial must return `denied` and clear sharing state.
- Upload frames to peer `POST /omarchy/screen/frame?w=&h=` with raw JPEG and fixed `Content-Length`.

Current native status: capture, foreground synchronization, crop, JPEG 60, 100 ms frame throttle, and HTTP upload are implemented. REST/WS start/stop and a visible sharing indicator/control are pending.

### 10.7 PC-side Omarchy Link contract

The external plugin remains identity `cl.villagranquiroz.omarchy-link`, entry `BarWidget.qml`, `allowMultiple:false`, default section `right`. Its Python server binds `0.0.0.0:8753`, with `OMARCHY_LINK_PORT` and `OMARCHY_LINK_PEER=ip:port` overrides.

| Method | Path | Contract |
|---|---|---|
| POST | `/omarchy/link` | `{ip,port?,name}` → persisted connected state |
| GET | `/omarchy/link` | `{connected,peerIp,peerPort,peerName,linkPort}` |
| POST | `/omarchy/link/bye` | mark disconnected |
| PUT | `/omarchy/clipboard` | `{text}` → `wl-copy`, then `xclip` fallback |
| GET | `/omarchy/clipboard` | `wl-paste`, then `xclip` fallback |
| POST | `/omarchy/screen/frame?w=&h=` | raw JPEG/PNG → `{ok,bytes}` |
| GET | `/omarchy/screen/status` | `{frames,last,w,h}` |

External plugin temporary files are `/tmp/omarchy-link-state.json`, `/tmp/omarchy-screen-state.json`, `/tmp/omarchy-screen.jpg` or `.png`, and `/tmp/ls.log`. Preserve its reentrant lock; a non-reentrant lock deadlocks on every-thirtieth-frame logging. Its server accepts chunked requests defensively, although phone requests set `Content-Length`.

## 11. Legacy Android bridge contract

If migration retains a Flutter shell, integration tests, or any external code that calls the old bridge, preserve the following exactly. A fully native UI may implement the same operations directly, but behavior and DTOs remain useful acceptance criteria.

### 11.1 `com.ohm/ohm` methods

| Method | Arguments | Result/effect |
|---|---|---|
| `getInstalledApps` | none | sorted list of `{label,package,activity}` |
| `getInstalledAppWidgets` | none | list of `{label,package,provider,minWidth,minHeight}` |
| `getAppIcon` | `{package,activity}` | PNG bytes; empty bytes on failure |
| `getBatteryLevel` | none | integer `0..100`, or `-1` |
| `getNativeAbi` | none | first `Build.SUPPORTED_ABIS`, or `""` |
| `isDefaultLauncher` | none | boolean |
| `getNavigationMode` | none | integer; expected `0=buttons`, `2=gestures` |
| `requestDefaultLauncher` | none | first resolvable HOME-role/settings intent |
| `openAppSettings` | none | app details, fallback general settings |
| `openNavigationSettings` | none | OEM/AOSP navigation settings, fallback general settings |
| `restoreGestureNavigation` | none | boolean; Xiaomi secure/global setting writes |
| `openAccessibilitySettings` | none | opens accessibility settings |
| `isGestureAccessibilityEnabled` | none | exact flattened service component enabled |
| `openRecents` | none | status-bar reflection, then `input KEYCODE_APP_SWITCH` fallback |
| `restartApp` | none | recreate activity |
| `startClipboardMonitor` | `{ip:String,port:Int=8753}` | start clipboard FGS |
| `stopClipboardMonitor` | none | stop clipboard FGS |
| `injectTap` | `{x:Double,y:Double}` | `{ok}` or `{ok:false,error:"accessibility_disabled"}` |
| `injectSwipe` | `{x1,y1,x2,y2:Double,durationMs:Int=300}` | same shape |
| `injectKey` | `{key:"back"|"home"|"recents"}` | `{ok:Boolean}` |
| `setImmersiveMode` | `{enabled:Boolean=true}` | hide/restore navigation bar |
| `launchApp` | `{package,activity}` | boolean |
| `bindAppWidget` | `{provider:<flattened ComponentName>}` | integer ID or `{id,needsBind:true}` |
| `unbindAppWidget` | `{id:Int}` | delete host ID; null result |
| `runInTermux` | `{command:String,args:[String]}` | async `{stdout,stderr,exitCode}` |
| `isTermuxApiInstalled` | none | package `com.termux.api` present |

Native callbacks: `widgetBound` with `{id,provider}`, `widgetBindFailed` with `{id,provider}`, and `onOmarchyPeerLink` with `{uri}`.

### 11.2 Other bridge surfaces

- `com.ohm/audio_spectrum` emits `{volume:Double,beat:Boolean,bands:[16 Double values]}`; emit zeros when unavailable.
- `ohm/screen` accepts `startCapture`/`stopCapture` and emits `onFrame` with JPEG bytes.
- `com.ohm/appwidget` creation args are `{id:Int}` and create an `AppWidgetHostView`.

Current native status: these channels are absent because the app is a native Activity. Equivalent direct app functionality remains partial as described above.

## 12. Android manifest, permissions, and components

### 12.1 Activity contract

`MainActivity` must remain exported, `launchMode="singleTask"`, and expose:

1. `MAIN + HOME + DEFAULT`;
2. `MAIN + LAUNCHER`;
3. `VIEW + DEFAULT + BROWSABLE` for `omarchy://`.

Application compatibility attributes:

- `android:usesCleartextTraffic="true"`
- `android:requestLegacyExternalStorage="true"`

Current native status: **implemented**.

### 12.2 Permissions

Required/current permission inventory:

| Permission | Purpose | Current native status |
|---|---|---|
| `android.permission.INTERNET` | API, Omarchy peer, marketplace | Declared |
| `android.permission.QUERY_ALL_PACKAGES` | launcher/app/provider discovery | Declared |
| `android.permission.READ_EXTERNAL_STORAGE` (`maxSdkVersion=32`) | legacy/public reads | Declared |
| `android.permission.WRITE_EXTERNAL_STORAGE` (`maxSdkVersion=29`) | legacy public writes | Declared |
| `android.permission.MANAGE_EXTERNAL_STORAGE` | preferred public launcher root | Declared; settings request implemented |
| `android.permission.RECORD_AUDIO` | global Visualizer/audio-reactive TTFX | Declared; runtime request pending |
| `android.permission.MODIFY_AUDIO_SETTINGS` | Visualizer/output capture | Declared |
| `android.permission.FOREGROUND_SERVICE` | foreground sync/capture services | Declared |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | clipboard FGS | Declared |
| `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION` | screen FGS | Declared |
| `android.permission.POST_NOTIFICATIONS` | foreground notifications | Declared; runtime request pending |
| `android.permission.SYSTEM_ALERT_WINDOW` | legacy overlay compatibility | Declared; accessibility overlays use accessibility window type |
| `android.permission.WRITE_SECURE_SETTINGS` | navigation restoration on privileged/dev installs | Declared; restoration operation pending |
| `android.permission.BIND_ACCESSIBILITY_SERVICE` | service protection attribute, not a normal runtime permission | Applied to service |

Package visibility queries must include:

- `ACTION_MAIN + CATEGORY_LAUNCHER` — **implemented**.
- `ACTION_PROCESS_TEXT` with MIME `text/plain` — **pending/missing from manifest**.

### 12.3 Components

- `.MainActivity`: exported, singleTask, HOME/LAUNCHER/Omarchy deep link — **implemented**.
- `.ClipboardMonitorService`: non-exported, `foregroundServiceType="dataSync"` — **implemented**.
- `.ScreenCaptureService`: non-exported, `foregroundServiceType="mediaProjection"` — **implemented**.
- `.OhmGestureAccessibilityService`: exported, protected by `BIND_ACCESSIBILITY_SERVICE` — **implemented**.
- Accessibility XML must retain `typeWindowsChanged|typeWindowStateChanged`, `feedbackGeneric`, `flagDefault|flagRetrieveInteractiveWindows`, `canRetrieveWindowContent=true`, and especially `canPerformGestures=true` — **implemented**.

The current manifest declares `foregroundServiceType="specialUse"` on the accessibility service without the corresponding special-use permission/property, while the service is not a normal foreground service. Remove or fully justify/configure that declaration before release; removing the incorrect FGS type does not alter the accessibility contract.

## 13. Exact native source responsibility map

These are the current native source files and their migration responsibilities:

| Native file | Responsibility/status |
|---|---|
| `app/src/main/AndroidManifest.xml` | Identity, permissions, components, intents; partial contract |
| `app/build.gradle.kts` | Namespace/application ID, SDK/version/build dependencies |
| `app/src/main/kotlin/cl/villagranquiroz/ohm_launcher/MainActivity.kt` | Root wiring, storage, watcher, apps, deep links, peer, MediaProjection; partial |
| `NativeLauncherView.kt` | Current desktop/drawer/favorites UI; partial, must expand or be decomposed |
| `LauncherConfig.kt` | Current partial config/TTFX parser |
| `ConfigStorage.kt` | Root migration, config/favorites persistence; partial |
| `DesktopConfigEditor.kt` | Desktop TTFX JSON updates |
| `FavoritesConfigEditor.kt` | Favorite parse/toggle/order/resolve |
| `PeerConfigEditor.kt` | Temporary peer persistence helper; must target `settings.json` |
| `AppCatalog.kt` | Launchable app query/normalize/launch |
| `PluginContract.kt` | Plugin constants/model |
| `PluginRepository.kt` | Plugin discovery/validation/enable/disable/delete; unwired |
| `qml/QmlTokenizer.kt` | QML tokenization |
| `qml/QmlAst.kt` | QML AST |
| `qml/QmlParser.kt` | QML parsing |
| `qml/QmlRuntime.kt` | QML expression/property evaluation |
| `qml/QmlRenderModel.kt` | QML-to-render-model conversion |
| `qml/QmlViewRenderer.kt` | QML render model to Android Views; unwired |
| `NativeTtfxView.kt` | TTFX process lifecycle and raster rendering |
| `TtfxFrameParser.kt` | Length/framed TTFX output parsing |
| `TtfxSettingsDialog.kt` | Current desktop TTFX settings UI |
| `AudioSpectrum.kt` | Visualizer FFT and 16-band normalized spectrum |
| `OmarchyWordmark.kt` | Official Omarchy bitmap rendering/scaling |
| `ShellExecutor.kt` | Embedded/optional Termux shell execution library |
| `BinStore.kt` | Private binary install/list/remove library |
| `QuakeTerminalView.kt` | Command-history helper only; PTY UI pending |
| `LocalApiServer.kt` | HTTP parser/routes/limits; not started by app |
| `OmarchyProtocol.kt` | REST routes and DTOs |
| `OmarchyApiAdapter.kt` | Adapter interface only; concrete app adapter pending |
| `OmarchyInputEvent.kt` | Validated input payload model |
| `OmarchyPathConfinement.kt` | Canonical file safety helper |
| `OmarchyPeer.kt` | URI/parser/state |
| `OmarchyPeerClient.kt` | `/omarchy/link` notify/probe and frame upload |
| `ClipboardMonitorService.kt` | Outgoing clipboard foreground sync |
| `ScreenCaptureController.kt` | MediaProjection capture and peer frame callback |
| `ScreenCaptureService.kt` | Required mediaProjection foreground state |
| `OhmGestureAccessibilityService.kt` | Global gestures and remote input primitives |
| `app/src/main/res/xml/accessibility_service_config.xml` | Accessibility capabilities |
| `app/src/main/assets/bin/ttfx-aarch64` | ARM64 TTFX binary |
| `app/src/main/assets/bin/ttfx-x86_64` | x86-64 TTFX binary |
| `app/src/main/assets/terminfo/x/xterm-256color` | Terminal definition asset |

Recommended missing modules may be introduced without changing this contract: typed settings/runtime repositories, desktop grid/editor, edge drag coordinator, AppWidget host, plugin/marketplace UI, PTY terminal, concrete API adapter, WebSocket hub, discovery manager, and permission coordinator.

## 14. Defects to fix without changing compatibility

The native rewrite must not intentionally preserve these defects:

1. Unauthenticated LAN shell/binary/file/input access is dangerous. Add a pairing/authentication mode, but preserve an explicit compatibility mode and all payload schemas.
2. Canonicalize and confine uploaded/downloaded/listed file paths; reject traversal filenames. The current native HTTP parser/path helper already improves this area.
3. Deduplicate photo results from overlapping roots.
4. Do not report screen capture started before consent/start succeeds.
5. Wire WebSocket `screen_start` to MediaProjection; do not create a dead frame loop.
6. Advertise mDNS only after successful API bind.
7. Replace the invalid historical BLE UUID before implementing BLE.
8. Do not delete AppWidget IDs merely because a view is temporarily disposed; persist stable IDs.
9. Do not store `omarchyPeer` inside `widgets_config.json`; migrate it to `settings.json`.
10. Watch enabled and disabled plugin directories and keep all plugin consumers on one observable repository; do not recreate mutable snapshot desynchronization.
11. Resolve the invalid accessibility `specialUse` foreground-service declaration.
12. Treat the code-observed 14×10 grid, 900 ms item long-press/direct box pan, and Back-only local fallback as the compatibility baseline, not obsolete comments.

## 15. Verification checklist

A migration milestone is not complete until applicable items below are exercised, not merely compiled.

### Identity, build, and manifest

- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] `./gradlew assembleDebug` passes and produces an installable APK.
- [ ] Installed package is exactly `cl.villagranquiroz.ohm_launcher` and label is `Ohm Launcher`.
- [ ] App appears as both HOME candidate and normal launcher icon.
- [ ] `omarchy://host:port?id=value` routes to the existing singleTask instance on cold and warm starts.
- [ ] Invalid/missing host or explicit port is rejected without crash.
- [ ] Manifest contains both launcher and `ACTION_PROCESS_TEXT text/plain` visibility queries.

### Storage and JSON

- [ ] Legacy root migrates only when destination is absent.
- [ ] Public, app-external, and private-documents fallback paths are separately tested.
- [ ] Canonical initial config is byte-semantically equivalent to §4.3.
- [ ] Seed plugin is present after first boot.
- [ ] All five persistence areas (`widgets_config.json`, `settings.json`, `favorites.json`, `runtime_widgets.json`, plugin roots) survive process restart and `adb install -r` where platform storage semantics permit.
- [ ] Atomic write interruption leaves either old or new valid JSON, not truncation.
- [ ] Extra root/desktop/node/settings/manifest fields survive UI edits.
- [ ] Corrupt JSON displays a visible error card and launcher stays usable.
- [ ] Config/plugin changes reload after approximately 400 ms without restart.
- [ ] Every field/default in §§4–5 has a parser test, including null settings and all padding/color forms.

### Desktop and widgets

- [ ] Default 14×10 grid and stated insets match on device.
- [ ] Every widget type renders; unknown type and missing plugin produce error cards.
- [ ] Recursive layouts, text aliases, all clock styles, battery, apps grid, plugins, AppWidgets, and boxes are covered.
- [ ] Geometry clamps, overlap remains allowed, and paint order follows JSON.
- [ ] Edit move/resize/width/delete writes `x/y/w/h` and survives reload.
- [ ] Last desktop cannot be deleted; copied desktop duplicates template widgets.
- [ ] Wallpaper fills behind transparent system bars; content bars respect safe areas.
- [ ] Only active desktop owns a running TTFX/audio renderer.

### Bars, boxes, apps, and gestures

- [ ] Favorites preserve exact `package/activity` order and hide missing apps without deleting entries.
- [ ] Favorites and command bars support every edge/mode and retain a reopen handle when collapsed.
- [ ] Command search covers every specified field, caps at eight, reports extras, and Enter requires one result.
- [ ] Edge boxes implement every field/default and never delete on long-press.
- [ ] Timed gesture tests cover 900 ms item accept, 8 px stillness reset, 2 s settings, 1 s cross-box commit, 16 px box pan, 120 px edge target, 220 ms gap, and invalid-drop cancel.
- [ ] App query/sort/dedup, lazy icon loading, component launch, label/package search, Enter behavior, and four-column drawer pass.
- [ ] Drawer finger-follow/velocity/progress/scrim/handle/overscroll behaviors pass on device.
- [ ] Horizontal pager wins according to axis-lock rules; editing disables pager/drawer/Quake.
- [ ] Accessibility overlays do not obstruct MIUI Recents and Back/Home/Recents work from all defined edges.
- [ ] Local fallback is active only under the three required conditions and does not falsely claim bottom Home/Recents.

### AppWidgets, plugins, and QML

- [ ] Provider listing returns every exact field.
- [ ] Direct and consent-required binding both work; cancel releases only the newly allocated ID.
- [ ] Stable AppWidget IDs survive rerender, desktop switch, process restart, and configuration change.
- [ ] Plugin validator tests every required field, kind/entry mapping, path escape, missing file, reserved ID, unknown kind, and symlink.
- [ ] Enable/disable/delete work by manifest ID and trigger UI reload.
- [ ] Seed and real QML fixtures render through the same repository path used by desktop, preview, and command bar.
- [ ] Plugin overlay/panel/menu/bar-widget surfaces and Add action behave as specified.

### Shell, bins, API, and terminal

- [ ] Native server actually starts on configured port and `0.0.0.0` only when enabled.
- [ ] Every endpoint/error in §9.4 has an integration test over a real socket.
- [ ] `/ai` covers configured and `ai_not_configured` paths.
- [ ] Binary name/traversal checks, base64/raw install, list, uninstall, script execution, and ELF linker execution pass.
- [ ] Termux broadcast round-trip and timeout fallback pass when Termux API is present/absent.
- [ ] Quake PTY remains alive while hidden, starts 80×24 shell, exports all required variables, and handles every special key sequence.
- [ ] `/quake` toggles visibility without killing PTY.

### Omarchy REST, WS, discovery, clipboard, screen, and input

- [ ] Every REST route in §10.1 is tested over a running server with exact response fields/errors.
- [ ] File and photo paths are canonicalized, confined, sorted, and deduplicated.
- [ ] WebSocket sends exact hello, ping/pong, input result, clipboard events, and screen events.
- [ ] mDNS publishes exact type/name/TXT only after HTTP bind and disappears on stop.
- [ ] Phone QR and strict PC deep-link round trip with IPv4/IPv6 where supported.
- [ ] Peer persists in `settings.json`, is not overwritten by incidental requests, probes every 15 seconds, clears only on valid disconnected response, and sends bye.
- [ ] Clipboard foreground service starts/stops, suppresses duplicates/echo, and syncs both directions.
- [ ] Screen start requires consent; denial remains stopped; successful capture uploads cropped JPEG quality 60 frames with correct dimensions.
- [ ] Screen REST and WS controls share one state machine and emit accurate events.
- [ ] Tap, swipe duration clamp, Back, Home, Recents, unknown action, and accessibility-disabled error all pass through REST and WS.
- [ ] PC plugin receives link notification, clipboard, and screen frames; screen status increments without the every-thirtieth-frame deadlock.

### Device-level release gate

- [ ] Test on at least one AOSP-like device/emulator and the target Xiaomi/HyperOS device.
- [ ] Verify runtime requests for storage, microphone, notifications, accessibility, default launcher, and MediaProjection.
- [ ] Verify three-button and gesture navigation bar appearance and all edge gestures.
- [ ] Run process-death, rotation/configuration, reboot, network-loss, peer-restart, malformed JSON, corrupt plugin, and occupied-port scenarios.
- [ ] Capture screenshots/video and logs proving desktop layers, bars, drawer, edit mode, Quake, plugins, AppWidget, Omarchy control, and screen-sharing indicator.
