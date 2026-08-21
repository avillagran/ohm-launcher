import QtQuick
import Quickshell
import Quickshell.Io
import qs.Commons
import qs.Ui
import "CalendarModel.js" as CalendarModel

Panel {
  id: root
  moduleName: "calendar-activity"
  ipcTarget: "calendar-activity"
  manageIpc: false

  property date today: new Date()
  readonly property string todayKey: CalendarModel.keyForDate(today)

  property int viewYear: today.getFullYear()
  property int viewMonth: today.getMonth()

  readonly property date viewDate: new Date(viewYear, viewMonth, 1)
  readonly property bool viewingCurrentMonth: viewYear === today.getFullYear() && viewMonth === today.getMonth()

  readonly property real yearDone: CalendarModel.yearProgress(today.getFullYear(), today.getMonth(), today.getDate())
  readonly property int yearDonePercent: CalendarModel.yearProgressPercent(today.getFullYear(), today.getMonth(), today.getDate())

  readonly property int weekStart: CalendarModel.normalizedWeekStart(setting("weekStartDay", null), Qt.locale().firstDayOfWeek)
  readonly property string nextWeekStartLabel: Qt.locale().dayName(CalendarModel.toggledWeekStart(weekStart), Locale.LongFormat)
  readonly property var weekdays: CalendarModel.weekdayOrder(weekStart)

  property var panelEvents: []
  property bool eventsLoaded: false
  property bool hasEventsToday: false

  property string selectedDayKey: ""
  property var selectedDayEvents: []

  property bool addingEvent: false
  property bool newEventNotify: false

  property color contentForeground: bar ? bar.foreground : Color.foreground
  readonly property string contentFontFamily: bar ? bar.fontFamily : Style.font.family

  readonly property int panelWidth: 680
  // readonly property int panelWidth: Math.min(600, panel.screenW * 0.4)

  // readonly property int panelWidth: Math.max(
  //   Style.space(560),
  //   Math.min(Style.space(1080), panel.screenW * 0.4))

  readonly property int cellWidth: Style.space(52)
  readonly property int cellHeight: Style.space(34)
  readonly property int cellSpacing: Style.space(2)
  readonly property int weekColumnWidth: Style.space(32)
  readonly property int gutterWidth: Style.space(14)

  readonly property var weeks: CalendarModel.monthGrid(viewYear, viewMonth, weekStart, todayKey, panelEvents)

  readonly property string icon: "\uf073"

  implicitWidth: button.implicitWidth
  implicitHeight: button.implicitHeight

  function open() {
    refresh()
    root.controller.show()
    root.selectedDayKey = root.todayKey
    root.updateSelectedDayEvents()
    root.startAddEvent()
    Qt.callLater(function() {
      if (root.opened) setCenterHoverRevealSuppressed(true)
    })
  }

  function close() {
    setCenterHoverRevealSuppressed(false)
    if (root.addingEvent) root.cancelAddEvent()
    root.controller.hide()
  }

  function toggle() {
    if (root.opened) root.close()
    else root.open()
  }

  function switchPanel(direction) {
    if (root.bar && typeof root.bar.switchPanelFrom === "function")
      return root.bar.switchPanelFrom(root, direction)
    return false
  }

  function setCenterHoverRevealSuppressed(value) {
    if (root.bar && "centerHoverRevealSuppressed" in root.bar)
      root.bar.centerHoverRevealSuppressed = value
  }

  function refresh() {
    root.today = new Date()
    root.goToToday()
    updateHasEventsToday()
  }

  function goToToday() {
    root.viewYear = today.getFullYear()
    root.viewMonth = today.getMonth()
    root.selectedDayKey = ""
  }

  function formatSelectedHeroLabel() {
    var parts = root.selectedDayKey.split("-")
    if (parts.length !== 3) return ""
    return Qt.formatDate(new Date(parseInt(parts[0]), parseInt(parts[1]) - 1, parseInt(parts[2])), "MMMM d")
  }

  function moveMonth(delta) {
    var next = CalendarModel.stepMonth(viewYear, viewMonth, delta)
    root.viewYear = next.year
    root.viewMonth = next.month
    root.selectedDayKey = ""
  }

  function moveYear(delta) {
    moveMonth(delta * 12)
  }

  function weekdayLabel(weekday) {
    return String(Qt.locale().dayName(weekday, Locale.ShortFormat)).replace(/\.$/, "").toUpperCase()
  }

  function selectDay(dayKey) {
    if (root.selectedDayKey === dayKey) {
      root.selectedDayKey = ""
      return
    }
    root.selectedDayKey = dayKey
    root.updateSelectedDayEvents()
    Qt.callLater(function() {
      calendarScroll.contentY = calendarScroll.contentHeight - calendarScroll.height
    })
  }

  function updateSelectedDayEvents() {
    root.selectedDayEvents = CalendarModel.eventsForDay(panelEvents, selectedDayKey)
  }

  function updateHasEventsToday() {
    var todaysEvents = CalendarModel.eventsForDay(panelEvents, root.todayKey)
    root.hasEventsToday = todaysEvents.length > 0
  }

  function startAddEvent() {
    root.addingEvent = true
    titleField.text = ""
    timeField.text = ""
    root.newEventNotify = false
    Qt.callLater(function() {
      titleField.forceActiveFocus()
    })
  }

  function openEventsZone() {
    if (root.selectedDayKey === "") root.selectDay(root.todayKey)
    root.startAddEvent()
    Qt.callLater(function() {
      calendarScroll.contentY = calendarScroll.contentHeight - calendarScroll.height
    })
  }

  function cancelAddEvent() {
    root.addingEvent = false
    Qt.callLater(function() { if (keyCatcher) keyCatcher.forceActiveFocus() })
  }

  function saveNewEvent() {
    var title = String(titleField.text).trim()
    if (!title) return

    var event = {
      id: CalendarModel.generateId(),
      dateKey: root.selectedDayKey,
      title: title,
      time: CalendarModel.normalizeTime(String(timeField.text)) || "",
      notify: root.newEventNotify,
      notified: false,
      createdAt: Date.now()
    }

    root.panelEvents = panelEvents.concat([event])
    root.saveEvents()
    root.addingEvent = false
    root.updateSelectedDayEvents()
    root.updateHasEventsToday()
  }

  function deleteEvent(eventId) {
    root.panelEvents = panelEvents.filter(function(e) { return e.id !== eventId })
    root.saveEvents()
    root.updateSelectedDayEvents()
    root.updateHasEventsToday()
  }

  function persistSettings(values) {
    var entry = { id: root.moduleName }
    for (var existing in root.settings) if (existing !== "id") entry[existing] = root.settings[existing]
    for (var key in values) entry[key] = values[key]

    root.settings = entry
    if (root.bar && root.bar.shell && typeof root.bar.shell.updateEntryInline === "function")
      root.bar.shell.updateEntryInline(root.moduleName, entry)
  }

  function setWeekStart(day) {
    var next = CalendarModel.normalizedWeekStart(day, root.weekStart)
    if (next === root.weekStart) return
    persistSettings({ weekStartDay: CalendarModel.weekStartSettingName(next) })
  }

  function toggleWeekStart() {
    setWeekStart(CalendarModel.toggledWeekStart(root.weekStart))
  }

  function setWeekStartName(index) {
    return CalendarModel.weekStartSettingName ? CalendarModel.weekStartSettingName(index) : String(index)
  }

  function loadEvents(raw) {
    if (root.eventsLoaded) return
    root.panelEvents = CalendarModel.parseEvents(raw)
    root.eventsLoaded = true
    root.updateHasEventsToday()
  }

  function saveEvents() {
    eventsFile.setText(JSON.stringify(root.panelEvents, null, 2) + "\n")
  }

  function timeToMinutes(timeStr) {
    if (!timeStr) return 0
    var parts = String(timeStr).split(":")
    if (parts.length < 2) return 0
    var h = parseInt(parts[0], 10)
    var m = parseInt(parts[1], 10)
    if (isNaN(h)) h = 0
    if (isNaN(m)) m = 0
    return h * 60 + m
  }

  function checkNotifications() {
    var todayKey = root.todayKey
    var now = new Date()
    var currentMinutes = timeToMinutes(Qt.formatDateTime(now, "HH:mm"))
    var pendingIds = {}

    for (var i = 0; i < panelEvents.length; i++) {
      var ev = panelEvents[i]
      if (ev.dateKey === todayKey && ev.notify && !ev.notified) {
        var normalizedTime = CalendarModel.normalizeTime(ev.time)
        var evMinutes = timeToMinutes(normalizedTime)
        var shouldNotify = ev.time && evMinutes <= currentMinutes
        if (shouldNotify) {
          pendingIds[ev.id] = true
        }
      }
    }

    if (Object.keys(pendingIds).length === 0) return

    for (var id in pendingIds) {
      for (var j = 0; j < panelEvents.length; j++) {
        if (panelEvents[j].id === id) {
          var ev = panelEvents[j]
          var summary = ev.title
          var body = CalendarModel.formatDateKey(ev.dateKey)
          if (ev.time) body += " at " + CalendarModel.normalizeTime(ev.time)
          if (root.bar && root.bar.run) {
            root.bar.run("notify-send -a calendar-activity " + Util.shellQuote(summary) + " " + Util.shellQuote(body))
            root.bar.run("canberra-gtk-play --id=alarm-clock-elapsed")
          }
          ev.notified = true
        }
      }
    }
    root.panelEvents = panelEvents.slice()
    root.saveEvents()
  }

  SystemClock {
    id: clock
    precision: SystemClock.Minutes
    onDateChanged: {
      if (CalendarModel.keyForDate(clock.date) === String(root.todayKey)) return
      var followToday = root.viewingCurrentMonth
      root.today = clock.date
      root.notifyReset()
      if (followToday) root.goToToday()
    }
  }

  function notifyReset() {
    var changed = false
    for (var i = 0; i < panelEvents.length; i++) {
      if (panelEvents[i].notified) {
        panelEvents[i].notified = false
        changed = true
      }
    }
    if (changed) {
      root.panelEvents = panelEvents.slice()
      root.saveEvents()
    }
  }

  FileView {
    id: eventsFile
    path: (Quickshell.env("HOME") || "") + "/.local/state/omarchy/year-calendar-events.json"
    atomicWrites: true
    watchChanges: false
    printErrors: false
    onLoaded: root.loadEvents(text())
    onLoadFailed: root.loadEvents("")
  }

  Timer {
    id: notificationTimer
    interval: 10000
    repeat: true
    running: true
    triggeredOnStart: false
    onTriggered: root.checkNotifications()
  }

  IpcHandler {
    target: "calendar-activity"

    function refresh(): void { root.broadcast("refresh") }
    function open(): void { root.open() }
    function close(): void { root.close() }
    function show(): void { root.open() }
    function hide(): void { root.close() }
    function toggle(): void { root.toggle() }
  }

  BarIconButton {
    id: button
    anchors.fill: parent
    bar: root.bar
    text: root.icon
    active: root.hasEventsToday

    onPressed: function(b) {
      if (b === Qt.LeftButton) root.toggle()
    }
  }

  Component.onCompleted: {
    Qt.callLater(function() { eventsFile.reload() })
  }

  KeyboardPanel {
    id: panel
    anchorItem: button
    owner: root
    bar: root.bar
    open: root.opened
    centerOnBar: true
    focusTarget: keyCatcher
    contentWidth: panel.fittedContentWidth(root.panelWidth)
    contentHeight: panel.fittedContentHeight(calendarColumn.implicitHeight + Style.space(24))

    PanelKeyCatcher {
      id: keyCatcher
      anchors.fill: parent
      blocked: root.addingEvent
      onMoveRequested: function(dx, dy) {
        if (dx !== 0) root.moveMonth(dx)
        if (dy !== 0) root.moveYear(dy)
      }
      onActivateRequested: root.goToToday()
      onCloseRequested: root.close()
      onTabRequested: function(direction) { root.switchPanel(direction) }
      onTextKey: function(t) {
        if (t === "[") root.moveMonth(-1)
        else if (t === "]") root.moveMonth(1)
        else if (t === "{") root.moveYear(-1)
        else if (t === "}") root.moveYear(1)
        else if (t === "t" || t === "T") root.goToToday()
        else if (t === "w" || t === "W") root.toggleWeekStart()
      }

      Flickable {
        id: calendarScroll
        anchors.fill: parent
        contentWidth: calendarColumn.width
        contentHeight: calendarColumn.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        interactive: contentHeight > height || contentWidth > width

        Column {
          id: calendarColumn
          width: Math.max(calendarScroll.width, gridColumn.width)
          spacing: Style.space(8)

          Item {
            width: parent.width
            height: heroRow.height

            Row {
              id: heroRow
              anchors.horizontalCenter: parent.horizontalCenter
              spacing: Style.space(22)

              Text {
                anchors.baseline: heroDate.baseline
                text: "󰃭"
                color: heroMouse.containsMouse
                  ? Style.hoverStateColor(root.contentForeground, Color.accent)
                  : root.contentForeground
                font.family: root.contentFontFamily
                font.pixelSize: 48
              }

              Text {
                id: heroDate
                anchors.verticalCenter: parent.verticalCenter
                text: root.selectedDayKey === ""
                  ? (root.viewingCurrentMonth
                      ? Qt.formatDate(root.today, "MMMM d")
                      : Qt.formatDate(root.viewDate, "MMMM d"))
                  : root.formatSelectedHeroLabel()
                color: heroMouse.containsMouse
                  ? Style.hoverStateColor(root.contentForeground, Color.accent)
                  : root.contentForeground
                font.family: root.contentFontFamily
                font.pixelSize: 52
                font.bold: true
              }
            }

            MouseArea {
              id: heroMouse
              x: heroRow.x
              y: heroRow.y
              width: heroRow.width
              height: heroRow.height
              enabled: !root.viewingCurrentMonth
              hoverEnabled: enabled
              cursorShape: Qt.PointingHandCursor
              onClicked: root.goToToday()

              PanelToolTip {
                visible: heroMouse.containsMouse
                text: "Back to today"
                fontFamily: root.contentFontFamily
              }
            }

            Row {
              id: eventsHint
              anchors.left: heroRow.right
              anchors.leftMargin: Style.space(14)
              anchors.verticalCenter: parent.verticalCenter
              spacing: Style.space(8)

              Item {
                id: eventsButton
                width: Style.space(30)
                height: Style.space(30)

                Rectangle {
                  anchors.fill: parent
                  radius: Style.cornerRadius
                  color: eventsMouse.containsMouse
                    ? Style.hoverFillFor(root.contentForeground, Color.accent)
                    : "transparent"
                }

                Text {
                  anchors.centerIn: parent
                  text: "󰅁"
                  rotation: -90
                  color: eventsMouse.containsMouse
                    ? Style.hoverStateColor(root.contentForeground, Color.accent)
                    : root.contentForeground
                  font.family: root.contentFontFamily
                  font.pixelSize: Style.font.icon
                }

                MouseArea {
                  id: eventsMouse
                  anchors.fill: parent
                  hoverEnabled: true
                  cursorShape: Qt.PointingHandCursor
                  onClicked: root.openEventsZone()
                }

                PanelToolTip {
                  visible: eventsMouse.containsMouse
                  text: "Add or manage events"
                  fontFamily: root.contentFontFamily
                }
              }

              Text {
                anchors.verticalCenter: parent.verticalCenter
                text: "Add event"
                color: eventsMouse.containsMouse
                  ? Style.hoverStateColor(root.contentForeground, Color.accent)
                  : Qt.darker(root.contentForeground, 1.6)
                font.family: root.contentFontFamily
                font.pixelSize: Style.font.bodySmall
              }
            }
          }

          Item {
            width: parent.width
            height: yearBlock.y + yearBlock.height

            Item {
              id: yearBlock
              y: Style.space(6)
              anchors.horizontalCenter: parent.horizontalCenter
              width: gridColumn.width
              height: Math.max(yearLabel.implicitHeight, Style.space(10))

              Text {
                id: yearLabel
                anchors.left: parent.left
                anchors.verticalCenter: parent.verticalCenter
                text: root.today.getFullYear()
                color: Qt.darker(root.contentForeground, 1.5)
                font.family: root.contentFontFamily
                font.pixelSize: Style.font.bodySmall
                font.letterSpacing: 1
              }

              Text {
                id: yearPercent
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                text: root.yearDonePercent + "%"
                color: root.contentForeground
                font.family: root.contentFontFamily
                font.pixelSize: Style.font.bodySmall
              }

              Rectangle {
                anchors.left: yearLabel.right
                anchors.right: yearPercent.left
                anchors.leftMargin: Style.space(12)
                anchors.rightMargin: Style.space(12)
                anchors.verticalCenter: parent.verticalCenter
                height: Style.space(6)
                radius: Style.cornerRadius > 0 ? height / 2 : 0
                color: Qt.rgba(root.contentForeground.r, root.contentForeground.g, root.contentForeground.b, 0.12)

                Rectangle {
                  width: Math.round(parent.width * root.yearDone)
                  height: parent.height
                  radius: parent.radius
                  color: Style.selectedStateColor(root.contentForeground, Color.accent)
                  Behavior on width { NumberAnimation { duration: 160; easing.type: Easing.OutCubic } }
                }
              }
            }
          }

          Item {
            width: parent.width
            height: gridColumn.y + gridColumn.height

            WheelHandler {
              onWheel: function(event) {
                if (event.angleDelta.y === 0) return
                root.moveMonth(event.angleDelta.y > 0 ? -1 : 1)
              }
            }

            Column {
              id: gridColumn
              y: Style.space(18)
              anchors.horizontalCenter: parent.horizontalCenter
              spacing: Style.space(3)

              Row {
                id: headerRow
                spacing: root.cellSpacing

                Rectangle {
                  width: root.weekColumnWidth
                  height: Style.space(16)
                  radius: Style.cornerRadius
                  color: weekStartMouse.containsMouse
                    ? Style.hoverFillFor(root.contentForeground, Color.accent)
                    : "transparent"

                  Text {
                    anchors.centerIn: parent
                    text: "W"
                    color: weekStartMouse.containsMouse
                      ? Style.hoverStateColor(root.contentForeground, Color.accent)
                      : Qt.darker(root.contentForeground, 1.9)
                    font.family: root.contentFontFamily
                    font.pixelSize: Style.font.caption
                    font.letterSpacing: 1
                    font.bold: true
                  }

                  MouseArea {
                    id: weekStartMouse
                    anchors.fill: parent
                    hoverEnabled: true
                    cursorShape: Qt.PointingHandCursor
                    onClicked: root.toggleWeekStart()
                  }

                  PanelToolTip {
                    visible: weekStartMouse.containsMouse
                    text: "Start weeks on " + root.nextWeekStartLabel
                    fontFamily: root.contentFontFamily
                  }
                }

                Item {
                  width: root.gutterWidth
                  height: Style.space(16)
                }

                Repeater {
                  model: root.weekdays

                  Text {
                    required property var modelData
                    width: root.cellWidth
                    height: Style.space(16)
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                    text: root.weekdayLabel(modelData)
                    color: Qt.darker(root.contentForeground, 1.5)
                    font.family: root.contentFontFamily
                    font.pixelSize: Style.font.caption
                    font.letterSpacing: 1
                    font.bold: true
                  }
                }
              }

              Repeater {
                model: root.weeks

                Row {
                  required property var modelData
                  spacing: root.cellSpacing

                  Text {
                    width: root.weekColumnWidth
                    height: root.cellHeight
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                    text: modelData.week
                    color: Qt.darker(root.contentForeground, 1.9)
                    font.family: root.contentFontFamily
                    font.pixelSize: Style.font.caption
                  }

                  Item {
                    width: root.gutterWidth
                    height: root.cellHeight
                  }

                  Repeater {
                    model: modelData.days

                    Item {
                      required property var modelData
                      width: root.cellWidth
                      height: root.cellHeight

                      Rectangle {
                        anchors.fill: parent
                        radius: Style.cornerRadius
                        color: root.selectedDayKey === modelData.key
                          ? Style.hoverFillFor(root.contentForeground, Color.accent)
                          : modelData.today
                            ? Style.hoverFillFor(root.contentForeground, Color.accent)
                            : modelData.hasEvent
                              ? Qt.rgba(Color.urgent.r, Color.urgent.g, Color.urgent.b, 0.15)
                              : "transparent"
                        border.width: (modelData.today || root.selectedDayKey === modelData.key || modelData.hasEvent) ? Style.spacing.hairline : 0
                        border.color: root.selectedDayKey === modelData.key
                          ? "#D3C6AA"
                          : modelData.hasEvent
                            ? Qt.rgba(Color.urgent.r, Color.urgent.g, Color.urgent.b, 0.5)
                            : Style.normalBorderFor(root.contentForeground, Color.accent)

                        Text {
                          anchors.centerIn: parent
                          anchors.verticalCenterOffset: modelData.hasEvent ? -Style.space(2) : 0
                          text: modelData.day
                          color: modelData.inMonth
                            ? (modelData.weekend ? Qt.darker(root.contentForeground, 1.45) : root.contentForeground)
                            : Qt.darker(root.contentForeground, 2.2)
                          font.family: root.contentFontFamily
                          font.pixelSize: Style.font.body
                          font.bold: modelData.today
                        }

                        Rectangle {
                          width: Style.space(4)
                          height: Style.space(4)
                          radius: Style.space(2)
                          anchors.horizontalCenter: parent.horizontalCenter
                          anchors.bottom: parent.bottom
                          anchors.bottomMargin: Style.space(3)
                          color: root.selectedDayKey === modelData.key
                            ? Color.accent
                            : (modelData.hasEvent ? Color.accent : "transparent")
                          opacity: modelData.hasEvent ? 1 : 0
                        }
                      }

                      MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: {
                          if (modelData.inMonth) {
                            root.selectDay(modelData.key)
                          }
                        }
                      }
                    }
                  }
                }
              }
            }

            Rectangle {
              x: gridColumn.x + root.weekColumnWidth + root.cellSpacing + Math.round((root.gutterWidth - width) / 2)
              y: gridColumn.y + headerRow.height + gridColumn.spacing
              width: Style.spacing.hairline
              height: gridColumn.height - headerRow.height - gridColumn.spacing
              color: root.contentForeground
              opacity: 0.1
            }
          }

          Item {
            width: parent.width
            height: monthNav.height

            Item {
              id: monthNav
              anchors.horizontalCenter: parent.horizontalCenter
              width: gridColumn.width
              height: monthLabel.implicitHeight + Style.space(10)

              Text {
                id: monthLabel
                anchors.horizontalCenter: parent.horizontalCenter
                anchors.verticalCenter: parent.verticalCenter
                width: Style.space(130)
                horizontalAlignment: Text.AlignHCenter
                text: Qt.formatDate(root.viewDate, "MMMM yyyy").toUpperCase()
                color: Qt.darker(root.contentForeground, 1.4)
                font.family: root.contentFontFamily
                font.pixelSize: Style.font.body
                font.letterSpacing: 1
              }

              PanelActionButton {
                anchors.left: parent.left
                anchors.leftMargin: -Style.space(8)
                anchors.verticalCenter: parent.verticalCenter
                iconText: "󰅁"
                tooltipText: "Previous month"
                foreground: root.contentForeground
                fontFamily: root.contentFontFamily
                onClicked: root.moveMonth(-1)
              }

              PanelActionButton {
                anchors.right: parent.right
                anchors.rightMargin: -Style.space(8)
                anchors.verticalCenter: parent.verticalCenter
                iconText: "󰅂"
                tooltipText: "Next month"
                foreground: root.contentForeground
                fontFamily: root.contentFontFamily
                onClicked: root.moveMonth(1)
              }
            }
          }

          Item {
            width: parent.width
            height: eventSection.visible ? eventSection.height : 0

            Column {
              id: eventSection
              visible: root.selectedDayKey !== ""
              width: parent.width
              spacing: Style.space(6)

              Rectangle {
                width: parent.width
                height: Style.spacing.hairline
                color: root.contentForeground
                opacity: 0.12
              }

              Text {
                text: CalendarModel.formatDateKey(root.selectedDayKey)
                color: root.contentForeground
                font.family: root.contentFontFamily
                font.pixelSize: Style.font.body
                font.bold: true
                leftPadding: Style.space(4)
              }

              Repeater {
                model: root.selectedDayEvents

                delegate: Item {
                  required property var modelData
                  required property int index
                  width: parent ? parent.width : root.cellWidth * 7
                  height: delegateRow.height + Style.space(4)

                  Row {
                    id: delegateRow
                    anchors.left: parent.left
                    anchors.right: deleteButton.left
                    anchors.rightMargin: Style.space(8)
                    spacing: Style.space(8)

                    Rectangle {
                      width: Style.space(3)
                      height: delegateContent.height
                      radius: Style.space(1.5)
                      color: modelData.notify ? Color.accent : Qt.darker(root.contentForeground, 1.8)
                    }

                    Column {
                      id: delegateContent
                      width: parent.width - Style.space(50)
                      spacing: Style.space(2)

                      Text {
                        text: modelData.title
                        color: root.contentForeground
                        font.family: root.contentFontFamily
                        font.pixelSize: Style.font.body
                        elide: Text.ElideRight
                        maximumLineCount: 1
                      }

                      Row {
                        spacing: Style.space(8)
                        visible: modelData.time || modelData.notify

                        Text {
                          text: modelData.time
                          visible: !!modelData.time
                          color: Qt.darker(root.contentForeground, 1.5)
                          font.family: root.contentFontFamily
                          font.pixelSize: Style.font.bodySmall
                        }

                        Text {
                          text: modelData.notify ? "󰂜" : "󰂛"
                          color: modelData.notify ? Color.accent : Qt.darker(root.contentForeground, 1.8)
                          font.family: root.contentFontFamily
                          font.pixelSize: Style.font.bodySmall
                        }
                      }
                    }
                  }

                  Item {
                    id: deleteButton
                    width: Style.space(24)
                    height: Style.space(24)
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.right: parent.right

                    Rectangle {
                      anchors.fill: parent
                      radius: Style.space(12)
                      color: delBtn.containsMouse
                        ? Qt.rgba(root.contentForeground.r, root.contentForeground.g, root.contentForeground.b, 0.12)
                        : "transparent"
                    }

                    Text {
                      anchors.centerIn: parent
                      text: "\u2715"
                      color: delBtn.containsMouse ? root.contentForeground : Qt.darker(root.contentForeground, 1.5)
                      font.pixelSize: 12
                    }

                    MouseArea {
                      id: delBtn
                      anchors.fill: parent
                      hoverEnabled: true
                      cursorShape: Qt.PointingHandCursor
                      onClicked: root.deleteEvent(modelData.id)
                    }
                  }
                }
              }

              Text {
                visible: root.selectedDayEvents.length === 0 && !root.addingEvent
                text: "No events"
                color: Qt.darker(root.contentForeground, 1.8)
                font.family: root.contentFontFamily
                font.pixelSize: Style.font.bodySmall
                font.italic: true
                leftPadding: Style.space(4)
              }

              Rectangle {
                width: parent.width
                height: Style.spacing.hairline
                color: root.contentForeground
                opacity: 0.08
                visible: !root.addingEvent
              }

              Item {
                width: parent.width
                height: Style.space(28)
                visible: !root.addingEvent

                Rectangle {
                  anchors.fill: parent
                  radius: Style.cornerRadius
                  color: addBtn.containsMouse
                    ? Style.hoverFillFor(root.contentForeground, Color.accent)
                    : "transparent"
                }

                Text {
                  anchors.centerIn: parent
                  text: "+ Add event"
                  color: addBtn.containsMouse
                    ? Style.hoverStateColor(root.contentForeground, Color.accent)
                    : Qt.darker(root.contentForeground, 1.4)
                  font.family: root.contentFontFamily
                  font.pixelSize: Style.font.bodySmall
                  font.letterSpacing: 0.5
                }

                MouseArea {
                  id: addBtn
                  anchors.fill: parent
                  hoverEnabled: true
                  cursorShape: Qt.PointingHandCursor
                  onClicked: root.startAddEvent()
                }
              }

              Column {
                visible: root.addingEvent
                width: parent.width
                spacing: Style.space(8)

                Rectangle {
                  width: parent.width
                  height: Style.spacing.hairline
                  color: root.contentForeground
                  opacity: 0.08
                }

                TextField {
                  id: titleField
                  width: parent.width
                  placeholderText: "Event title"
                  foreground: root.contentForeground
                  font.family: root.contentFontFamily

                  Keys.onPressed: function(event) {
                    if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
                      root.saveNewEvent()
                      event.accepted = true
                    }
                    if (event.key === Qt.Key_Escape) {
                      root.cancelAddEvent()
                      event.accepted = true
                    }
                  }
                }

                Row {
                  spacing: Style.space(8)
                  width: parent.width

                  TextField {
                    id: timeField
                    width: parent.width * 0.5
                    placeholderText: "HH:MM (optional)"
                    foreground: root.contentForeground
                    font.family: root.contentFontFamily
                    inputMethodHints: Qt.ImhTime

                    Keys.onPressed: function(event) {
                      if (event.key === Qt.Key_Return || event.key === Qt.Key_Enter) {
                        root.saveNewEvent()
                        event.accepted = true
                      }
                      if (event.key === Qt.Key_Escape) {
                        root.cancelAddEvent()
                        event.accepted = true
                      }
                    }
                  }

                  Item {
                    width: Style.space(80)
                    height: Style.space(24)

                    Row {
                      spacing: Style.space(6)
                      anchors.verticalCenter: parent.verticalCenter

                      Text {
                        text: "Notify"
                        color: root.contentForeground
                        font.family: root.contentFontFamily
                        font.pixelSize: Style.font.bodySmall
                        anchors.verticalCenter: parent.verticalCenter
                      }

                      Item {
                        width: Style.space(34)
                        height: Style.space(20)
                        anchors.verticalCenter: parent.verticalCenter

                        Rectangle {
                          width: parent.width
                          height: parent.height
                          radius: height / 2
                          color: root.newEventNotify
                            ? Color.accent
                            : Qt.rgba(root.contentForeground.r, root.contentForeground.g, root.contentForeground.b, 0.2)

                          Rectangle {
                            x: root.newEventNotify ? parent.width - width : 0
                            y: (parent.height - height) / 2
                            width: Style.space(16)
                            height: Style.space(16)
                            radius: Style.space(8)
                            color: root.contentForeground
                            Behavior on x { NumberAnimation { duration: 120; easing.type: Easing.OutCubic } }
                          }

                          MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: root.newEventNotify = !root.newEventNotify
                          }
                        }
                      }
                    }
                  }
                }

                Row {
                  spacing: Style.space(8)
                  Text {
                    text: "Save"
                    color: Style.hoverStateColor(root.contentForeground, Color.accent)
                    font.family: root.contentFontFamily
                    font.pixelSize: Style.font.bodySmall
                    font.bold: true
                    padding: Style.space(6)

                    MouseArea {
                      anchors.fill: parent
                      cursorShape: Qt.PointingHandCursor
                      onClicked: root.saveNewEvent()
                    }
                  }

                  Text {
                    text: "Cancel"
                    color: Qt.darker(root.contentForeground, 1.5)
                    font.family: root.contentFontFamily
                    font.pixelSize: Style.font.bodySmall
                    padding: Style.space(6)

                    MouseArea {
                      anchors.fill: parent
                      cursorShape: Qt.PointingHandCursor
                      onClicked: root.cancelAddEvent()
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
