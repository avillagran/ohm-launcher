part of 'package:ohm_launcher/main.dart';

// ============================================================================
//  4b. INSTALADOR DE PLUGINS DESDE EL MARKETPLACE
//  ============================================================================
//  Descarga un plugin desde su repositorio de GitHub:
//    1. Localiza manifest.json (raíz, <id>/, plugins/<id>/ o barrido de la
//       GitHub Contents API) — verificado contra repos reales.
//    2. Verifica el contrato (id no reservado, entryPoints presentes).
//    3. Descarga los entry points junto al manifest y los guarda en
//       plugins/<id>/. Los .json se interpretan al instante; los .qml se
//       respetan (contrato) pero no son renderizables en Android.
//    4. Devuelve un resumen; la UI lanza un rescan inmediato.
// ============================================================================

class PluginInstaller {
  PluginInstaller._();

  static Future<InstallResult> installFromGitHub(String repoUrl, {String expectedId = ''}) async {
    final match = RegExp(r'github\.com/([^/]+)/([^/]+)').firstMatch(repoUrl);
    if (match == null) {
      return const InstallResult.error('URL de GitHub no válida.');
    }
    final owner = match.group(1)!;
    final repo = match.group(2)!.replaceAll(RegExp(r'\.git$'), '');

    for (final branch in const ['main', 'master']) {
      final plan = await PluginRemoteFetcher.findManifest(
        owner: owner,
        repo: repo,
        branch: branch,
        expectedId: expectedId,
      );
      if (plan == null) continue;

      final data = plan.data;
      final id = data['id'] is String ? data['id'] as String : '';
      if (id.isEmpty) {
        return const InstallResult.error('manifest.json sin campo "id".');
      }
      if (id.toLowerCase().startsWith('omarchy.')) {
        return InstallResult.error('El id "$id" usa el prefijo reservado "omarchy.".');
      }
      final entryPoints = data['entryPoints'];
      if (entryPoints is! Map) {
        return const InstallResult.error('manifest.json sin "entryPoints".');
      }

      final folder = Directory('${StorageService.instance.pluginsPath}/$id');
      await folder.create(recursive: true);
      await File('${folder.path}/manifest.json').writeAsString(plan.body, flush: true);

      final base = '${PluginRemoteFetcher.rawBase}/$owner/$repo/$branch';
      var jsonFiles = 0;
      var qmlFiles = 0;
      var missingFiles = 0;

      final candidates = <String>{};
      for (final v in entryPoints.values) {
        if (v is String && v.isNotEmpty) candidates.add(v);
      }
      candidates.add('Panel.json'); // convención del panel asociado.

      for (final f in candidates) {
        if (f.contains('..')) continue;
        final remotePath = plan.manifestDir.isEmpty ? f : '${plan.manifestDir}/$f';
        final body = await HttpUtil.fetch('$base/$remotePath');
        if (body == null) {
          missingFiles++;
          continue;
        }
        await File('${folder.path}/$f').writeAsString(body, flush: true);
        if (f.endsWith('.json')) {
          jsonFiles++;
        } else {
          qmlFiles++;
        }
      }

      return InstallResult.success(
        pluginId: id,
        branch: branch,
        jsonFiles: jsonFiles,
        qmlFiles: qmlFiles,
        missingFiles: missingFiles,
      );
    }

    return const InstallResult.error(
      'No se encontró manifest.json en las ramas main/master del repositorio.',
    );
  }
}

class InstallResult {
  const InstallResult.success({
    required this.pluginId,
    required this.branch,
    required this.jsonFiles,
    required this.qmlFiles,
    required this.missingFiles,
  })  : success = true,
        error = null;

  const InstallResult.error(String this.error)
      : success = false,
        pluginId = null,
        branch = '',
        jsonFiles = 0,
        qmlFiles = 0,
        missingFiles = 0;

  final bool success;
  final String? error;
  final String? pluginId;
  final String branch;
  final int jsonFiles;
  final int qmlFiles;
  final int missingFiles;

  String describe() {
    if (!success) return 'Error: $error';
    return 'Instalado $pluginId desde "$branch" · $jsonFiles JSON · $qmlFiles QML (interpretado por el bridge)';
  }
}
