// Verifica el ciclo completo de un plugin QML: descubrimiento en disco,
// validación del contrato y render del entry point .qml con el bridge QML.
import 'dart:io';

import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ohm_launcher/main.dart';

void main() {
  test('Plugin QML de prueba se descubre y renderiza sin error', () async {
    // Carpeta del plugin de ejemplo en el repo.
    final pluginDir = Directory('examples/plugins/io.github.ohm.demo.weather');
    expect(pluginDir.existsSync(), isTrue, reason: 'Falta la carpeta del plugin de ejemplo');

    final plugins = await PluginDiscovery.discover(pluginDir.parent);
    final mine = plugins.where((p) => p.id == 'io.github.ohm.demo.weather').toList();
    expect(mine, isNotEmpty, reason: 'El plugin no fue descubierto');

    final plugin = mine.first;
    expect(plugin.isValid, isTrue,
        reason: 'El plugin tiene errores de validación: ${plugin.validationErrors}');
    expect(plugin.kinds, contains('bar-widget'));

    // El entry point debe resolverse como QML y renderizar sin lanzar.
    final kind = PluginRenderer.entryKindFor(plugin, 'bar-widget');
    expect(kind, EntryKind.qml, reason: 'El entry point no se clasificó como QML');

    final widget = PluginRenderer.renderForKind(plugin, 'bar-widget');
    expect(widget, isA<Widget>());
  });
}
