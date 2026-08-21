import Quickshell
import Quickshell.Io
import Quickshell.Wayland
import QtQuick
import qs.Commons
import qs.Ui

Item {
  id: root

  property string omarchyPath: Quickshell.env("OMARCHY_PATH")
  property var shell: null
  property var manifest: null

  // This plugin is self-contained: the bundled cursor scripts live in the
  // plugin's own bin/ directory (manifest.__sourceDir is stamped by the
  // plugin registry). Scripts resolve there first so the picker works on any
  // omarchy install, falling back to bare command names if the dir is empty.
  property string pluginBin: (root.manifest && root.manifest.__sourceDir)
    ? root.manifest.__sourceDir.replace(/\/$/, "") + "/bin"
    : ""

  function tool(name) {
    if (root.pluginBin)
      return "'" + (root.pluginBin + "/" + name).replace(/'/g, "'\\''") + "'"
    return name
  }

  property bool opened: false
  property string mode: "theme"
  property string filterText: ""
  property int selectedIndex: 0
  property bool cursorActive: false
  property string currentValue: ""

  // Shares the [menu] surface tokens — themes that style the menu also
  // style this picker.
  property color background: Color.menu.background
  property color foreground: Color.menu.text
  property color border: Color.menu.border
  property var borderSpec: Border.surfaceSpec("menu", "border", border, Math.max(1, Style.space(2)))
  property color scrim: Color.menu.scrim
  property color selectedBackground: Color.menu.selectedBackground
  property color selectedText: Color.menu.selectedText
  readonly property int cornerRadius: Style.cornerRadius
  property string fontFamily: Style.font.menuFamily
  property int contentMargin: Style.spacing.panelPadding
  property int contentSpacing: Style.spacing.md
  property int cardWidth: Math.min(Style.space(360), panel.width - Style.gapsOut * 2)
  property int cardHeight: Math.min(Style.space(480), panel.height - Style.gapsOut * 2)
  property int headerHeight: Math.max(Style.space(34), Style.font.title + Style.spacing.controlPaddingY * 2)
  property int searchHeight: Math.max(Style.space(34), Style.font.title + Style.spacing.controlPaddingY * 2)
  property int rowHeight: Math.max(Style.space(38), Style.font.body + Style.spacing.controlPaddingY * 2)

  readonly property bool sizeMode: root.mode === "size"

  property int listRevision: 0
  property var filtered: []

  function listScript() {
    if (root.sizeMode)
      return "current=$(" + root.tool("omarchy-cursor-size-current") + " 2>/dev/null); { " + root.tool("omarchy-cursor-size-list") + " 2>/dev/null | grep -qx \"$current\" && printf '%s\\t%s\\t%s\\n' 'Custom size' 'custom' \"$current\" || printf '%s\\t%s\\t%s\\n' 'Custom size' 'custom' \"$current\" '1'; " + root.tool("omarchy-cursor-size-list") + " 2>/dev/null | while read -r s; do [[ -z $s ]] && continue; printf '%s\\t%s\\t%s\\n' \"$s\" \"$s\" \"$current\"; done; }"
    return "current=$(" + root.tool("omarchy-cursor-current") + " 2>/dev/null); " + root.tool("omarchy-cursor-list") + " 2>/dev/null | while read -r c; do [[ -z $c ]] && continue; printf '%s\\t%s\\t%s\\n' \"$c\" \"$c\" \"$current\"; done"
  }

  function open(payloadJson) {
    root.opened = true
    root.filterText = ""
    root.selectedIndex = 0
    root.cursorActive = true
    root.reload()
    Qt.callLater(function() { keyCatcher.forceActiveFocus() })
  }

  function close() {
    root.opened = false
  }

  function dismiss() {
    root.opened = false
    if (root.shell && typeof root.shell.hide === "function")
      root.shell.hide((root.manifest && root.manifest.id) || "taxin.cursor-style")
  }

  function toggle() {
    if (root.opened) root.dismiss()
    else root.open("{}")
  }

  function reload() {
    root.listRevision += 1
    listProc.revision = root.listRevision
    listProc.collected = ""
    listProc.command = ["bash", "-c", root.listScript()]
    listProc.running = true
  }

  function rebuildDisplay() {
    var raw = []
    for (var i = 0; i < model.count; i++) raw.push(model.get(i))
    var needle = root.filterText.toLowerCase()
    var out = []
    for (var j = 0; j < raw.length; j++) {
      if (!needle || String(raw[j].label).toLowerCase().indexOf(needle) !== -1)
        out.push(raw[j])
    }
    root.filtered = out

    if (out.length === 0) root.selectedIndex = 0
    else if (root.selectedIndex >= out.length) root.selectedIndex = out.length - 1
    else if (root.selectedIndex < 0) root.selectedIndex = 0
    root.cursorActive = out.length > 0

    Qt.callLater(function() {
      if (out.length > 0) resultList.positionViewAtIndex(root.selectedIndex, ListView.Contain)
    })
  }

  function select(delta) {
    if (root.filtered.length === 0) return
    root.selectedIndex = (root.selectedIndex + delta + root.filtered.length) % root.filtered.length
    root.cursorActive = true
    resultList.positionViewAtIndex(root.selectedIndex, ListView.Contain)
  }

  function setFilter(next) {
    root.filterText = next
    root.selectedIndex = 0
    root.cursorActive = true
    root.rebuildDisplay()
  }

  function activateIndex(index) {
    if (index < 0 || index >= root.filtered.length) return
    var row = root.filtered[index]
    if (root.sizeMode) {
      if (row.value === "custom") {
        Util.execDetached(root.tool("omarchy-cursor-size-custom"))
        customReloadTimer.restart()
        return
      }
      Util.execDetached(root.tool("omarchy-cursor-size-set") + " " + Util.shellQuote(row.value))
    } else {
      Util.execDetached(root.tool("omarchy-cursor-set") + " " + Util.shellQuote(row.value))
    }
    root.currentValue = row.value
    root.markCurrent(row.value)
    root.rebuildDisplay()
  }

  function markCurrent(newValue) {
    for (var m = 0; m < model.count; m++) {
      var item = model.get(m)
      model.set(m, { label: item.label, value: item.value, current: item.value === newValue })
    }
  }

  function switchMode(next) {
    if (root.mode === next) return
    root.mode = next
    root.filterText = ""
    root.selectedIndex = 0
    root.cursorActive = true
    root.currentValue = ""
    root.reload()
  }

  ListModel {
    id: model
  }

  Process {
    id: listProc
    property string collected: ""
    property int revision: 0
    stdout: SplitParser {
      onRead: function(data) { listProc.collected += data + "\n" }
    }
    onExited: {
      if (listProc.revision !== root.listRevision) return
      var current = ""
      model.clear()
      var lines = listProc.collected.split("\n")
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i].trim()
        if (!line) continue
        var parts = line.split("\t")
        if (parts.length < 2) continue
        var label = parts[0]
        var value = parts[1]
        var isCurrent = parts.length > 2 && (parts[2] === value || (parts.length > 3 && parts[3] === "1"))
        if (isCurrent) current = parts[2]
        model.append({ label: label, value: value, current: isCurrent })
      }
        root.currentValue = current
        for (var k = 0; k < model.count; k++) {
          if (model.get(k).value === current) {
            root.selectedIndex = k
            break
          }
        }
        root.rebuildDisplay()
    }
  }

  // The custom-size script applies asynchronously; reload shortly after it is
  // launched so the list and readout pick up the newly persisted size.
  Timer {
    id: customReloadTimer
    interval: 500
    repeat: false
    onTriggered: root.reload()
  }

  PanelWindow {
    id: panel
    visible: root.opened
    anchors { top: true; bottom: true; left: true; right: true }
    color: "transparent"
    WlrLayershell.namespace: "taxin-cursor-style"
    WlrLayershell.layer: WlrLayer.Overlay
    WlrLayershell.keyboardFocus: WlrKeyboardFocus.Exclusive
    exclusionMode: ExclusionMode.Ignore

    Rectangle {
      anchors.fill: parent
      color: root.scrim
    }

    MouseArea {
      anchors.fill: parent
      onClicked: root.dismiss()
    }

    BorderSurface {
      id: card
      width: root.cardWidth
      height: root.cardHeight
      radius: root.cornerRadius
      anchors.centerIn: parent
      color: root.background
      borderSpec: root.borderSpec
      padding: root.contentMargin

      MouseArea { anchors.fill: parent; onClicked: {} }

      Item {
        id: keyCatcher
        anchors.fill: parent
        focus: true

        Keys.priority: Keys.BeforeItem
        Keys.onPressed: function(event) {
          if (event.key === Qt.Key_Escape) {
            if (root.filterText) root.setFilter("")
            else root.dismiss()
            event.accepted = true
          } else if (Util.editsFilter(event, root.filterText)) {
            root.setFilter(Util.editedFilter(event, root.filterText))
            event.accepted = true
          } else if (event.key === Qt.Key_Tab) {
            root.switchMode(root.sizeMode ? "theme" : "size")
            event.accepted = true
          } else if (event.key === Qt.Key_Up) {
            root.select(-1)
            event.accepted = true
          } else if (event.key === Qt.Key_Down) {
            root.select(1)
            event.accepted = true
          } else if (event.key === Qt.Key_PageUp) {
            root.select(-Math.max(1, Math.floor(resultList.height / root.rowHeight)))
            event.accepted = true
          } else if (event.key === Qt.Key_PageDown) {
            root.select(Math.max(1, Math.floor(resultList.height / root.rowHeight)))
            event.accepted = true
          } else if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
            if (root.cursorActive) root.activateIndex(root.selectedIndex)
            else if (root.filtered.length > 0) root.cursorActive = true
            event.accepted = true
          } else if (event.text && event.text.length === 1 && event.text.charCodeAt(0) >= 32 && event.text.charCodeAt(0) !== 127) {
            root.setFilter(root.filterText + event.text)
            event.accepted = true
          }
        }
      }

      Column {
        anchors.fill: parent
        anchors.topMargin: card.contentTopInset
        anchors.rightMargin: card.contentRightInset
        anchors.bottomMargin: card.contentBottomInset
        anchors.leftMargin: card.contentLeftInset
        spacing: root.contentSpacing

        Item {
          width: parent.width
          height: root.headerHeight

          Text {
            id: title
            text: "Cursor Style"
            color: root.foreground
            font.family: root.fontFamily
            font.pixelSize: Style.font.heading
            anchors.verticalCenter: parent.verticalCenter
            anchors.left: parent.left
            elide: Text.ElideRight
          }

          Text {
            visible: root.currentValue !== ""
            text: root.sizeMode ? ("Current: " + root.currentValue + "px") : ("Current: " + root.currentValue)
            color: root.foreground
            opacity: 0.55
            font.family: root.fontFamily
            font.pixelSize: Style.font.title
            elide: Text.ElideRight
            horizontalAlignment: Text.AlignRight
            anchors.verticalCenter: parent.verticalCenter
            anchors.left: title.right
            anchors.leftMargin: Style.space(8)
            anchors.right: modeRow.left
            anchors.rightMargin: Style.space(8)
          }

          Row {
            id: modeRow
            anchors.verticalCenter: parent.verticalCenter
            anchors.right: parent.right
            spacing: Style.space(4)

            Rectangle {
              width: Style.space(76)
              height: Style.space(30)
              radius: root.cornerRadius
              color: root.sizeMode ? "transparent" : root.selectedBackground

              Text {
                text: "󰇀 Theme"
                color: root.sizeMode ? root.foreground : root.selectedText
                opacity: root.sizeMode ? 0.7 : 1
                font.family: root.fontFamily
                font.pixelSize: Style.font.title
                anchors.centerIn: parent
              }

              MouseArea {
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: root.switchMode("theme")
              }
            }

            Rectangle {
              width: Style.space(72)
              height: Style.space(30)
              radius: root.cornerRadius
              color: root.sizeMode ? root.selectedBackground : "transparent"

              Text {
                text: "󰹵 Size"
                color: root.sizeMode ? root.selectedText : root.foreground
                opacity: root.sizeMode ? 1 : 0.7
                font.family: root.fontFamily
                font.pixelSize: Style.font.title
                anchors.centerIn: parent
              }

              MouseArea {
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onClicked: root.switchMode("size")
              }
            }
          }
        }

        Rectangle {
          width: parent.width
          height: root.searchHeight
          radius: root.cornerRadius
          color: "transparent"

          Text {
            text: root.filterText || (root.sizeMode ? "Search sizes…" : "Search themes…")
            color: root.foreground
            opacity: root.filterText ? 1 : 0.58
            font.family: root.fontFamily
            font.pixelSize: Style.font.title
            elide: Text.ElideRight
            anchors.verticalCenter: parent.verticalCenter
            anchors.left: parent.left
            anchors.right: parent.right
          }
        }

        Item {
          width: parent.width
          height: parent.height - root.headerHeight - root.searchHeight - root.contentSpacing * 2

          ListView {
            id: resultList
            anchors.fill: parent
            model: root.filtered
            clip: true
            spacing: Style.space(2)
            boundsBehavior: Flickable.StopAtBounds

            delegate: Rectangle {
              required property int index
              required property var modelData

              readonly property bool hasCursor: root.cursorActive && index === root.selectedIndex
              readonly property bool isCurrent: modelData.current

              width: parent.width
              height: root.rowHeight
              radius: root.cornerRadius
              color: hasCursor ? root.selectedBackground : "transparent"

              Text {
                text: modelData.label
                color: root.foreground
                font.family: root.fontFamily
                font.pixelSize: Style.font.body
                anchors.verticalCenter: parent.verticalCenter
                anchors.left: parent.left
                anchors.leftMargin: Style.space(10)
                anchors.right: checkMark.left
                anchors.rightMargin: Style.space(10)
                elide: Text.ElideRight
              }

              Text {
                id: checkMark
                visible: isCurrent
                text: "✓"
                color: root.selectedText
                font.family: root.fontFamily
                font.pixelSize: Style.font.title
                anchors.verticalCenter: parent.verticalCenter
                anchors.right: parent.right
                anchors.rightMargin: Style.space(10)
              }

              MouseArea {
                anchors.fill: parent
                hoverEnabled: true
                cursorShape: Qt.PointingHandCursor
                onContainsMouseChanged: if (containsMouse) {
                  root.cursorActive = true
                  root.selectedIndex = index
                }
                onClicked: {
                  root.cursorActive = true
                  root.selectedIndex = index
                  root.activateIndex(index)
                }
              }
            }
          }

          Column {
            anchors.centerIn: parent
            spacing: Style.space(8)
            visible: root.filtered.length === 0 && !listProc.running

            Text {
              text: "󰇀"
              color: root.selectedText
              opacity: 0.8
              font.family: root.fontFamily
              font.pixelSize: Style.font.displayLarge
              horizontalAlignment: Text.AlignHCenter
              width: parent.width
            }

            Text {
              text: "No matches for “" + root.filterText + "”"
              color: root.foreground
              opacity: 0.7
              font.family: root.fontFamily
              font.pixelSize: Style.font.title
              horizontalAlignment: Text.AlignHCenter
              width: parent.width
            }
          }
        }
      }
    }
  }

  // Self-install the menu row into the user's menu extension. The plugin owns
  // its menu entry so it survives core upgrades; keepLoaded mounts this overlay
  // at shell startup, so the row appears as soon as the plugin is enabled.
  // pluginBin is only known once the shell has handed us the manifest, so run
  // this from its change handler rather than Component.onCompleted.
  property bool menuRowInstalled: false
  onPluginBinChanged: {
    if (!root.menuRowInstalled && root.pluginBin) {
      root.menuRowInstalled = true
      Util.execDetached(root.tool("omarchy-cursor-menu-install"))
    }
  }

  // Disabling or deleting the plugin destroys this overlay, so take the menu
  // row back out at the same time; enabling re-adds it via onPluginBinChanged.
  // The cleanup runs through the entry script copied into the user config,
  // not this plugin's own bin/, so it survives the plugin being removed.
  Component.onDestruction: {
    var ext = Quickshell.env("OMARCHY_MENU_EXTENSION")
    var dir = ext ? String(ext).replace(/\/[^\/]*$/, "") : Quickshell.env("HOME") + "/.config/omarchy/extensions"
    Util.execDetached(Util.shellQuote(dir + "/omarchy-cursor-menu-entry") + " --remove")
  }
}
