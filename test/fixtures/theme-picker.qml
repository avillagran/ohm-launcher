import QtQuick
import QtQuick.Controls
import Quickshell
import Quickshell.Io
import qs.Commons
import qs.Ui

BarWidget {
    id: root
    moduleName: "theme-picker"

    readonly property string icon: "\uefcc"

    property var themes: []
    property string currentTheme: ""
    property bool applying: false

    readonly property string currentThemeName: {
        for (var i = 0; i < root.themes.length; i++) {
            if (root.themes[i].slug === root.currentTheme)
                return root.themes[i].name;
        }
        return root.currentTheme;
    }

    implicitWidth: button.implicitWidth
    implicitHeight: button.implicitHeight

    function colorList(colors) {
        var keys = ["accent", "background", "foreground", "red", "orange", "yellow", "green", "cyan", "blue", "magenta"];
        var out = [];
        for (var i = 0; i < keys.length; i++) {
            var hex = colors ? colors[keys[i]] : "";
            if (hex)
                out.push(String(hex));
        }
        return out;
    }

    function colorFor(hex, fallback) {
        if (!hex)
            return fallback;
        var s = String(hex);
        return /^#?[0-9a-fA-F]{3}([0-9a-fA-F]{3})?([0-9a-fA-F]{2})?$/.test(s) ? Qt.color(s) : fallback;
    }

    function reload() {
        themesProcess.running = true;
    }

    function updateCurrentTheme(raw) {
        var slug = String(raw || "").trim();
        root.currentTheme = slug;
        for (var i = 0; i < root.themes.length; i++) {
            root.themes[i].current = root.themes[i].slug === slug;
        }
        root.themes = root.themes.slice();
    }

    function togglePopup() {
        if (popup.open) {
            popup.close();
        } else {
            root.reload();
            popup.open = true;
        }
    }

    function applyTheme(slug) {
        if (!root.bar || root.applying)
            return;
        root.applying = true;
        root.bar.run("omarchy theme set " + Util.shellQuote(slug));
        popup.close();
        reloadTimer.restart();
        Qt.callLater(function () {
            root.applying = false;
        });
    }

    Process {
        id: themesProcess
        command: ["bash", Quickshell.env("HOME") + "/.config/omarchy/plugins/theme-picker/scripts/themes"]
        stdout: StdioCollector {
            waitForEnd: true
            onStreamFinished: {
                var raw = String(text || "").trim();
                if (!raw.length)
                    return;
                try {
                    var parsed = JSON.parse(raw);
                    if (Array.isArray(parsed)) {
                        root.themes = parsed;
                        var cur = "";
                        for (var i = 0; i < parsed.length; i++) {
                            if (parsed[i].current)
                                cur = parsed[i].slug;
                        }
                        root.currentTheme = cur;
                    }
                } catch (e) {
                    console.warn("theme-picker parse error:", e.message);
                }
            }
        }
        stderr: StdioCollector {
            waitForEnd: true
            onStreamFinished: {
                var raw = String(text || "").trim();
                if (raw.length)
                    console.warn("theme-picker stderr:", raw);
            }
        }
    }

    FileView {
        id: currentThemeFile
        path: Quickshell.env("HOME") + "/.local/state/omarchy/current/theme.name"
        watchChanges: true
        onLoaded: root.updateCurrentTheme(text())
        onLoadFailed: {}
    }

    Timer {
        id: reloadTimer
        interval: 1200
        onTriggered: root.reload()
    }

    WidgetButton {
        id: button
        anchors.fill: parent
        bar: root.bar
        text: root.icon
        hasVisualContent: true
        horizontalMargin: 8.75
        tooltipText: "Themes"
        foreground: popup.open ? Style.hoverStateColor(bar ? bar.foreground : Color.foreground, Color.accent) : (bar ? bar.foreground : Color.foreground)

        onPressed: function (b) {
            if (b === Qt.LeftButton)
                root.togglePopup();
        }
    }

    PopupCard {
        id: popup
        anchorItem: button
        bar: root.bar
        owner: root
        open: false
        triggerMode: "click"
        contentWidth: fittedContentWidth(Style.space(420))
        contentHeight: fittedContentHeight(column.implicitHeight, Style.space(560))

        Column {
            id: column
            width: parent.width
            spacing: Style.spacing.sm

            Item {
                id: headerItem
                width: parent.width
                height: Style.space(22)

                Text {
                    id: headerTitle
                    anchors.left: parent.left
                    anchors.verticalCenter: parent.verticalCenter
                    text: root.icon + "  Themes"
                    color: Color.popups.text
                    font.family: Style.font.family
                    font.pixelSize: Style.font.body
                    font.bold: true
                }

                Text {
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    text: root.currentThemeName
                    color: Color.accent
                    font.family: Style.font.family
                    font.pixelSize: Style.font.bodySmall
                    elide: Text.ElideLeft
                    width: Math.max(1, parent.width - headerTitle.width - Style.space(14))
                    horizontalAlignment: Text.AlignRight
                }
            }

            Rectangle {
                width: parent.width
                height: Style.spacing.hairline
                color: Util.alpha(Color.popups.text, 0.12)
            }

            ListView {
                id: themeList
                width: parent.width
                height: Style.space(430)
                model: root.themes
                clip: true
                spacing: Style.space(6)
                boundsBehavior: Flickable.StopAtBounds
                interactive: true

                ScrollBar.vertical: ScrollBar {
                    id: themeScrollBar
                    policy: ScrollBar.AlwaysOn
                    visible: themeList.contentHeight > themeList.height
                    width: Style.space(8)
                    interactive: true

                    contentItem: Rectangle {
                        implicitWidth: Style.space(8)
                        radius: width / 2
                        color: Util.alpha(Color.popups.text, 0.4)
                    }

                    background: Rectangle {
                        implicitWidth: Style.space(8)
                        color: "transparent"
                    }
                }

                delegate: Item {
                    required property var modelData
                    width: themeList.width
                    height: bodyColumn.implicitHeight + Style.space(8)

                    MouseArea {
                        id: cardMouse
                        anchors.fill: parent
                        hoverEnabled: true
                        cursorShape: Qt.PointingHandCursor
                        onClicked: root.applyTheme(modelData.slug)
                    }

                    Rectangle {
                        anchors.fill: parent
                        radius: Style.cornerRadius
                        color: cardMouse.containsMouse ? Style.hoverFillFor(Color.popups.text, Color.accent) : "transparent"
                        border.width: modelData.current ? Math.max(1, Style.normalBorderWidth) : (cardMouse.containsMouse ? Style.hoverBorderWidth : 0)
                        border.color: modelData.current ? Color.accent : (cardMouse.containsMouse ? Style.hoverBorderFor(Color.popups.text, Color.accent) : "transparent")
                    }

                    Column {
                        id: bodyColumn
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: Style.space(4)
                        spacing: Style.space(5)

                        Rectangle {
                            id: preview
                            width: parent.width
                            height: Style.space(118)
                            radius: Style.cornerRadius
                            clip: true
                            color: Util.alpha(Color.popups.text, 0.06)
                            border.width: Style.spacing.hairline
                            border.color: Util.alpha(Color.popups.text, 0.12)

                            Image {
                                anchors.fill: parent
                                source: modelData.preview ? Util.fileUrl(modelData.preview) : ""
                                fillMode: Image.PreserveAspectCrop
                                smooth: true
                                mipmap: true
                                sourceSize: Qt.size(preview.width * 2, preview.height * 2)
                            }

                            Rectangle {
                                id: badge
                                visible: modelData.current
                                anchors.top: parent.top
                                anchors.right: parent.right
                                anchors.margins: Style.space(5)
                                height: Style.space(18)
                                width: badgeRow.width + Style.space(12)
                                radius: height / 2
                                color: Color.accent

                                Row {
                                    id: badgeRow
                                    anchors.centerIn: parent
                                    spacing: Style.space(4)

                                    Text {
                                        text: ""
                                        color: Color.background
                                        font.family: Style.font.family
                                        font.pixelSize: Style.font.caption
                                    }

                                    Text {
                                        text: "Current"
                                        color: Color.background
                                        font.family: Style.font.family
                                        font.pixelSize: Style.font.caption
                                        font.bold: true
                                    }
                                }
                            }
                        }

                        Text {
                            text: modelData.name
                            color: modelData.current ? Color.accent : Color.popups.text
                            font.family: Style.font.family
                            font.pixelSize: Style.font.body
                            font.bold: modelData.current
                            elide: Text.ElideRight
                            width: parent.width
                        }

                        Row {
                            width: parent.width
                            spacing: Style.space(5)

                            Repeater {
                                model: colorList(modelData.colors)

                                delegate: Rectangle {
                                    required property var modelData
                                    width: Style.space(13)
                                    height: Style.space(13)
                                    radius: Style.space(3)
                                    color: root.colorFor(modelData, "transparent")
                                    border.width: Style.spacing.hairline
                                    border.color: Util.alpha(Color.popups.text, 0.3)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Component.onCompleted: {
        Qt.callLater(function () {
            root.reload();
            currentThemeFile.reload();
        });
    }
}
