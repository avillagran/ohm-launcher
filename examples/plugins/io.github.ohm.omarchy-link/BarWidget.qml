// Omarchy Link — compact bar widget for OhmLauncher.
// Shows connection status and opens the full panel.
import QtQuick
import QtQuick.Controls

Row {
    id: root
    spacing: 6
    property string baseUrl: "http://192.168.1.141:8753"
    Label { text: "🔗"; font.pixelSize: 14 }
    Button {
        text: "Omarchy"
        onClicked: {
            var x = new XMLHttpRequest()
            x.open("GET", root.baseUrl + "/omarchy/discover", true)
            x.onreadystatechange = function () {
                if (x.readyState === XMLHttpRequest.DONE)
                    statusLabel.text = x.status === 200 ? "connected" : "offline"
            }
            x.send()
        }
    }
    Label { id: statusLabel; text: "—"; font.pixelSize: 12 }
}
