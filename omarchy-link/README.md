# Omarchy Link — OhmLauncher connection plugin

A normal [Omarchy](https://omarchy.org) plugin (Quickshell / QtQuick QML) that
manages the link with **OhmLauncher** (Android): discovers the peer on the LAN
(mDNS / QR / Bluetooth), shares files, syncs clipboard and themes, backs up
photos, and shares the screen between the phone and the desktop.

This is a **standalone plugin repository**. The Android side (the launcher that
exposes the contract) lives at https://github.com/avillagran/ohm-launcher.

---

## Install in Omarchy

Install it like any other Omarchy plugin — clone it into your Quickshell
plugins directory and enable it from the Omarchy plugin list:

```bash
git clone https://github.com/avillagran/omarchy-link \
      ~/.config/quickshell/plugins/cl.villagranquiroz.omarchy-link
```

(The folder name `cl.villagranquiroz.omarchy-link` matches the plugin `id` in
`manifest.json`.) After enabling `cl.villagranquiroz.omarchy-link`, the bar
widget appears in the Omarchy bar and the panel opens from the widget button.

---

## Plugin structure (Omarchy convention)

```
manifest.json      # id, kinds: ["bar-widget"], entryPoints.barWidget
BarWidget.qml      # manifest entry point (root type `BarWidget`)
Panel.qml          # loaded internally via Loader (root type `Panel`)
README.md          # this file
TESTING.md         # end-to-end verification guide
examples/minimal/  # minimal BarWidget+Panel starter
```

### manifest.json (canonical)
```json
{
  "schemaVersion": 1,
  "id": "cl.villagranquiroz.omarchy-link",
  "name": "Omarchy Link",
  "version": "1.0.0",
  "author": "Ohm Launcher",
  "license": "MIT",
  "kinds": ["bar-widget"],
  "entryPoints": { "barWidget": "BarWidget.qml" }
}
```
> Note: the panel is **not** a separate manifest kind. Omarchy loads
> `Panel.qml` internally from `BarWidget.qml` via a `Loader`, exactly like the
> official clock example. Do **not** use `kinds: ["panel"]` here.

### BarWidget.qml (entry point)
Root type is `BarWidget` (from `qs.Ui`). It must expose:
- `open() / close() / toggle() / closeForPopoutSwitch()`
- `readonly property bool opened`
- `readonly property bool popoutSwitchClosing`
- `onBarChanged: injectPanel()` and an internal `Loader { source: "Panel.qml" }`

### Panel.qml (loaded internally)
Root type is `Panel` with `manageIpc: false`. It receives `bar`, `anchorItem`,
`hostWidget` from the BarWidget and wraps content in a `KeyboardPanel` +
`PanelKeyCatcher` (from `qs.Commons` / `qs.Ui`). Example skeleton:

```qml
import QtQuick
import Quickshell
import qs.Commons
import qs.Ui

Panel {
  id: root
  moduleName: "cl.villagranquiroz.omarchy-link"
  manageIpc: false
  property var anchorItem: null
  property var hostWidget: null
  function open() { controller.show() }
  function close() { controller.hide() }
  KeyboardPanel {
    id: panel
    anchorItem: root.anchorItem
    owner: root.hostWidget || root
    bar: root.bar
    open: root.opened
    focusTarget: keyCatcher
    PanelKeyCatcher {
      id: keyCatcher
      anchors.fill: parent
      onCloseRequested: root.close()
    }
    // ... your UI ...
  }
}
```

### Required imports
- `BarWidget`, `WidgetButton`, `Panel`, `KeyboardPanel`: `import qs.Ui`
- `PanelKeyCatcher`: `import qs.Commons`
- `SystemClock`, `Style`: `import Quickshell` (+ `qs.Ui` for `Style`)
- `Scope`/`PanelWindow` are **Quickshell-core** types — Omarchy plugins use the
  `BarWidget`/`Panel` wrappers instead. Use those wrappers.

---

## Two connection directions

### A) Phone is the server (default)
The phone runs an HTTP+WS server on port `8753` (LAN mode). This plugin:
- calls `setPeer("ohm://<phone-ip>:8753")` then `connect()` (discovers via
  `GET /omarchy/discover`), or
- (optional) auto-discovers via mDNS `_ohm._tcp` using a helper (README below).

### B) PC is the server (this plugin shows a QR)
The panel displays a QR `omarchy://<pc-ip>:8753?id=omarchy-pc`.
**Scan it with the phone's camera** (the normal system camera app). Android
routes the `omarchy://` intent to OhmLauncher, which then connects to this PC.

To *receive* the phone's calls, this plugin must expose the **same contract**
over HTTP+WS. Pure QML has no `HttpServer`; use a small helper:
- a C++ `QHttpServer` (Qt 6) exposing `/omarchy/*`, or
- a Python/Node sidecar process, or
- `QtWebSockets` for the event channel (`/omarchy/ws`).

---

## Contract (baseUrl = http://<ip>:<port>)

| Method | Path | Notes |
|--------|------|-------|
| GET  | `/omarchy/discover`    | `{name, model, lan_ip, port, capabilities}` |
| GET  | `/omarchy/clipboard`   | `{text}` |
| PUT  | `/omarchy/clipboard`   | body `{text}` |
| GET  | `/omarchy/theme`       | `{colors:{...}}` |
| PUT  | `/omarchy/theme`       | applies colors |
| POST | `/omarchy/file` (multipart) | receives a file |
| GET  | `/omarchy/file?path=`  | downloads a file |
| POST | `/omarchy/screen/start` \| `/stop` | starts/stops screen share |
| POST | `/omarchy/photos/backup` | lists DCIM photos (peer downloads via `/file`) |
| WS   | `/omarchy/ws` | events: `peer_hello`, `clipboard_changed` |

Screen frames (after `/omarchy/screen/start`) arrive over WS as
`{ "type": "screen_frame", "data": "<base64 JPEG>" }`.

---

## QR image provider (C++ side, Quickshell)

Quickshell/Omarchy has no built-in QR painter. Register a
`QQuickImageProvider` named **`qrcode`** that renders the data string, then use:

```qml
Image { source: "image://qrcode/omarchy://192.168.1.50:8753?id=omarchy-pc" }
```

Minimal C++ sketch (Qt 6):

```cpp
#include <QQuickImageProvider>
#include <QImage>
// build a QImage from the data using any QR lib (e.g. qrcodegen), then:
class QrCodeProvider : public QQuickImageProvider {
public:
  QrCodeProvider() : QQuickImageProvider(Image) {}
  QImage requestImage(const QString &id, QSize *, const QSize &) override {
    return renderQr(id.toUtf8()); // id is the string after "image://qrcode/"
  }
};
// in Quickshell init:
engine->addImageProvider("qrcode", new QrCodeProvider);
```

---

## Testing

See [TESTING.md](./TESTING.md) for the full end-to-end verification guide
(smoke test, phone round-trip, and curl checks against the contract).

## Minimal starter

See [examples/minimal](./examples/minimal) for the smallest valid
`BarWidget.qml` + `Panel.qml` you can copy to start a new Omarchy plugin.
