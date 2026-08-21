import QtQuick
import Quickshell
import qs.Ui
import qs.Commons

BarWidget {
  id: root
  moduleName: "pick.screenshot"

  property bool popupOpen: false

  function close() { popupOpen = false }

  implicitWidth: button.implicitWidth
  implicitHeight: barSize

  WidgetButton {
    id: button
    anchors.fill: parent
    bar: root.bar
    text: "\uF030"
    horizontalMargin: 7.5
    onPressed: function(button) {
      if (button === Qt.RightButton) {
        Util.execDetached("omarchy-capture-screenshot")
      } else {
        root.popupOpen = !root.popupOpen
      }
    }
  }

  PopupCard {
    id: popup
    anchorItem: button
    bar: root.bar
    owner: root
    open: root.popupOpen
    contentWidth: popup.fittedContentWidth(Style.space(260))
    contentHeight: popup.fittedContentHeight(column.implicitHeight)

    Column {
      id: column
      width: parent.width
      spacing: Style.space(6)

      Repeater {
        model: [
          { icon: "\uF0C8", label: "Region", detail: "Select an area", mode: "region" },
          { icon: "\uF2D0", label: "Fullscreen", detail: "All monitors", mode: "fullscreen" },
          { icon: "\uF2D2", label: "Window", detail: "Active window", mode: "windows" },
        ]

        delegate: BorderSurface {
          required property var modelData

          readonly property string itemIcon: modelData.icon
          readonly property string itemLabel: modelData.label
          readonly property string itemDetail: modelData.detail
          readonly property string itemMode: modelData.mode

          width: column.width
          implicitHeight: row.implicitHeight + Style.space(12)
          radius: Style.cornerRadius
          color: mouseArea.containsMouse
            ? Style.hoverFillFor(root.bar.foreground, Color.accent)
            : "transparent"

          Row {
            id: row
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            anchors.leftMargin: Style.space(10)
            anchors.rightMargin: Style.space(10)
            spacing: Style.space(10)

            Text {
              text: itemIcon
              color: root.bar.foreground
              font.family: root.bar.fontFamily
              font.pixelSize: Style.font.title
              width: Style.space(24)
              horizontalAlignment: Text.AlignHCenter
              anchors.verticalCenter: parent.verticalCenter
            }

            Column {
              spacing: Style.space(1)
              anchors.verticalCenter: parent.verticalCenter

              Text {
                text: itemLabel
                color: root.bar.foreground
                font.family: root.bar.fontFamily
                font.pixelSize: Style.font.body
                font.bold: true
              }

              Text {
                text: itemDetail
                color: Qt.darker(root.bar.foreground, 1.5)
                font.family: root.bar.fontFamily
                font.pixelSize: Style.font.caption
              }
            }
          }

          MouseArea {
            id: mouseArea
            anchors.fill: parent
            hoverEnabled: true
            cursorShape: Qt.PointingHandCursor
            onClicked: {
              Util.execDetached("omarchy-capture-screenshot " + itemMode)
              root.close()
            }
          }
        }
      }
    }
  }
}
