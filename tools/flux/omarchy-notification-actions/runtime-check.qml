// Runs in an isolated Quickshell config with no notification server or window.
import QtQuick
import Quickshell

Scope {
  id: root

  Component.onCompleted: Qt.callLater(function() {
    var plugin = Quickshell.env("OHM_FLUX_NOTIFICATION_PLUGIN")
    var component = Qt.createComponent(plugin + "/components/NotificationCard.qml")
    if (component.status !== Component.Ready) {
      console.error("NotificationCard runtime load failed:", component.errorString())
      Qt.exit(1)
      return
    }
    var card = component.createObject(root, {
      summary: "Flux action layout validation",
      body: "Isolated rendering check",
      actionToken: "runtime-fixture",
      actionsJson: JSON.stringify([
        { identifier: "fixture-approve", label: "Approve" },
        { identifier: "fixture-deny", label: "Deny" }
      ])
    })
    if (!card) {
      console.error("NotificationCard runtime construction failed:", component.errorString())
      Qt.exit(1)
      return
    }
    Qt.callLater(function() {
      if (card.implicitWidth <= 0 || card.implicitHeight <= 0) {
        console.error("NotificationCard runtime layout has no dimensions")
        Qt.exit(1)
        return
      }
      console.log("NotificationCard runtime construction passed")
      card.destroy()
      Qt.exit(0)
    })
  })
}
