// Widget de clima (demo del bridge QML de Ohm Launcher).
// Muestra la estructura visual completa de un widget de clima: ciudad,
// temperatura grande, descripción y hora de "última actualización".
// Los datos son de ejemplo; para clima EN VIVO hay que extender el bridge
// QML con un nodo de red (ver nota en main.dart). El SystemClock demuestra
// que el QML se interpreta en caliente sin compilar.
import QtQuick
import Quickshell
import Quickshell.Io

BarWidget {
  id: root

  SystemClock {
    id: clock
    precision: SystemClock.Minutes
  }

  readonly property string ciudad: "Santiago"
  readonly property int tempC: 18
  readonly property string condicion: "Despejado"
  readonly property string actualizado: Qt.formatTime(clock.date, "HH:mm")

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
        text: root.tempC + "°"
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
