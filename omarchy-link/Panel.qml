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
  property string peerToken: ""
  // Port of THIS pc's link_server (for local status polls + QR generation).
  // Reported by link_server.py in the state file so the panel never assumes 8753.
  property int linkPort: 8753
  // Keep the diagnostics available without making them the panel's default focus.
  property bool showLog: false
  property bool screenSharing: false
  property bool screenExpanded: false
  property bool showPairing: false
  property bool pairingDismissed: false
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

  readonly property color accentColor: Color.accent
  readonly property color connectionColor: "#4ade80"
  readonly property color surfaceColor: Style.normalFillFor(root.barForeground, root.accentColor)
  readonly property color surfaceBorder: Style.normalBorderFor(root.barForeground, root.accentColor)
  readonly property int surfaceRadius: Style.cornerRadius

  // Apply the link-state JSON written by link_server.py (phone -> pc notify).
  function applyState(text) {
    try {
      const d = JSON.parse(text || "{}")
      root.connected = d.connected === true
      root.peerIp = d.peerIp || ""
      root.peerName = d.peerName || ""
      if (d.peerToken !== undefined) root.peerToken = d.peerToken || ""
      if (root.connected) root.showPairing = false
      if (d.peerPort) root.peerPort = parseInt(d.peerPort, 10)
      if (d.linkPort) root.linkPort = parseInt(d.linkPort, 10)
    } catch (e) { /* ignore malformed */ }
  }

  function open() {
    controller.show()
    refreshConnectionState()
    if ((!root.connected && !root.pairingDismissed) || root.showPairing)
      regenerateQr()
    serverTimer.restart()
  }
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
    const token = /[?&]token=([^&]+)/.exec(uri)
    root.peerToken = token ? decodeURIComponent(token[1]) : ""
    log("peer set -> " + root.peerIp + ":" + root.peerPort)
  }

  function base() { return "http://" + root.peerIp + ":" + root.peerPort }
  function authorize(request) {
    if (root.peerToken) request.setRequestHeader("X-Omarchy-Link-Token", root.peerToken)
  }

  // FileView remains the event-driven source, while this cheap local probe
  // repairs stale bar state after helper/shell restarts or missed file events.
  function refreshConnectionState() {
    const x = new XMLHttpRequest()
    x.open("GET", "http://127.0.0.1:" + String(root.linkPort) + "/omarchy/link")
    x.onreadystatechange = function () {
      if (x.readyState === XMLHttpRequest.DONE && x.status === 200)
        root.applyState(x.responseText)
    }
    x.send()
  }

  // Generic JSON GET against the OhmLauncher contract.
  function getJson(path, onOk, onErr) {
    const x = new XMLHttpRequest()
    x.open("GET", base() + path)
    authorize(x)
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
    authorize(x)
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
    frameA.source = ""
    frameB.source = ""
    postOnly("/omarchy/screen/start")
    screenRetry.start()
  }
  function stopScreen() {
    root.screenSharing = false
    screenRetry.stop()
    frameA.source = ""
    frameB.source = ""
    postOnly("/omarchy/screen/stop")
  }
  // Chained long-poll: the phone answers only when a frame newer than
  // frameCount exists (or after 20s with 204), so frames arrive at capture
  // speed instead of timer speed.
  function pullScreenFrame() {
    if (!root.screenSharing) return
    var x = new XMLHttpRequest()
    x.open("GET", base() + "/omarchy/screen/status?after=" + root.frameCount + "&timeout=20000")
    authorize(x)
    x.onreadystatechange = function () {
      if (x.readyState !== XMLHttpRequest.DONE) return
      var advanced = false
      if (x.status === 200) {
        try {
          var j = JSON.parse(x.responseText)
          if (j.w) root.screenW = j.w
          if (j.h) root.screenH = j.h
          if (j.frames && j.frames !== root.frameCount) {
            root.frameCount = j.frames
            // Load into the hidden buffer; it flips visible when decoded.
            const url = base() + "/omarchy/screen/frame?sequence=" + j.frames
              + "&token=" + encodeURIComponent(root.peerToken)
            if (screenImage.frontA) frameB.source = url
            else frameA.source = url
            advanced = true
          }
        } catch (e) {}
      }
      if (advanced) pullScreenFrame()
      else screenRetry.start()
    }
    x.send()
  }
  function backupPhotos() {
    postOnly("/omarchy/photos/backup")
  }
  function postOnly(path) {
    const x = new XMLHttpRequest()
    x.open("POST", base() + path)
    authorize(x)
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
    authorize(x)
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
    const safeName = String(name || "download").replace(/[^A-Za-z0-9._-]/g, "_")
    // bash expands $HOME; mkdir -p so first download never fails.
    const url = base() + "/omarchy/file?path=" + encodeURIComponent(path)
    dlProc.command = ["bash", "-c",
      "mkdir -p \"$HOME/Downloads\" && curl -sSL -H \"X-Omarchy-Link-Token: "
        + root.peerToken + "\" -o \"$HOME/Downloads/" + safeName + "\" \"" + url + "\""]
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

  Timer {
    interval: 2000
    repeat: true
    running: true
    triggeredOnStart: true
    onTriggered: root.refreshConnectionState()
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
        spacing: Style.spacing.panelGap
        Translation { id: i18n }

        // Compact brand block: mark, product name, peer, and state all read as
        // one header instead of unrelated title/status lines.
        Row {
          width: parent.width
          spacing: Style.spacing.md

          Rectangle {
            width: Style.space(34)
            height: width
            radius: root.surfaceRadius
            color: root.connected
              ? Style.selectedFillFor(root.barForeground, root.connectionColor)
              : root.surfaceColor
            border.width: Style.normalBorderWidth
            border.color: root.connected ? root.connectionColor : root.surfaceBorder

            AndroidIcon {
              anchors.centerIn: parent
              width: Style.space(18)
              height: width
              color: root.connected ? root.connectionColor : root.barForeground
            }
          }

          Column {
            width: parent.width - Style.space(34) - Style.spacing.md * 2 - statusMark.width
            spacing: Style.spacing.xxs

            Text {
              width: parent.width
              text: i18n.t("title")
              color: root.barForeground
              font.family: root.bar ? root.bar.fontFamily : Style.font.family
              font.pixelSize: Style.font.subtitle
              font.bold: true
              elide: Text.ElideRight
            }
            Text {
              width: parent.width
              text: root.connected
                ? ((root.peerName || "OhmLauncher") + " · " + root.peerIp)
                : i18n.t("notConnected")
              color: root.connected ? root.connectionColor : root.barForeground
              opacity: root.connected ? 1 : 0.62
              font.family: root.bar ? root.bar.fontFamily : Style.font.family
              font.pixelSize: Style.font.caption
              elide: Text.ElideRight
            }
          }

          Rectangle {
            id: statusMark
            width: Style.space(8)
            height: width
            anchors.verticalCenter: parent.verticalCenter
            radius: root.surfaceRadius
            color: root.connected ? root.connectionColor : root.surfaceBorder
          }
        }

        PanelSeparator { foreground: root.barForeground }

        // Pairing is a single, bounded surface and disappears after linking.
        Rectangle {
          visible: (!root.connected && !root.pairingDismissed) || root.showPairing
          width: parent.width
          implicitHeight: pairingContent.implicitHeight + Style.spacing.xl * 2
          radius: root.surfaceRadius
          color: root.surfaceColor
          border.width: Style.normalBorderWidth
          border.color: root.surfaceBorder

          Column {
            id: pairingContent
            x: Style.spacing.xl
            y: Style.spacing.xl
            width: parent.width - Style.spacing.xl * 2
            spacing: Style.spacing.sm

            Image {
              id: qrImage
              width: Style.space(148)
              height: width
              fillMode: Image.PreserveAspectFit
              source: "file:///tmp/omarchy-link-qr.png"
              anchors.horizontalCenter: parent.horizontalCenter
            }
            Text {
              width: parent.width
              text: i18n.t("discoverHint")
              color: root.barForeground
              opacity: 0.72
              horizontalAlignment: Text.AlignHCenter
              wrapMode: Text.WordWrap
              font.family: root.bar ? root.bar.fontFamily : Style.font.family
              font.pixelSize: Style.font.bodySmall
            }
            Text {
              width: parent.width
              text: i18n.t("useGoogleLens")
              color: root.accentColor
              horizontalAlignment: Text.AlignHCenter
              font.family: root.bar ? root.bar.fontFamily : Style.font.family
              font.pixelSize: Style.font.caption
            }
            WidgetButton {
              width: parent.width
              text: i18n.t("hideQr")
              bar: root.bar
              onPressed: function (b) {
                if (b === 1) {
                  root.showPairing = false
                  root.pairingDismissed = true
                }
              }
            }
          }
        }

        // Connected actions share one visual rhythm and fixed two-column grid.
        Rectangle {
          visible: root.connected
          width: parent.width
          implicitHeight: actionLayout.implicitHeight + Style.spacing.lg * 2
          radius: root.surfaceRadius
          color: root.surfaceColor
          border.width: Style.normalBorderWidth
          border.color: root.surfaceBorder

          Column {
            id: actionLayout
            x: Style.spacing.lg
            y: Style.spacing.lg
            width: parent.width - Style.spacing.lg * 2
            spacing: Style.spacing.sm

            Row {
              width: parent.width
              spacing: Style.spacing.sm
              WidgetButton {
                fixedWidth: (parent.width - Style.spacing.sm) / 2
                text: i18n.t("files")
                bar: root.bar
                active: root.showFiles
                activeColor: root.accentColor
                onPressed: function (b) {
                  if (b === 1) {
                    root.showFiles = !root.showFiles
                    if (root.showFiles) root.loadFiles(root.filesPath)
                  }
                }
              }
              WidgetButton {
                fixedWidth: (parent.width - Style.spacing.sm) / 2
                text: i18n.t("backupPhotos")
                bar: root.bar
                onPressed: function (b) { if (b === 1) root.backupPhotos() }
              }
            }
            PanelSeparator { foreground: root.barForeground; strength: 0.08 }
            Row {
              width: parent.width
              spacing: Style.spacing.sm
              WidgetButton {
                fixedWidth: (parent.width - Style.spacing.sm) / 2
                text: i18n.t("copyToPhone")
                bar: root.bar
                onPressed: function (b) { if (b === 1) root.pushClipboard("hello from Omarchy") }
              }
              WidgetButton {
                fixedWidth: (parent.width - Style.spacing.sm) / 2
                text: i18n.t("copyFromPhone")
                bar: root.bar
                enabled: root.connected
                onPressed: function (b) { if (b === 1) root.pullClipboard() }
              }
            }
            PanelSeparator { foreground: root.barForeground; strength: 0.08 }
            Row {
              width: parent.width
              spacing: Style.spacing.sm
              WidgetButton {
                fixedWidth: (parent.width - Style.spacing.sm) / 2
                text: root.screenSharing ? i18n.t("stopScreen") : i18n.t("startScreen")
                bar: root.bar
                active: root.screenSharing
                activeColor: root.accentColor
                onPressed: function (b) {
                  if (b === 1) root.screenSharing ? root.stopScreen() : root.startScreen()
                }
              }
              WidgetButton {
                fixedWidth: (parent.width - Style.spacing.sm) / 2
                text: i18n.t("themes")
                bar: root.bar
                onPressed: function (b) { if (b === 1) root.applyTheme() }
              }
            }
          }
        }

        // Phone file browser.
        Rectangle {
          visible: root.showFiles
          width: parent.width
          implicitHeight: filesContent.implicitHeight + Style.spacing.lg * 2
          radius: root.surfaceRadius
          color: root.surfaceColor
          border.width: Style.normalBorderWidth
          border.color: root.surfaceBorder

          Column {
            id: filesContent
            x: Style.spacing.lg
            y: Style.spacing.lg
            width: parent.width - Style.spacing.lg * 2
            spacing: Style.spacing.sm

            ListModel { id: filesModel }

            Row {
              width: parent.width
              spacing: Style.spacing.sm
              WidgetButton {
                visible: root.filesParent !== ""
                text: ".."
                bar: root.bar
                fixedWidth: Style.space(28)
                onPressed: function (b) { if (b === 1) root.loadFiles(root.filesParent) }
              }
              Text {
                width: parent.width - (root.filesParent !== "" ? Style.space(28) + Style.spacing.sm : 0)
                anchors.verticalCenter: parent.verticalCenter
                text: root.filesPath
                color: root.barForeground
                opacity: 0.68
                elide: Text.ElideLeft
                font.family: root.bar ? root.bar.fontFamily : Style.font.family
                font.pixelSize: Style.font.caption
              }
            }
            ListView {
              width: parent.width
              height: Style.space(156)
              clip: true
              model: filesModel
              spacing: Style.spacing.xxs
              delegate: Rectangle {
                width: ListView.view.width
                height: Style.spacing.controlHeight
                radius: root.surfaceRadius
                color: fileArea.containsMouse
                  ? Style.hoverFillFor(root.barForeground, root.accentColor)
                  : "transparent"
                Text {
                  anchors.verticalCenter: parent.verticalCenter
                  anchors.left: parent.left
                  anchors.leftMargin: Style.spacing.sm
                  width: parent.width - Style.spacing.lg
                  text: (model.isDir ? "▸ " : "· ") + model.name
                  color: model.isDir ? root.accentColor : root.barForeground
                  elide: Text.ElideRight
                  font.family: root.bar ? root.bar.fontFamily : Style.font.family
                  font.pixelSize: Style.font.bodySmall
                }
                MouseArea {
                  id: fileArea
                  anchors.fill: parent
                  hoverEnabled: true
                  cursorShape: Qt.PointingHandCursor
                  onClicked: {
                    if (model.isDir) root.loadFiles(model.path)
                    else root.downloadFile(model.path, model.name)
                  }
                }
              }
            }
          }
        }

        // Live screen remains fully interactive, now framed as a theme surface.
        Rectangle {
          visible: root.screenSharing
          width: root.screenExpanded ? Style.space(576) : parent.width
          implicitHeight: screenColumn.implicitHeight + Style.spacing.lg * 2
          anchors.horizontalCenter: parent.horizontalCenter
          radius: root.surfaceRadius
          color: root.surfaceColor
          border.width: Style.normalBorderWidth
          border.color: root.surfaceBorder

          Column {
            id: screenColumn
            x: Style.spacing.lg
            y: Style.spacing.lg
            width: parent.width - Style.spacing.lg * 2
            spacing: Style.spacing.sm

            Item {
              id: screenImage
              width: root.screenExpanded ? 560 : 220
              height: root.screenExpanded ? 700 : 140
              anchors.horizontalCenter: parent.horizontalCenter
              property bool frontA: true

              Rectangle { anchors.fill: parent; color: Color.background; radius: root.surfaceRadius }
              Image {
                id: frameA
                anchors.fill: parent
                visible: parent.frontA
                cache: false
                fillMode: Image.PreserveAspectFit
                onStatusChanged: if (status === Image.Ready && !parent.frontA) parent.frontA = true
              }
              Image {
                id: frameB
                anchors.fill: parent
                visible: !parent.frontA
                cache: false
                fillMode: Image.PreserveAspectFit
                onStatusChanged: if (status === Image.Ready && parent.frontA) parent.frontA = false
              }
              MouseArea {
                anchors.fill: parent
                cursorShape: Qt.PointingHandCursor
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

            Row {
              spacing: Style.spacing.sm
              anchors.horizontalCenter: parent.horizontalCenter
              WidgetButton { text: "◀"; bar: root.bar; fixedWidth: Style.space(32)
                onPressed: function (b) { if (b === 1) root.sendInput({ action: "key", key: "back" }) } }
              WidgetButton { text: "●"; bar: root.bar; fixedWidth: Style.space(32)
                onPressed: function (b) { if (b === 1) root.sendInput({ action: "key", key: "home" }) } }
              WidgetButton { text: "■"; bar: root.bar; fixedWidth: Style.space(32)
                onPressed: function (b) { if (b === 1) root.sendInput({ action: "key", key: "recents" }) } }
              WidgetButton {
                text: i18n.t(root.screenExpanded ? "screenReduce" : "screenExpand")
                bar: root.bar
                onPressed: function (b) { if (b === 1) root.screenExpanded = !root.screenExpanded }
              }
            }
            Text {
              width: parent.width
              text: "Receiving frames · " + root.frameCount
              color: root.barForeground
              opacity: 0.56
              horizontalAlignment: Text.AlignHCenter
              font.family: root.bar ? root.bar.fontFamily : Style.font.family
              font.pixelSize: Style.font.caption
            }
          }
        }

        // Diagnostics stay one line high until explicitly expanded.
        Rectangle {
          width: parent.width
          implicitHeight: diagnostics.implicitHeight + Style.spacing.md * 2
          radius: root.surfaceRadius
          color: root.surfaceColor
          border.width: Style.normalBorderWidth
          border.color: root.surfaceBorder

          Column {
            id: diagnostics
            x: Style.spacing.lg
            y: Style.spacing.md
            width: parent.width - Style.spacing.lg * 2
            spacing: Style.spacing.sm

            Row {
              width: parent.width
              Text {
                width: parent.width - logButton.width
                anchors.verticalCenter: parent.verticalCenter
                text: i18n.t("logHeader")
                color: root.barForeground
                opacity: 0.62
                font.family: root.bar ? root.bar.fontFamily : Style.font.family
                font.pixelSize: Style.font.caption
              }
              WidgetButton {
                id: logButton
                text: root.showLog ? i18n.t("logHide") : i18n.t("logShow")
                bar: root.bar
                enabled: true
                onPressed: function (b) { if (b === 1) root.showLog = !root.showLog }
              }
            }
            Text {
              visible: root.showLog
              width: parent.width
              text: root.logText
              color: root.barForeground
              opacity: 0.78
              font.family: root.bar ? root.bar.fontFamily : Style.font.family
              font.pixelSize: Style.font.caption
              wrapMode: Text.WordWrap
            }
          }
        }

        WidgetButton {
          visible: root.connected || root.pairingDismissed
          width: parent.width
          text: root.connected ? i18n.t("linkMore") : i18n.t("showQr")
          bar: root.bar
          foreground: root.accentColor
          onPressed: function (b) {
            if (b === 1) {
              root.showPairing = !root.showPairing
              root.pairingDismissed = false
              if (root.showPairing) regenerateQr()
            }
          }
        }
      }
    }
  }

  // Chained push-over-HTTP retry; no fixed frame-rate polling.
  Timer {
    id: screenRetry
    interval: 400
    repeat: false
    onTriggered: pullScreenFrame()
  }
}
