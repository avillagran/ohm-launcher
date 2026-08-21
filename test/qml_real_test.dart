// Verifica el bridge QML contra archivos QML REALES de plugins del
// marketplace: deben interpretarse y renderizar contenido visible sin crashear.
// Los overflows de RenderFlex (franjas amarillas) se consideran advertencias
// de layout y se filtran; cualquier otra excepción hace fallar el test.
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:ohm_launcher/qml_bridge/qml_widgets.dart';

void main() {
  final fixtures = Directory('test/fixtures');

  testWidgets('Dock compacto del calendario muestra solo el icono', (tester) async {
    final file = File('test/fixtures/calendar.qml');
    final src = file.readAsStringSync();

    final originalOnError = FlutterError.onError;
    FlutterError.onError = (details) {
      if (details.exceptionAsString().contains('overflowed')) return;
      originalOnError?.call(details);
    };
    try {
      // Modo compacto (dock): el botón con su icono, sin el panel completo.
      final compact = QmlInterpreter.interpret(
        source: src,
        originDir: file.parent.path,
        originFile: 'calendar.qml',
        compact: true,
      );
      // Mismo contenedor que el tile del dock (52px con FittedBox scaleDown).
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Center(
              child: SizedBox(
                width: 80,
                height: 52,
                child: ClipRect(
                  child: FittedBox(
                    fit: BoxFit.scaleDown,
                    child: compact.widget,
                  ),
                ),
              ),
            ),
          ),
        ),
      );
      await tester.pump();
      // El texto del panel (Add event) NO debe estar en el dock compacto.
      expect(find.textContaining('Add event'), findsNothing);

      // Modo completo (panel al hacer clic): sí muestra el contenido.
      final full = QmlInterpreter.interpret(
        source: src,
        originDir: file.parent.path,
        originFile: 'calendar.qml',
      );
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: SingleChildScrollView(child: full.widget))));
      await tester.pump();
      expect(find.textContaining('Add event'), findsWidgets);
    } finally {
      FlutterError.onError = originalOnError;
    }
  });
  for (final file in fixtures.listSync().whereType<File>().where((f) => f.path.endsWith('.qml'))) {
    final name = file.path.split('/').last;

    testWidgets('Bridge QML renderiza $name sin lanzar', (tester) async {
      final src = file.readAsStringSync();
      final res = QmlInterpreter.interpret(
        source: src,
        originDir: file.parent.path,
        originFile: name,
      );

      // Filtrar overflows (advertencias de layout), propagar lo demás.
      final originalOnError = FlutterError.onError;
      FlutterError.onError = (details) {
        if (details.exceptionAsString().contains('overflowed')) return;
        originalOnError?.call(details);
      };
      try {
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              backgroundColor: const Color(0xFF0B0F14),
              body: SingleChildScrollView(child: res.widget),
            ),
          ),
        );
        await tester.pump();
      } finally {
        FlutterError.onError = originalOnError;
      }

      // Los bar-widgets deben producir elementos visibles; los overlays pueden
      // ser no visuales aislados (cursor-style es un overlay).
      if (name != 'cursor-style.qml') {
        final visible = find.byType(Text).evaluate().length + find.byType(Icon).evaluate().length;
        expect(visible, greaterThan(0), reason: '$name no produce ningún elemento visible');
      }
    });
  }
}