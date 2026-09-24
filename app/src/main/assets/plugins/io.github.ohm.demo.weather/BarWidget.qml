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

  Column {
    spacing: 6

    Row {
      spacing: 8
      Text {
        text: "󰖔"
        color: "#66E0FF"
        font.pixelSize: 18
      }
      Text {
        text: root.ciudad
        color: "#E8F1F8"
        font.pixelSize: 16
        font.bold: true
      }
    }

    Row {
      spacing: 10
      Text {
        text: root.tempText
        color: "#FFFFFF"
        font.pixelSize: 40
        font.bold: true
        font.family: "monospace"
      }
      Column {
        mainAxisSize: MainAxisSize.min
        Text {
          text: root.condicion
          color: "#9AA7B4"
          font.pixelSize: 13
        }
        Text {
          text: "act. " + root.actualizado
          color: "#5A6B7A"
          font.pixelSize: 11
        }
      }
    }

    Row {
      spacing: 6
      Rectangle { width: 28; height: 28; radius: 6; color: "#1A2330"; }
      Rectangle { width: 28; height: 28; radius: 6; color: "#21303F"; }
      Rectangle { width: 28; height: 28; radius: 6; color: "#0F1A24"; }
    }
  }
}
