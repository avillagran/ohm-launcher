// Built-in weather widget for the curated OhmLauncher desktop.
// OhmLauncher injects a native Weather binding (Map) with the selected city,
// temperature, condition, unit, and last-update time; double-tapping the
// widget opens the city picker. When the binding is absent the original
// static demo values keep the widget renderable, and the clock stays live.
import QtQuick
import Quickshell
import Quickshell.Io

BarWidget {
  id: root

  SystemClock {
    id: clock
    precision: SystemClock.Minutes
  }

  // Without the injected binding the interpreter resolves unknown capitalized
  // references to plain strings, so Weather.unit equals neither "C" nor "F"
  // and the static fallbacks below still apply.
  readonly property bool weatherBound: Weather.unit == "C" || Weather.unit == "F"
  readonly property string ciudad: root.weatherBound ? Weather.city : "Santiago"
  readonly property string tempText: root.weatherBound ? Weather.tempText : "18°"
  readonly property string condicion: root.weatherBound ? Weather.condition : "Despejado"
  readonly property string actualizado: root.weatherBound ? Weather.updated : Qt.formatTime(clock.date, "HH:mm")
  readonly property string iconText: root.weatherBound && Weather.icon ? Weather.icon : "󰖔"
  readonly property var forecast0: root.weatherBound ? Weather.forecast0 : null
  readonly property var forecast1: root.weatherBound ? Weather.forecast1 : null
  readonly property var forecast2: root.weatherBound ? Weather.forecast2 : null

  Column {
    spacing: 6

    Row {
      spacing: 8
      Text {
        text: root.iconText
        color: Color.accent
        font.pixelSize: 18
      }
      Text {
        text: root.ciudad
        color: Color.foreground
        font.pixelSize: 16
        font.bold: true
      }
    }

    Row {
      spacing: 10
      Text {
        text: root.tempText
        color: Color.foreground
        font.pixelSize: 40
        font.bold: true
        font.family: "monospace"
      }
      Column {
        mainAxisSize: MainAxisSize.min
        Text {
          text: root.condicion
          color: Color.muted
          font.pixelSize: 13
        }
        Text {
          text: "act. " + root.actualizado
          color: Color.muted
          font.pixelSize: 11
        }
      }
    }

    Row {
      spacing: 6
      Rectangle {
        visible: root.forecast0 && root.forecast0.icon
        width: 62; height: 56; radius: 4; color: Color.surface
        Column {
          anchors.fill: parent
          Text { anchors.horizontalCenter: parent.horizontalCenter; text: root.forecast0 ? root.forecast0.day : ""; color: Color.muted; font.pixelSize: 10 }
          Text { anchors.horizontalCenter: parent.horizontalCenter; text: root.forecast0 ? root.forecast0.icon : ""; color: Color.accent; font.pixelSize: 19 }
          Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: 2
            Text { text: root.forecast0 ? root.forecast0.high : ""; color: Color.foreground; font.pixelSize: 10 }
            Text { text: root.forecast0 ? root.forecast0.low : ""; color: Color.muted; font.pixelSize: 10 }
          }
        }
      }
      Rectangle {
        visible: root.forecast1 && root.forecast1.icon
        width: 62; height: 56; radius: 4; color: Color.surface
        Column {
          anchors.fill: parent
          Text { anchors.horizontalCenter: parent.horizontalCenter; text: root.forecast1 ? root.forecast1.day : ""; color: Color.muted; font.pixelSize: 10 }
          Text { anchors.horizontalCenter: parent.horizontalCenter; text: root.forecast1 ? root.forecast1.icon : ""; color: Color.accent; font.pixelSize: 19 }
          Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: 2
            Text { text: root.forecast1 ? root.forecast1.high : ""; color: Color.foreground; font.pixelSize: 10 }
            Text { text: root.forecast1 ? root.forecast1.low : ""; color: Color.muted; font.pixelSize: 10 }
          }
        }
      }
      Rectangle {
        visible: root.forecast2 && root.forecast2.icon
        width: 62; height: 56; radius: 4; color: Color.surface
        Column {
          anchors.fill: parent
          Text { anchors.horizontalCenter: parent.horizontalCenter; text: root.forecast2 ? root.forecast2.day : ""; color: Color.muted; font.pixelSize: 10 }
          Text { anchors.horizontalCenter: parent.horizontalCenter; text: root.forecast2 ? root.forecast2.icon : ""; color: Color.accent; font.pixelSize: 19 }
          Row {
            anchors.horizontalCenter: parent.horizontalCenter
            spacing: 2
            Text { text: root.forecast2 ? root.forecast2.high : ""; color: Color.foreground; font.pixelSize: 10 }
            Text { text: root.forecast2 ? root.forecast2.low : ""; color: Color.muted; font.pixelSize: 10 }
          }
        }
      }
    }
  }
}
