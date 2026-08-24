// Omarchy Link — Panel (loaded internally by BarWidget.qml via Loader).
//
// Official Omarchy convention (see omarchyplugins.com/develop.html):
//   - Root type is `Panel` with `moduleName`, `manageIpc: false`.
//   - It receives `bar`, `anchorItem`, `hostWidget` from the BarWidget.
//   - `open()/close()` drive `KeyboardPanel.controller.show()/hide()`.
//   - Content lives in a `KeyboardPanel` with a `PanelKeyCatcher`.
//
// Connection logic: this plugin is a CLIENT of the OhmLauncher phone server
// (HTTP+WS on port 8753). It can:
//   * auto-discover the phone via mDNS `_ohm._tcp` (needs a helper; see README),
//   * paste the phone's `ohm://<ip>:8753` QR manually,
//   * OR display an `omarchy://<pc-ip>:8753?id=<host>` QR for the phone to scan
//     and connect back (the phone becomes the client of THIS pc).
//
// All actions call the contract documented in README.md / TESTING.md.

import QtQuick
import Quickshell
import Quickshell.Io
import qs.Commons
import qs.Ui

Panel {
  id: root
  moduleName: "cl.villagranquiroz.omarchy-link"
  manageIpc: false

  // Exposed to BarWidget (button status) -----------------------------------
  property bool connected: false
  property string peerName: ""
  property string peerIp: ""
  property int peerPort: 8753
  property bool showLog: false
  property bool screenSharing: false

  property var anchorItem: null
  property var hostWidget: null

  // Apply the link-state JSON written by link_server.py (phone -> pc notify).
  function applyState(text) {
    try {
      const d = JSON.parse(text || "{}")
      root.connected = d.connected === true
      root.peerIp = d.peerIp || ""
      root.peerName = d.peerName || ""
    } catch (e) { /* ignore malformed */ }
  }

  function open() { controller.show(); regenerateQr(); serverTimer.restart() }
  function close() { controller.hide() }
  function switchPanel(direction) {
    if (root.bar && typeof root.bar.switchPanelFrom === "function")
      return root.bar.switchPanelFrom(root.hostWidget || root, direction)
    return false
  }

  // --- Connection helpers ---------------------------------------------------

  // Set the base URL from an `ohm://<ip>:8753` or `omarchy://<ip>:8753` string.
  function setPeer(uri) {
    const m = /(?:ohm|omarchy):\/\/([0-9.]+):(\d+)/.exec(uri)
    if (!m) { log("bad uri: " + uri); return }
    root.peerIp = m[1]
    root.peerPort = parseInt(m[2], 10)
    log("peer set -> " + root.peerIp + ":" + root.peerPort)
  }

  function base() { return "http://" + root.peerIp + ":" + root.peerPort }

  // Generic JSON GET against the OhmLauncher contract.
  function getJson(path, onOk, onErr) {
    const x = new XMLHttpRequest()
    x.open("GET", base() + path)
    x.onreadystatechange = function () {
      if (x.readyState === XMLHttpRequest.DONE) {
        if (x.status === 200) onOk(JSON.parse(x.responseText))
        else if (onErr) onErr(x.status, x.responseText)
      }
    }
    x.send()
  }

  // Generic JSON PUT.
  function putJson(path, body, onOk, onErr) {
    const x = new XMLHttpRequest()
    x.open("PUT", base() + path)
    x.setRequestHeader("Content-Type", "application/json")
    x.onreadystatechange = function () {
      if (x.readyState === XMLHttpRequest.DONE) {
        if (x.status === 200) onOk(JSON.parse(x.responseText))
        else if (onErr) onErr(x.status, x.responseText)
      }
    }
    x.send(JSON.stringify(body))
  }

  function connect() {
    if (!root.peerIp) { log("set a peer first"); return }
    getJson("/omarchy/discover", function (d) {
      root.connected = true
      root.peerName = d.name || "phone"
      log("connected: " + root.peerName)
    }, function (code) { log("discover failed: " + code) })
  }

  function pushClipboard(text) {
    putJson("/omarchy/clipboard", { text: text },
      function () { log("clipboard pushed") },
      function (c) { log("clipboard push failed: " + c) })
  }

  function pullClipboard() {
    getJson("/omarchy/clipboard",
      function (d) { log("clipboard: " + d.text) },
      function (c) { log("clipboard pull failed: " + c) })
  }

  function startScreen() {
    root.screenSharing = true
    postOnly("/omarchy/screen/start")
  }
  function stopScreen() {
    root.screenSharing = false
    postOnly("/omarchy/screen/stop")
  }
  function backupPhotos() {
    postOnly("/omarchy/photos/backup")
  }
  function postOnly(path) {
    const x = new XMLHttpRequest()
    x.open("POST", base() + path)
    x.onreadystatechange = function () {
      if (x.readyState === XMLHttpRequest.DONE) log(path + " -> " + x.status)
    }
    x.send()
  }

  // Apply the Omarchy theme on the phone (PUT /omarchy/theme). The phone decides
  // the concrete theme; we send a hint (dark by default).
  function applyTheme() {
    putJson("/omarchy/theme", { dark: true, source: "omarchy" },
      function () { log("theme applied") },
      function (c) { log("theme apply failed: " + c) })
  }

  // Regenerate the QR PNG (make_qr.sh) so the phone can scan and connect back.
  function regenerateQr() {
    qrProc.command = ["bash",
      Qt.resolvedUrl("make_qr.sh").replace("file://", ""),
      "", "8753", "omarchy-pc"]
    qrProc.running = true
  }

  Process {
    id: qrProc
    running: false
    command: ["bash", "make_qr.sh"]
    onExited: function (code) {
      if (code !== 0) log("qr gen failed: " + code)
      else { qrImage.source = ""; qrImage.source = "file:///tmp/omarchy-link-qr.png" }
    }
  }

  // Link-state server (phone -> pc notify). Started on first panel open so the
  // PC is listening on :8753 when the phone scans the omarchy:// QR. Quickshell
  // only launches Processes from a user-driven handler, hence open() not load.
  // The launch is deferred via a Timer so it never aborts the open() call.
  Process {
    id: linkServer
    running: false
    command: ["/usr/bin/python3", Qt.resolvedUrl("link_server.py").replace("file://", "")]
    onExited: function (code) { log("link server exited: " + code) }
  }
  Timer {
    id: serverTimer
    interval: 500
    running: false
    onTriggered: linkServer.running = true
  }

  // Watch the state file written by link_server.py and reflect it in the UI.
  FileView {
    id: linkStateFile
    path: "/tmp/omarchy-link-state.json"
    watchChanges: true
    onLoaded: root.applyState(text())
  }

  // Local log area shown in the panel (so an LLM/user can verify behavior).
  property string logText: ""
  function log(msg) { root.logText = root.logText + msg + "\n" }

  // --- UI -------------------------------------------------------------------
  SystemClock { id: clock; precision: SystemClock.Seconds }

  KeyboardPanel {
    id: panel
    anchorItem: root.anchorItem
    owner: root.hostWidget || root
    bar: root.bar
    open: root.opened
    focusTarget: keyCatcher
    contentWidth: panel.fittedContentWidth(Style.space(280))
    contentHeight: panel.fittedContentHeight(content.implicitHeight)

    PanelKeyCatcher {
      id: keyCatcher
      anchors.fill: parent
      onCloseRequested: root.close()
      onTabRequested: function (direction) { root.switchPanel(direction) }

      Column {
        id: content
        width: parent.width
        spacing: Style.space(8)
        Translation { id: i18n }

        Text {
          width: parent.width
          text: i18n.t("title")
          color: root.barForeground
          font.family: root.bar ? root.bar.fontFamily : Style.font.family
          font.pixelSize: Style.font.subtitle
          font.bold: true
        }

        Text {
          width: parent.width
          text: root.connected
            ? i18n.t("connected", root.peerName, root.peerIp)
            : i18n.t("notConnected")
          color: root.barForeground
          font.family: root.bar ? root.bar.fontFamily : Style.font.family
          font.pixelSize: Style.font.body
        }

        // QR for the phone to scan and connect back to THIS pc.
        // Generated as a PNG by make_qr.sh (Quickshell/Omarchy has no built-in
        // QR painter). Regenerated when the panel opens. Hidden once connected.
        Image {
          id: qrImage
          visible: !root.connected
          width: 160; height: 160
          fillMode: Image.PreserveAspectFit
          source: "file:///tmp/omarchy-link-qr.png"
          anchors.horizontalCenter: parent.horizontalCenter
        }

        // Hint: the system camera scanner on some phones (e.g. Xiaomi/HyperOS)
        // does not open custom URI schemes, so Google Lens is the reliable way
        // to trigger the omarchy:// deep link.
        Text {
          visible: !root.connected
          width: parent.width
          text: "(" + i18n.t("useGoogleLens") + ")"
          color: root.barForeground
          opacity: 0.7
          horizontalAlignment: Text.AlignHCenter
          font.family: root.bar ? root.bar.fontFamily : Style.font.family
          font.pixelSize: Style.font.caption
        }

        // Once connected, the QR is no longer needed. This re-shows it to link
        // an additional phone (the server keeps listening for more peers).
        WidgetButton {
          visible: root.connected
          text: i18n.t("linkMore")
          bar: root.bar
          onPressed: function (b) {
            if (b === Qt.LeftButton) { regenerateQr(); qrImage.visible = true }
          }
        }

        // Action grid (call the OhmLauncher contract). Disabled until linked.
        // Laid out as a 2-column grid (rows of 2) so it fits the panel width.
        Column {
          spacing: Style.space(6)
          Row {
            spacing: Style.space(6)
            WidgetButton { text: i18n.t("files"); bar: root.bar; enabled: root.connected
              onPressed: function (b) { if (b === Qt.LeftButton) root.postOnly("/omarchy/file") } }
            WidgetButton { text: i18n.t("copyToPhone"); bar: root.bar; enabled: root.connected
              onPressed: function (b) { if (b === Qt.LeftButton) root.pushClipboard("hello from Omarchy") } }
          }
          Row {
            spacing: Style.space(6)
            WidgetButton { text: i18n.t("copyFromPhone"); bar: root.bar; enabled: root.connected
              onPressed: function (b) { if (b === Qt.LeftButton) root.pullClipboard() } }
            WidgetButton { text: i18n.t("startScreen"); bar: root.bar; enabled: root.connected
              onPressed: function (b) { if (b === Qt.LeftButton) root.startScreen() } }
          }
          Row {
            spacing: Style.space(6)
            WidgetButton { text: i18n.t("backupPhotos"); bar: root.bar; enabled: root.connected
              onPressed: function (b) { if (b === Qt.LeftButton) root.backupPhotos() } }
            WidgetButton { text: i18n.t("themes"); bar: root.bar; enabled: root.connected
              onPressed: function (b) { if (b === Qt.LeftButton) root.applyTheme() } }
          }
        }

        // Screen-share viewer (phone -> pc). Shows the latest frame saved by
        // link_server.py (/omarchy/screen/frame) while screenSharing is active.
        Image {
          id: screenImage
          visible: root.screenSharing
          width: 220; height: 140
          fillMode: Image.PreserveAspectFit
          source: "file:///tmp/omarchy-screen.jpg"
          anchors.horizontalCenter: parent.horizontalCenter
        }

        // Refresh the frame view while sharing (the file is overwritten live).
        Timer {
          running: root.screenSharing
          interval: 250
          repeat: true
          onTriggered: {
            screenImage.source = ""
            screenImage.source = "file:///tmp/omarchy-screen.jpg"
          }
        }

        // Toggle to reveal the internal log (verification surface for an agent/user).
        WidgetButton {
          text: root.showLog ? i18n.t("logHide") : i18n.t("logShow")
          bar: root.bar
          enabled: root.connected
          onPressed: function (b) { if (b === Qt.LeftButton) root.showLog = !root.showLog }
        }

        // Live log (hidden unless the Log toggle is on).
        Text {
          visible: root.showLog
          width: parent.width
          text: root.logText
          color: root.barForeground
          font.family: "monospace"
          font.pixelSize: Style.font.caption
          wrapMode: Text.WordWrap
        }
      }
    }
  }
}
