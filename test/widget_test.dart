import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:ohm_launcher/main.dart';
import 'package:ohm_launcher/qml_bridge/qml_widgets.dart';

void main() {
  testWidgets('Ohm Launcher builds sin crashear', (WidgetTester tester) async {
    await tester.pumpWidget(const OhmApp());
    await tester.pump();

    expect(find.byType(MaterialApp), findsOneWidget);
  });

  test('El motor dinámico interpreta la config por defecto', () {
    final widget = DynamicWidgetEngine.parse(StorageService.kDefaultConfig);
    expect(widget, isA<Widget>());
  });

  test('El motor devuelve un error visual ante JSON inválido', () {
    final widget = DynamicWidgetEngine.parse('{ esto no es json');
    expect(widget, isA<Widget>());
  });

  test('La config multi-escritorio se interpreta como PageView', () {
    final widget = DynamicWidgetEngine.parseDesktop(StorageService.kDefaultConfigMulti);
    expect(widget, isA<Widget>());
    expect(DynamicWidgetEngine.desktopCount(StorageService.kDefaultConfigMulti), 2);
    expect(DynamicWidgetEngine.desktopName(StorageService.kDefaultConfigMulti, 0), 'Inicio');
  });

  test('La config legacy de un escritorio sigue funcionando', () {
    expect(DynamicWidgetEngine.desktopCount(StorageService.kDefaultConfig), 1);
    final widget = DynamicWidgetEngine.parseDesktop(StorageService.kDefaultConfig);
    expect(widget, isA<Widget>());
  });

  test('El bridge QML interpreta el bar-widget de reloj oficial', () {
    const qml = '''
import QtQuick
import Quickshell
import qs.Ui

BarWidget {
  id: root
  moduleName: "test.clock"

  SystemClock {
    id: clock
    precision: SystemClock.Minutes
  }

  WidgetButton {
    id: button
    text: Qt.formatTime(clock.date, "HH:mm")
    tooltipText: "Open Clock"
  }
}
''';
    final res = QmlInterpreter.interpret(source: qml, originDir: '/tmp', originFile: 'BarWidget.qml');
    expect(res.error, isNull);
    expect(res.widget, isA<Widget>());
  });

  test('El bridge QML degrada QML roto sin lanzar', () {
    const qml = 'BarWidget { id: root text: Qt.formatTime( }';
    final res = QmlInterpreter.interpret(source: qml, originDir: '/tmp', originFile: 'broken.qml');
    expect(res.widget, isA<Widget>());
  });

  test('El bridge QML evalúa formatTime con SystemClock', () {
    const qml = '''
BarWidget {
  SystemClock { id: clock }
  WidgetButton { text: Qt.formatTime(clock.date, "HH:mm") }
}
''';
    final res = QmlInterpreter.interpret(source: qml, originDir: '/tmp', originFile: 'BarWidget.qml');
    expect(res.error, isNull);
  });

  testWidgets('El bridge QML renderiza Repeater con modelo de objetos', (tester) async {
    const qml = '''
BarWidget {
  Repeater {
    model: [
      { icon: "a", label: "Region" },
      { icon: "b", label: "Fullscreen" },
    ]
    delegate: BorderSurface { required property var modelData }
  }
}
''';
    final res = QmlInterpreter.interpret(source: qml, originDir: '/tmp', originFile: 'w.qml');
    expect(res.error, isNull);
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: res.widget)));
    await tester.pump();
    expect(tester.takeException(), isNull);
  });

  testWidgets('El bridge QML degrada regex/ternario sin lanzar', (tester) async {
    const qml = r'''
Item {
  property string pluginBin: (root.manifest && root.manifest.__sourceDir)
    ? root.manifest.__sourceDir.replace(/\/$/, "") + "/bin"
    : ""
  onContainsMouseChanged: if (containsMouse) {
    root.cursorActive = true
  }
  Column { Text { text: root.pluginBin } }
}
''';
    final res = QmlInterpreter.interpret(source: qml, originDir: '/tmp', originFile: 'w.qml');
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: res.widget)));
    await tester.pump();
    expect(tester.takeException(), isNull);
  });
}