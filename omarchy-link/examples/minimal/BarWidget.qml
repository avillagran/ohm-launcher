// Minimal Omarchy plugin starter (BarWidget + Panel).
// Copy this folder's two files into a new plugin dir to bootstrap.
// Official convention: root types are `BarWidget` (entry) and `Panel`
// (loaded internally via Loader). Do NOT use Scope/PanelWindow here.

import QtQuick
import Quickshell
import qs.Ui

BarWidget {
  id: root
  moduleName: "your.name.minimal"

  readonly property bool opened: panelLoader.item ? panelLoader.item.opened : false
  readonly property bool popoutSwitchClosing: panelLoader.item ? panelLoader.item.popoutSwitchClosing : false

  function open() { if (panelLoader.item) panelLoader.item.open() }
  function close() { if (panelLoader.item) panelLoader.item.close() }
  function toggle() { if (panelLoader.item) panelLoader.item.toggle() }
  function closeForPopoutSwitch() { if (panelLoader.item) panelLoader.item.closeForPopoutSwitch() }
  function injectPanel() {
    if (!panelLoader.item) return
    panelLoader.item.bar = root.bar
    panelLoader.item.anchorItem = button
    panelLoader.item.hostWidget = root
  }

  implicitWidth: button.implicitWidth
  implicitHeight: button.implicitHeight
  onBarChanged: injectPanel()

  Loader {
    id: panelLoader
    active: true
    source: Qt.resolvedUrl("Panel.qml")
    visible: false
    onLoaded: { root.injectPanel(); Qt.callLater(root.injectPanel) }
  }

  WidgetButton {
    id: button
    anchors.fill: parent
    bar: root.bar
    text: "Min"
    tooltipText: "Minimal plugin"
    onPressed: function (b) { if (b === Qt.LeftButton) root.toggle() }
  }
}
