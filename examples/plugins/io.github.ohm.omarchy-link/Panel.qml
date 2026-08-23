// Omarchy Link — reference panel that consumes the OhmLauncher contract.
//
// Connection model (both directions):
//   * Phone is the server (default): this panel scans mDNS `_ohm._tcp` on the
//     LAN and calls the phone's endpoints. Also accepts the phone's QR
//     `ohm://<phone-ip>:8753` pasted manually.
//   * PC is the server (this plugin): the panel shows a QR `omarchy://<pc-ip>:8753?id=<host>`
//     that you scan with the PHONE's camera (system camera app). Android routes
//     the `omarchy://` intent to OhmLauncher, which then connects to this PC.
//     To receive the phone's calls, this plugin must expose the SAME contract
//     over HTTP+WS (see README.md: a tiny C++/Python helper is required because
//     pure QML has no HttpServer; a WebSocket server is available via QtWebSockets).
//
// Contract (baseUrl = http://<ip>:<port>):
//   GET  /omarchy/discover        -> {name,model,lan_ip,port,capabilities}
//   GET  /omarchy/clipboard       -> {text}
//   PUT  /omarchy/clipboard       -> {text}
//   GET  /omarchy/theme           -> {colors:{...}}
//   PUT  /omarchy/theme           -> applies colors
//   POST /omarchy/file (multipart)-> receives a file
//   GET  /omarchy/file?path=      -> downloads a file
//   POST /omarchy/screen/start|stop
//   POST /omarchy/photos/backup   -> lists DCIM photos (peer downloads via /file)
//   WS   /omarchy/ws              -> events (peer_hello, clipboard_changed)
//
// QR rendering: Quickshell has no built-in QR painter. Register a
// `QQuickImageProvider` named "qrcode" (C++ side) that paints the data string,
// then use:  Image { source: "image://qrcode/omarchy://<ip>:8753?id=<host>" }
// A minimal C++ snippet is provided in README.md.

import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtWebSockets

ColumnLayout {
    id: root
    spacing: 8
    property string baseUrl: "http://192.168.1.141:8753"   // phone (or PC) ip:port
    property string status: "disconnected"

    Label { text: "Omarchy Link"; font.bold: true; font.pixelSize: 16 }

    // --- This PC's connection QR (scan it with the phone's camera) ---
    GroupBox {
        title: "Show this to the phone"
        Layout.fillWidth: true
        ColumnLayout {
            Label { text: "Scan with the phone camera to link:"; font.pixelSize: 12 }
            // Requires a "qrcode" QQuickImageProvider (C++). See README.md.
            Image {
                id: qr
                Layout.alignment: Qt.AlignCenter
                width: 180; height: 180
                source: "image://qrcode/" + ("omarchy://" + pcIp.text + ":8753?id=" + pcId.text)
                fillMode: Image.PreserveAspectFit
                Rectangle {
                    anchors.fill: parent; color: "transparent"
                    border.color: "#66E0FF"; border.width: 1
                    visible: qr.status !== Image.Ready
                    Label {
                        anchors.centerIn: parent; text: "omarchy://" + pcIp.text + ":8753?id=" + pcId.text
                        wrapMode: Text.Wrap; horizontalAlignment: Text.AlignHCenter
                        font.pixelSize: 11
                    }
                }
            }
            RowLayout {
                Label { text: "PC IP:" }
                TextField { id: pcIp; text: _localIp(); Layout.preferredWidth: 120 }
                Label { text: "id:" }
                TextField { id: pcId; text: _hostName(); Layout.preferredWidth: 90 }
            }
        }
    }

    // --- Manual peer entry + connect ---
    RowLayout {
        TextField {
            id: urlField
            Layout.fillWidth: true
            placeholderText: "http://phone-ip:8753"
            text: root.baseUrl
            onAccepted: root.baseUrl = text
        }
        Button {
            text: "Connect"
            onClicked: {
                root.baseUrl = urlField.text
                apiGet("/omarchy/discover", function (ok, data) {
                    if (ok) root.status = "peer: " + (data.name || "OhmLauncher")
                    else root.status = "error"
                })
            }
        }
    }
    Label { text: "Status: " + root.status }

    GridLayout {
        columns: 2
        Layout.fillWidth: true
        Button {
            text: "Copy to phone"
            Layout.fillWidth: true
            onClicked: {
                apiPut("/omarchy/clipboard", { text: desktopClipboard() }, function (ok, d) {
                    root.status = ok ? "clipboard sent" : "failed"
                })
            }
        }
        Button {
            text: "Pull from phone"
            Layout.fillWidth: true
            onClicked: {
                apiGet("/omarchy/clipboard", function (ok, d) {
                    if (ok) setDesktopClipboard(d.text || "")
                    root.status = ok ? "clipboard pulled" : "failed"
                })
            }
        }
        Button {
            text: "Send file"
            Layout.fillWidth: true
            onClicked: root.status = "use POST /omarchy/file (multipart)"
        }
        Button {
            text: "Pull theme"
            Layout.fillWidth: true
            onClicked: {
                apiGet("/omarchy/theme", function (ok, d) {
                    if (ok) applyTheme(d.colors || {})
                    root.status = ok ? "theme pulled" : "failed"
                })
            }
        }
        Button {
            text: "Share screen"
            Layout.fillWidth: true
            onClicked: apiPost("/omarchy/screen/start", {}, function (ok, d) {
                root.status = ok ? "screen: " + (d.status || "ok") : "failed"
            })
        }
        Button {
            text: "Backup photos"
            Layout.fillWidth: true
            onClicked: apiPost("/omarchy/photos/backup", {}, function (ok, d) {
                root.status = ok ? "photos: " + (d.status || "ok") : "failed"
            })
        }
    }

    // --- helpers ---
    function _localIp() {
        // Best-effort: returns the first non-loopback IPv4 via a helper.
        // In Quickshell, wire this to a C++/Python helper exposing the LAN IP.
        return "192.168.1.50"
    }
    function _hostName() {
        return "omarchy-pc"
    }
    function apiGet(path, cb) {
        var x = new XMLHttpRequest()
        x.open("GET", root.baseUrl + path, true)
        x.onreadystatechange = function () {
            if (x.readyState === XMLHttpRequest.DONE) {
                try { cb(x.status === 200, JSON.parse(x.responseText)) }
                catch (e) { cb(false, {}) }
            }
        }
        x.send()
    }
    function apiPut(path, body, cb) {
        var x = new XMLHttpRequest()
        x.open("PUT", root.baseUrl + path, true)
        x.setRequestHeader("Content-Type", "application/json")
        x.onreadystatechange = function () {
            if (x.readyState === XMLHttpRequest.DONE) cb(x.status === 200, {})
        }
        x.send(JSON.stringify(body))
    }
    function apiPost(path, body, cb) {
        var x = new XMLHttpRequest()
        x.open("POST", root.baseUrl + path, true)
        x.setRequestHeader("Content-Type", "application/json")
        x.onreadystatechange = function () {
            if (x.readyState === XMLHttpRequest.DONE) {
                try { cb(x.status === 200, JSON.parse(x.responseText)) }
                catch (e) { cb(x.status === 200, {}) }
            }
        }
        x.send(JSON.stringify(body))
    }

    // Stubs a native backend (C++/Process) must implement in Omarchy:
    function desktopClipboard() { return "" }
    function setDesktopClipboard(t) {}
    function applyTheme(colors) {}

    WebSocket {
        id: ws
        url: root.baseUrl.replace("http", "ws") + "/omarchy/ws"
        active: false
        onTextMessageReceived: function (msg) {
            try {
                var e = JSON.parse(msg)
                if (e.type === "clipboard_changed") root.status = "remote clipboard changed"
            } catch (err) {}
        }
    }
    Button {
        text: "Open events WS"
        onClicked: ws.active = true
    }
}
