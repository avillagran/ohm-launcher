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
  // Port of THIS pc's link_server (for local status polls + QR generation).
  // Reported by link_server.py in the state file so the panel never assumes 8753.
  property int linkPort: 8753
  property bool showLog: true
  property bool screenSharing: false
  property bool screenExpanded: false
  property int frameCount: 0
  // Phone pixel size (from /omarchy/screen/status) for remote-control mapping.
  property int screenW: 0
  property int screenH: 0
  // File browser state.
  property bool showFiles: false
  property string filesPath: "/sdcard"
  property string filesParent: ""

  property var anchorItem: null
  property var hostWidget: null

  // Apply the link-state JSON written by link_server.py (phone -> pc notify).
  function applyState(text) {
    try {
      const d = JSON.parse(text || "{}")
      root.connected = d.connected === true
      root.peerIp = d.peerIp || ""
      root.peerName = d.peerName || ""
      if (d.peerPort) root.peerPort = parseInt(d.peerPort, 10)
      if (d.linkPort) root.linkPort = parseInt(d.linkPort, 10)
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
    root.frameCount = 0
    screenImage.source = ""
    postOnly("/omarchy/screen/start")
  }
  function stopScreen() {
    root.screenSharing = false
    screenImage.source = ""
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

  // --- Remote control -----------------------------------------------------

  // Send an input event to the phone: {action:'tap'|'swipe'|'key', ...}.
  function sendInput(obj) {
    const x = new XMLHttpRequest()
    x.open("POST", base() + "/omarchy/input")
    x.setRequestHeader("Content-Type", "application/json")
    x.onreadystatechange = function () {
      if (x.readyState === XMLHttpRequest.DONE) {
        try {
          const r = JSON.parse(x.responseText)
          if (r.ok !== true) log("input " + obj.action + " -> " + (r.error || x.status))
        } catch (e) { log("input " + obj.action + " -> " + x.status) }
      }
    }
    x.send(JSON.stringify(obj))
  }

  // Map a point inside screenImage (PreserveAspectFit) to phone pixels.
  function mapToPhone(mx, my) {
    if (root.screenW <= 0 || root.screenH <= 0) return null
    const scale = Math.min(screenImage.width / root.screenW, screenImage.height / root.screenH)
    const dw = root.screenW * scale, dh = root.screenH * scale
    const ox = (screenImage.width - dw) / 2, oy = (screenImage.height - dh) / 2
    const px = (mx - ox) / scale, py = (my - oy) / scale
    if (px < 0 || py < 0 || px > root.screenW || py > root.screenH) return null
    return { x: Math.round(px), y: Math.round(py) }
  }

  // --- File browser ---------------------------------------------------------

  function loadFiles(path) {
    getJson("/omarchy/files?path=" + encodeURIComponent(path), function (d) {
      root.filesPath = d.path || path
      root.filesParent = d.parent || ""
      filesModel.clear()
      for (const e of (d.entries || [])) {
        filesModel.append({ name: e.name, path: e.path, isDir: e.isDir === true, size: e.size || 0 })
      }
    }, function (c) { log("files failed: " + c) })
  }

  function downloadFile(path, name) {
    // bash expands $HOME; mkdir -p so first download never fails.
    const url = base() + "/omarchy/file?path=" + encodeURIComponent(path)
    dlProc.command = ["bash", "-c",
      "mkdir -p \"$HOME/Downloads\" && curl -sSL -o \"$HOME/Downloads/" + name + "\" \"" + url + "\""]
    dlProc.running = true
    log("downloading " + name)
  }
  Process { id: dlProc; running: false; command: ["true"]
    onExited: function (code) { log(code === 0 ? "download ok" : "download failed: " + code) } }

  // Ask the local link server to read the real current Omarchy colors.toml and
  // push the complete palette to the connected phone.
  function applyTheme() {
    themeProc.command = ["curl", "-sS", "-X", "POST",
      "http://127.0.0.1:" + String(root.linkPort) + "/omarchy/theme/push"]
    themeProc.running = true
  }
  Process { id: themeProc; running: false; command: ["true"]
    onExited: function (code) { log(code === 0 ? "theme applied" : "theme apply failed: " + code) } }

  // Regenerate the QR PNG (make_qr.sh) so the phone can scan and connect back.
  function regenerateQr() {
    qrProc.command = ["bash",
      Qt.resolvedUrl("make_qr.sh").toString().replace("file://", ""),
      "", String(root.linkPort), "omarchy-pc"]
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
    command: ["/usr/bin/python3", Qt.resolvedUrl("link_server.py").toString().replace("file://", "")]
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

  FileView {
    id: serverLogFile
    path: "/tmp/ls.log"
    watchChanges: true
    onLoaded: {
      const lines = text().trim().split("\n")
      root.logText = lines.slice(Math.max(0, lines.length - 12)).join("\n")
    }
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
    contentWidth: panel.fittedContentWidth(Style.space(root.screenExpanded ? 620 : 280))
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
            if (b === 1) { regenerateQr(); qrImage.visible = true }
          }
        }

        // Action grid (call the OhmLauncher contract). Hidden until linked;
        // appears (with a fade) when the phone connects. Laid out as a 2-column
        // grid (rows of 2) so it fits the panel width.
        Column {
          visible: root.connected
          opacity: root.connected ? 1 : 0
          Behavior on opacity { NumberAnimation { duration: 200 } }
          spacing: Style.space(6)
          Row {
            spacing: Style.space(6)
            WidgetButton { text: i18n.t("files"); bar: root.bar
              onPressed: function (b) {
                if (b === 1) {
                  root.showFiles = !root.showFiles
                  if (root.showFiles) root.loadFiles(root.filesPath)
                }
              }
            }
            WidgetButton { text: i18n.t("copyToPhone"); bar: root.bar
              onPressed: function (b) { if (b === 1) root.pushClipboard("hello from Omarchy") } }
          }
          Row {
            spacing: Style.space(6)
            WidgetButton { text: i18n.t("copyFromPhone"); bar: root.bar; enabled: root.connected
              onPressed: function (b) { if (b === 1) root.pullClipboard() } }
            WidgetButton { text: root.screenSharing ? i18n.t("stopScreen") : i18n.t("startScreen"); bar: root.bar
              onPressed: function (b) { if (b === 1) { root.screenSharing ? root.stopScreen() : root.startScreen() } } }
          }
          Row {
            spacing: Style.space(6)
            WidgetButton { text: i18n.t("backupPhotos"); bar: root.bar
              onPressed: function (b) { if (b === 1) root.backupPhotos() } }
            WidgetButton { text: i18n.t("themes"); bar: root.bar
              onPressed: function (b) { if (b === 1) root.applyTheme() } }
          }
        }

        // File browser (browse the phone's shared storage; tap a file to
        // download it to ~/Downloads on this pc).
        Column {
          visible: root.showFiles
          opacity: root.showFiles ? 1 : 0
          Behavior on opacity { NumberAnimation { duration: 200 } }
          spacing: Style.space(4)
          width: parent.width

          ListModel { id: filesModel }

          Text {
            width: parent.width
            text: root.filesPath
            color: root.barForeground
            opacity: 0.7
            elide: Text.ElideLeft
            font.family: "monospace"
            font.pixelSize: Style.font.caption
          }
          WidgetButton {
            visible: root.filesParent !== ""
            text: ".."
            bar: root.bar
            onPressed: function (b) { if (b === 1) root.loadFiles(root.filesParent) }
          }
          ListView {
            width: parent.width
            height: 160
            clip: true
            model: filesModel
            delegate: Rectangle {
              width: ListView.view.width
              height: 22
              color: fileArea.containsMouse ? Qt.rgba(1, 1, 1, 0.08) : "transparent"
              radius: 4
              Text {
                anchors.verticalCenter: parent.verticalCenter
                anchors.left: parent.left
                anchors.leftMargin: 6
                width: parent.width - 12
                text: (model.isDir ? "📁 " : "📄 ") + model.name
                color: root.barForeground
                elide: Text.ElideRight
                font.family: root.bar ? root.bar.fontFamily : Style.font.family
                font.pixelSize: Style.font.caption
              }
              MouseArea {
                id: fileArea
                anchors.fill: parent
                hoverEnabled: true
                onClicked: {
                  if (model.isDir) root.loadFiles(model.path)
                  else root.downloadFile(model.path, model.name)
                }
              }
            }
          }
        }

        // Screen-share viewer (phone -> pc) + REMOTE CONTROL (pc -> phone).
        // Tap on the image = tap on the phone; drag = swipe. Coordinates are
        // mapped through PreserveAspectFit using the phone pixel size reported
        // in /omarchy/screen/status.
        Image {
          id: screenImage
          visible: root.screenSharing
          width: root.screenExpanded ? 560 : 220
          height: root.screenExpanded ? 700 : 140
          fillMode: Image.PreserveAspectFit
          source: ""
          anchors.horizontalCenter: parent.horizontalCenter
          MouseArea {
            anchors.fill: parent
            property real pressX: 0
            property real pressY: 0
            onPressed: function (m) { pressX = m.x; pressY = m.y }
            onReleased: function (m) {
              const p1 = root.mapToPhone(pressX, pressY)
              const p2 = root.mapToPhone(m.x, m.y)
              if (!p1 || !p2) return
              const dx = p2.x - p1.x, dy = p2.y - p1.y
              if (Math.sqrt(dx * dx + dy * dy) < 30)
                root.sendInput({ action: "tap", x: p2.x, y: p2.y })
              else
                root.sendInput({ action: "swipe", x1: p1.x, y1: p1.y, x2: p2.x, y2: p2.y, durationMs: 300 })
            }
          }
        }

        // Navigation keys for remote control (global actions on the phone).
        Row {
          visible: root.screenSharing
          spacing: Style.space(6)
          anchors.horizontalCenter: parent.horizontalCenter
          WidgetButton { text: "◀"; bar: root.bar
            onPressed: function (b) { if (b === 1) root.sendInput({ action: "key", key: "back" }) } }
          WidgetButton { text: "●"; bar: root.bar
            onPressed: function (b) { if (b === 1) root.sendInput({ action: "key", key: "home" }) } }
          WidgetButton { text: "■"; bar: root.bar
            onPressed: function (b) { if (b === 1) root.sendInput({ action: "key", key: "recents" }) } }
          WidgetButton {
            text: i18n.t(root.screenExpanded ? "screenReduce" : "screenExpand")
            bar: root.bar
            onPressed: function (b) { if (b === 1) root.screenExpanded = !root.screenExpanded }
          }
        }

        // Pull status and JPEGs from the phone. The reverse phone -> PC route
        // may be blocked by the desktop firewall even though PC -> phone works.
        Text {
          visible: root.screenSharing
          text: "Receiving frames: " + root.frameCount
          color: root.barForeground
          font.family: "monospace"
          font.pixelSize: Style.font.caption
          anchors.horizontalCenter: parent.horizontalCenter
        }

        // Refresh the frame view + counter while sharing.
        Timer {
          running: root.screenSharing
          interval: 500
          repeat: true
          onTriggered: {
            var x = new XMLHttpRequest()
            x.open("GET", base() + "/omarchy/screen/status")
            x.onreadystatechange = function () {
              if (x.readyState === XMLHttpRequest.DONE) {
                try {
                  var j = JSON.parse(x.responseText)
                  if (j.w) root.screenW = j.w
                  if (j.h) root.screenH = j.h
                  if (j.frames && j.frames !== root.frameCount) {
                    root.frameCount = j.frames
                    screenImage.source = ""
                    screenImage.source = base() + "/omarchy/screen/frame?sequence=" + j.frames
                  }
                } catch (e) {}
              }
            }
            x.send()
          }
        }

        // Toggle to reveal the internal log (verification surface for an agent/user).
        WidgetButton {
          text: root.showLog ? i18n.t("logHide") : i18n.t("logShow")
          bar: root.bar
          enabled: true
          onPressed: function (b) { if (b === 1) root.showLog = !root.showLog }
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
