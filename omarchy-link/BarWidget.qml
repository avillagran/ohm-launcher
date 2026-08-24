// Omarchy Link — bar-widget entry point (official Omarchy plugin convention).
//
// This file is the manifest `entryPoints.barWidget`. It exposes the Omarchy
// BarWidget contract: `open/close/toggle`, `opened`, `popoutSwitchClosing`,
// and loads Panel.qml internally through a Loader (exactly like the official
// clock example at omarchyplugins.com/develop.html).
//
// Connection logic lives here + in Panel.qml. The plugin is a CLIENT of the
// OhmLauncher phone server (HTTP+WS on port 8753); it can also show an
// `omarchy://` QR for the phone to scan and connect back (see Panel.qml).

import QtQuick
import Quickshell
import qs.Ui

BarWidget {
  id: root
  moduleName: "cl.villagranquiroz.omarchy-link"

  // Link-state server (phone -> pc notify). Runs for the life of the widget so
  // the PC is always listening on :8753 when the phone scans the omarchy:// QR.
  // Started via a Timer (Quickshell Process needs a post-completion tick to launch).
  Process {
    id: linkServer
    running: false
    command: ["/usr/bin/python3", Qt.resolvedUrl("link_server.py").replace("file://", "")]
  }
  Timer {
    interval: 1000
    running: true
    onTriggered: linkServer.running = true
  }

  // Omarchy BarWidget contract -------------------------------------------------
  readonly property bool opened: panelLoader.item
    ? panelLoader.item.opened === true
    : false
  readonly property bool popoutSwitchClosing: panelLoader.item
    ? panelLoader.item.popoutSwitchClosing === true
    : false

  function open() { if (panelLoader.item) panelLoader.item.open() }
  function close() { if (panelLoader.item) panelLoader.item.close() }
  function toggle() { if (panelLoader.item) panelLoader.item.toggle() }
  function closeForPopoutSwitch() {
    if (panelLoader.item) panelLoader.item.closeForPopoutSwitch()
  }
  function injectPanel() {
    if (!panelLoader.item) return
    panelLoader.item.bar = root.bar
    panelLoader.item.anchorItem = button
    panelLoader.item.hostWidget = root
  }

  implicitWidth: button.implicitWidth
  implicitHeight: button.implicitHeight
  onBarChanged: injectPanel()

  // Panel (loaded internally; not a separate manifest kind) --------------------
  Loader {
    id: panelLoader
    active: true
    source: Qt.resolvedUrl("Panel.qml")
    visible: false
    onLoaded: {
      root.injectPanel()
      Qt.callLater(root.injectPanel)
      if (panelLoader.item && typeof panelLoader.item.startLinkServer === "function")
        panelLoader.item.startLinkServer()
    }
  }

  // Bar button: Android icon; turns accent when connected -------------------
  BarIconButton {
    id: button
    anchors.fill: parent
    bar: root.bar
    active: panelLoader.item && panelLoader.item.connected
    useActiveColor: true
    activeColor: "#4caf50"
    iconComponent: Component { AndroidIcon { color: button.active ? "#4caf50" : (root.bar ? root.bar.foreground : "#ffffff") } }
    tooltipText: panelLoader.item && panelLoader.item.connected
      ? "OhmLauncher connected (" + panelLoader.item.peerName + ")"
      : "Open OhmLauncher Link"
    onPressed: function (buttonCode) {
      if (buttonCode === Qt.LeftButton) root.toggle()
    }
  }

  // Shared connection state (exposed to Panel.qml) ----------------------------
  QtObject {
    id: linkState
    property bool connected: false
    property string peerName: ""
    property string peerIp: ""
    property int peerPort: 8753
  }
}
