// ============================================================================
//  PLUGIN NETWORK — red pura de Dart (sin dependencias de Flutter)
//  ============================================================================
//  Localiza y descarga el contenido de un plugin Omarchy desde un repositorio
//  de GitHub. Mantiene el contrato oficial (manifest.json + entry points) y
//  se usa tanto desde la app como desde tool/installer_check.dart para
//  verificar la lógica sin ejecutar Flutter.
// ============================================================================

import 'dart:convert';
import 'dart:io';

class HttpUtil {
  HttpUtil._();

  static Future<String?> fetch(String url, {Duration timeout = const Duration(seconds: 20)}) async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15)
      ..userAgent = 'OmarchyLauncher/1.0';
    try {
      final request = await client.getUrl(Uri.parse(url));
      final response = await request.close().timeout(timeout);
      if (response.statusCode != HttpStatus.ok) return null;
      return await response.transform(utf8.decoder).join();
    } catch (_) {
      return null;
    } finally {
      client.close(force: true);
    }
  }
}

/// Manifest remoto ya localizado, junto con el directorio del repo donde vive.
class RemoteManifest {
  RemoteManifest({required this.body, required this.manifestDir});

  final String body;

  /// Relative path within the repo ('' when the manifest is at the root).
  final String manifestDir;

  Map<String, dynamic> get data => jsonDecode(body) as Map<String, dynamic>;
}

/// Localiza el manifest.json de un plugin en un repositorio de GitHub.
class PluginRemoteFetcher {
  PluginRemoteFetcher._();

  static const String rawBase = 'https://raw.githubusercontent.com';
  static const String apiBase = 'https://api.github.com';

  /// Busca el manifest del plugin cuyo id es [expectedId] en las ramas típicas.
  ///
  /// If [expectedId] is empty, only the repo root is checked (useful for
  /// installing from a URL without knowing the id beforehand).
  static Future<RemoteManifest?> findManifest({
    required String owner,
    required String repo,
    required String branch,
    String expectedId = '',
  }) async {
    // 1) Manifest at the repo root (most common case).
    final rootBody = await HttpUtil.fetch('$rawBase/$owner/$repo/$branch/manifest.json');
    if (rootBody != null) {
      if (expectedId.isEmpty) return RemoteManifest(body: rootBody, manifestDir: '');
      if (_idMatches(rootBody, expectedId)) return RemoteManifest(body: rootBody, manifestDir: '');
    }
    if (expectedId.isEmpty) return null;

    // 2) Candidatos típicos por subcarpeta (sin consumir la API de GitHub).
    for (final path in ['$expectedId/manifest.json', 'plugins/$expectedId/manifest.json']) {
      final body = await HttpUtil.fetch('$rawBase/$owner/$repo/$branch/$path');
      if (body != null && _idMatches(body, expectedId)) {
        return RemoteManifest(body: body, manifestDir: _dirOf(path));
      }
    }

    // 3) Barrido real del repo con la GitHub Contents API (sin token).
    final rootListing = await HttpUtil.fetch('$apiBase/repos/$owner/$repo/contents/?ref=$branch');
    if (rootListing != null) {
      for (final item in _listOf(rootListing)) {
        final name = item['name'];
        final type = item['type'];
        if (name is! String || type is! String || type != 'dir' || name == 'plugins') continue;
        final body = await HttpUtil.fetch('$rawBase/$owner/$repo/$branch/$name/manifest.json');
        if (body != null && _idMatches(body, expectedId)) {
          return RemoteManifest(body: body, manifestDir: name);
        }
      }

      final pluginsListing = await HttpUtil.fetch('$apiBase/repos/$owner/$repo/contents/plugins?ref=$branch');
      if (pluginsListing != null) {
        for (final item in _listOf(pluginsListing)) {
          final name = item['name'];
          final type = item['type'];
          if (name is! String || type is! String || type != 'dir') continue;
          final body = await HttpUtil.fetch('$rawBase/$owner/$repo/$branch/plugins/$name/manifest.json');
          if (body != null && _idMatches(body, expectedId)) {
            return RemoteManifest(body: body, manifestDir: 'plugins/$name');
          }
        }
      }
    }
    return null;
  }

  static bool _idMatches(String manifestBody, String expectedId) {
    try {
      final data = jsonDecode(manifestBody);
      return data is Map<String, dynamic> && data['id'] == expectedId;
    } catch (_) {
      return false;
    }
  }

  static String _dirOf(String path) {
    final i = path.lastIndexOf('/');
    return i < 0 ? '' : path.substring(0, i);
  }

  static List<Map<String, dynamic>> _listOf(String json) {
    final decoded = jsonDecode(json);
    if (decoded is! List) return const [];
    return decoded.whereType<Map<String, dynamic>>().toList();
  }
}