// Verifica que el parser QML procesa archivos QML reales de plugins.
// Uso: dart run tool/qml_parse_check.dart <archivo.qml> [...]
import 'dart:io';

import 'package:ohm_launcher/qml_bridge/qml_parser.dart';

Future<void> main(List<String> args) async {
  if (args.isEmpty) {
    stderr.writeln('Uso: dart run tool/qml_parse_check.dart <archivo.qml>');
    exit(2);
  }
  var failed = 0;
  for (final path in args) {
    final src = File(path).readAsStringSync();
    final doc = QmlParser(src).parse();
    if (doc.error != null) {
      failed++;
      stderr.writeln('FALLO  $path: ${doc.error}');
    } else {
      final root = doc.elements.isEmpty ? '<ninguno>' : doc.elements.first.type;
      final count = doc.elements.length;
      stdout.writeln('OK     $path  (raíz: $root, $count elemento(s))');
    }
  }
  exit(failed == 0 ? 0 : 1);
}