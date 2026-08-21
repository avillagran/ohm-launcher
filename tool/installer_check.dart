// ============================================================================
//  Verificación del instalador de plugins contra repos reales del marketplace.
//  Uso:  dart run tool/installer_check.dart
//  Sale con código != 0 si algún caso falla.
// ============================================================================

import 'dart:io';

import 'package:ohm_launcher/plugin_network.dart';

Future<void> main() async {
  var failures = 0;

  await _case(
    'manifest en la raíz (archlatam/omarchy-calendar-activity)',
    repo: 'https://github.com/archlatam/omarchy-calendar-activity',
    expectedId: 'calendar-activity',
    expectEntry: 'Panel.qml',
    onResult: (r) => failures = failures,
  ).then((ok) {
    if (!ok) failures++;
  });

  await _case(
    'plugin en subcarpeta (bjarneo/omarchy-shell-plugins -> omni)',
    repo: 'https://github.com/bjarneo/omarchy-shell-plugins',
    expectedId: 'omni',
    expectEntry: 'OmniMenu.qml',
  ).then((ok) {
    if (!ok) failures++;
  });

  await _case(
    'id inexistente debe fallar',
    repo: 'https://github.com/archlatam/omarchy-calendar-activity',
    expectedId: 'no-existe-este-id',
    expectEntry: null,
    expectNotFound: true,
  ).then((ok) {
    if (!ok) failures++;
  });

  stdout.writeln(failures == 0 ? '\nOK: todos los casos pasaron' : '\nFALLOS: $failures');
  exit(failures == 0 ? 0 : 1);
}

Future<bool> _case(
  String label, {
  required String repo,
  required String expectedId,
  required String? expectEntry,
  bool expectNotFound = false,
  void Function(String)? onResult,
}) async {
  final m = RegExp(r'github\.com/([^/]+)/([^/]+)').firstMatch(repo);
  final owner = m!.group(1)!;
  final repoName = m.group(2)!.replaceAll(RegExp(r'\.git$'), '');

  stdout.writeln('\n== $label ==');
  for (final branch in const ['main', 'master']) {
    final plan = await PluginRemoteFetcher.findManifest(
      owner: owner,
      repo: repoName,
      branch: branch,
      expectedId: expectedId,
    );
    if (plan == null) continue;

    stdout.writeln('  branch: $branch · manifestDir: "${plan.manifestDir}"');
    final data = plan.data;
    final id = data['id'];
    stdout.writeln('  id: $id');
    final entries = data['entryPoints'];
    if (id != expectedId) {
      if (expectNotFound) {
        stdout.writeln('  OK: no era nuestro plugin (id=$id)');
        return true;
      }
      stdout.writeln('  ERROR: id inesperado ($id)');
      return false;
    }
    if (entries is! Map) {
      stdout.writeln('  ERROR: sin entryPoints');
      return false;
    }

    final base = '${PluginRemoteFetcher.rawBase}/$owner/$repoName/$branch';
    var ok = true;
    for (final v in entries.values) {
      if (v is! String || v.isEmpty) continue;
      final remotePath = plan.manifestDir.isEmpty ? v : '${plan.manifestDir}/$v';
      final body = await HttpUtil.fetch('$base/$remotePath');
      final status = body == null ? 'NO ENCONTRADO' : 'descargado (${body.length} B)';
      stdout.writeln('  entry $v -> $status');
      if (body == null) ok = false;
      if (expectEntry != null && v == expectEntry && body == null) ok = false;
    }

    // Convención Panel.json junto al bar-widget.
    final panelPath = plan.manifestDir.isEmpty ? 'Panel.json' : '${plan.manifestDir}/Panel.json';
    final panelBody = await HttpUtil.fetch('$base/$panelPath');
    stdout.writeln('  Panel.json (convención) -> ${panelBody == null ? 'no existe (ok)' : 'presente (${panelBody.length} B)'}');

    stdout.writeln(ok ? '  OK: instalación simulada lista' : '  ERROR: faltan archivos');
    return ok;
  }
  stdout.writeln(expectNotFound ? '  OK: no encontrado (esperado)' : '  ERROR: manifest no encontrado');
  return expectNotFound;
}