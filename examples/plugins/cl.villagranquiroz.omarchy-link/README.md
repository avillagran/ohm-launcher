# Omarchy Link — OhmLauncher connection plugin

Manages the link between **OhmLauncher** (Android) and **Omarchy** (Linux desktop),
so you can share files, sync clipboard/themes, back up photos, and share the
screen between the phone and the desktop.

## Two connection directions

### A) Phone is the server (default)
The phone runs an HTTP+WS server on port `8753` (mode LAN). This panel:
- scans mDNS `_ohm._tcp` to auto-discover the phone, or
- accepts the phone's QR `ohm://<phone-ip>:8753` pasted manually.

### B) PC is the server (this plugin shows a QR)
This panel displays a QR `omarchy://<pc-ip>:8753?id=<host>`.
**Scan it with the phone's camera** (the normal system camera app). Android
routes the `omarchy://` intent to OhmLauncher, which then connects to this PC.

To *receive* the phone's calls, this plugin must expose the **same contract**
over HTTP+WS. Pure QML has no `HttpServer`, so use a small helper:
- a C++ `QHttpServer` (Qt 6) exposing `/omarchy/*`, or
- a Python/Node sidecar process, or
- `QtWebSockets` for the event channel (`/omarchy/ws`).

## Contract (baseUrl = http://<ip>:<port>)

| Method | Path | Notes |
|--------|------|-------|
| GET  | `/omarchy/discover`   | `{name, model, lan_ip, port, capabilities}` |
| GET  | `/omarchy/clipboard`  | `{text}` |
| PUT  | `/omarchy/clipboard`  | body `{text}` |
| GET  | `/omarchy/theme`      | `{colors:{...}}` |
| PUT  | `/omarchy/theme`      | applies colors |
| POST | POST `/omarchy/file` (multipart) | receives a file |
| GET  | `/omarchy/file?path=` | downloads a file |
| POST | `/omarchy/screen/start` \| `/stop` | starts/stops screen share |
| POST | `/omarchy/photos/backup` | lists DCIM photos (peer downloads via `/file`) |
| WS   | `/omarchy/ws` | events: `peer_hello`, `clipboard_changed` |

## QR image provider (C++ side, Quickshell)

Quickshell has no built-in QR painter. Register a `QQuickImageProvider`
named **`qrcode`** that renders the data string, then use:

```qml
Image { source: "image://qrcode/omarchy://192.168.1.50:8753?id=omarchy-pc" }
```

Minimal C++ sketch (Qt 6):

```cpp
#include <QQuickImageProvider>
#include <QPainter>
// use any QR lib (e.g. qrcodegen) to build a QImage, then:
class QrCodeProvider : public QQuickImageProvider {
public:
    QrCodeProvider() : QQuickImageProvider(Image) {}
    QImage requestImage(const QString &id, QSize *, const QSize &) override {
        // id is the data string after "image://qrcode/"
        return renderQr(id.toUtf8()); // -> QImage
    }
};
// in main/quickshell init:
engine->addImageProvider("qrcode", new QrCodeProvider);
```

## Install in Omarchy
Copy this folder to your Omarchy plugins directory (e.g.
`~/.config/quickshell/plugins/cl.villagranquiroz.omarchy-link/`) or your marketplace
path, then enable it from the Omarchy plugin list. The bar widget appears in the
Omarchy bar; the panel opens from the plugin launcher.
