part of 'package:ohm_launcher/main.dart';

// ============================================================================
//  4. SISTEMA DE PLUGINS — CONTRATO OMARCHY (omarchyplugins.com)
//  ============================================================================
//  Replica la especificación oficial de plugins:
//
//    manifest.json:
//      { "schemaVersion": 1,
//        "id": "tu.nombre.plugin",        // terceros NO usan prefijo "omarchy."
//        "name", "version", "author", "license", "description",
//        "kinds": ["bar-widget"|"panel"|"overlay"|"menu"|"service"|"bar"],
//        "entryPoints": { "barWidget": "BarWidget.json", ... } }
//
//  Reglas de validación replicadas:
//    - schemaVersion == 1
//    - kind y entry point coherentes (bar-widget -> entryPoints.barWidget)
//    - todo entry point existe y es una ruta relativa segura (sin "..")
//    - sin symlinks dentro de la carpeta del plugin
//    - IDs de terceros prohibidos con prefijo "omarchy."
// ============================================================================

/// Manifest de un plugin Omarchy ya parseado y tipado.
class OhmManifest {
  OhmManifest({
    required this.schemaVersion,
    required this.id,
    required this.name,
    required this.version,
    required this.author,
    required this.license,
    required this.description,
    required this.kinds,
    required this.entryPoints,
    required this.barWidget,
  });

  factory OhmManifest.parse(Map<String, dynamic> data) {
    return OhmManifest(
      schemaVersion: (data['schemaVersion'] as num?)?.toInt() ?? 0,
      id: data['id'] is String ? data['id'] as String : '',
      name: data['name'] is String ? data['name'] as String : '',
      version: data['version'] is String ? data['version'] as String : '',
      author: data['author'] is String ? data['author'] as String : '',
      license: data['license'] is String ? data['license'] as String : '',
      description: data['description'] is String ? data['description'] as String : '',
      kinds: data['kinds'] is List ? data['kinds']!.whereType<String>().toList() : const [],
      entryPoints: _strMap(data['entryPoints']),
      barWidget: data['barWidget'] is Map<String, dynamic>
          ? data['barWidget'] as Map<String, dynamic>
          : null,
    );
  }

  final int schemaVersion;
  final String id;
  final String name;
  final String version;
  final String author;
  final String license;
  final String description;
  final List<String> kinds;
  final Map<String, String> entryPoints;
  final Map<String, dynamic>? barWidget;

  static Map<String, String> _strMap(Object? v) {
    if (v is! Map) return const {};
    return v.map((k, value) => MapEntry('$k', value is String ? value : ''));
  }
}

/// Un plugin descubierto en disco, junto con sus errores de validación.
class OhmPlugin {
  const OhmPlugin({
    required this.id,
    required this.folder,
    required this.manifest,
    required this.validationErrors,
  });

  final String id;
  final Directory folder;
  final OhmManifest? manifest;
  final List<String> validationErrors;

  bool get isValid => manifest != null && validationErrors.isEmpty;
  List<String> get kinds => manifest?.kinds ?? const [];
  String get statusLabel => isValid ? 'activo' : validationErrors.first;

  /// Entry point asociado a un kind ("bar-widget" -> "barWidget").
  File? entryFileForKind(String kind) {
    final key = _kindToEntryKey(kind);
    if (key == null) return null;
    final fileName = manifest?.entryPoints[key];
    if (fileName == null || fileName.isEmpty) return null;
    return File('${folder.path}/$fileName');
  }

  /// Entry point del panel asociado (convención: Panel.json o Panel.qml
  /// junto al bar-widget).
  File? panelFile() {
    for (final name in const ['Panel.json', 'Panel.qml']) {
      final f = File('${folder.path}/$name');
      if (f.existsSync()) return f;
    }
    return null;
  }
}

String? _kindToEntryKey(String kind) => PluginDiscovery.kKindToEntryKey[kind];

/// Descubrimiento, validación y hot-reload de plugins (equivale a
/// `omarchy-shell shell rescanPlugins`).
class PluginDiscovery {
  PluginDiscovery._();

  static const int kSchemaVersion = 1;

  static const Map<String, String> kKindToEntryKey = {
    'bar-widget': 'barWidget',
    'panel': 'panel',
    'overlay': 'overlay',
    'menu': 'menu',
    'service': 'service',
    'bar': 'bar',
  };

  /// Escanea [root]/plugins y devuelve todos los plugins (válidos o no).
  static Future<List<OhmPlugin>> discover(Directory root) async {
    if (!await root.exists()) return const [];
    final plugins = <OhmPlugin>[];

    await for (final entity in root.list()) {
      if (entity is! Directory) continue;
      final folder = entity;
      final id = _basename(folder.path);
      final errors = <String>[];

      final manifestFile = File('${folder.path}/manifest.json');
      if (!await manifestFile.exists()) {
        errors.add('Falta manifest.json en la carpeta del plugin.');
        plugins.add(OhmPlugin(id: id, folder: folder, manifest: null, validationErrors: errors));
        continue;
      }

      OhmManifest? manifest;
      try {
        final data = jsonDecode(await manifestFile.readAsString());
        if (data is! Map<String, dynamic>) {
          throw const FormatException('manifest.json debe ser un objeto JSON');
        }
        manifest = OhmManifest.parse(data);
      } catch (e) {
        errors.add('manifest.json no válido: $e');
      }

      if (manifest != null) {
        errors.addAll(_validate(folder, manifest));
      }

      plugins.add(OhmPlugin(id: id, folder: folder, manifest: manifest, validationErrors: errors));
    }

    plugins.sort((a, b) => a.id.toLowerCase().compareTo(b.id.toLowerCase()));
    return plugins;
  }

  /// Aplica las reglas de validación del contrato Omarchy.
  static List<String> _validate(Directory folder, OhmManifest m) {
    final errors = <String>[];

    if (m.schemaVersion != kSchemaVersion) errors.add('schemaVersion debe ser 1.');
    if (m.id.isEmpty) errors.add('El campo "id" es obligatorio.');
    if (m.id.toLowerCase().startsWith('omarchy.')) {
      errors.add('Los IDs de terceros no pueden usar el prefijo reservado "omarchy.".');
    }
    if (m.name.isEmpty) errors.add('El campo "name" es obligatorio.');
    if (m.version.isEmpty) errors.add('El campo "version" es obligatorio.');
    if (m.author.isEmpty) errors.add('El campo "author" es obligatorio.');
    if (m.kinds.isEmpty) errors.add('El campo "kinds" no puede estar vacío.');

    for (final kind in m.kinds) {
      final key = kKindToEntryKey[kind];
      if (key == null) {
        errors.add('Kind desconocido: "$kind".');
        continue;
      }
      final entry = m.entryPoints[key];
      if (entry == null || entry.isEmpty) {
        errors.add('El kind "$kind" requiere entryPoints.$key.');
        continue;
      }
      if (entry.contains('..')) {
        errors.add('entryPoints.$key contiene rutas inseguras ("..").');
        continue;
      }
      if (!File('${folder.path}/$entry').existsSync()) {
        errors.add('Entry point no encontrado: "$entry".');
      }
    }

    for (final e in folder.listSync(followLinks: false)) {
      if (e is Link) errors.add('El plugin contiene un symlink: ${_basename(e.path)}');
    }

    return errors;
  }
}

/// Renderiza el entry point de un plugin con el motor dinámico.
class PluginRenderer {
  PluginRenderer._();

  /// Clasifica el entry point de un kind para decidir cómo renderizarlo.
  static EntryKind entryKindFor(OhmPlugin plugin, String kind) {
    final file = plugin.entryFileForKind(kind);
    if (file == null || !file.existsSync()) return EntryKind.missing;
    return file.path.endsWith('.json') ? EntryKind.json : EntryKind.qml;
  }

  /// Carga el archivo del kind indicado y lo convierte en Widget.
  /// JSON se interpreta con el motor dinámico; QML con el bridge QML.
  static Widget renderForKind(OhmPlugin plugin, String kind) {
    final file = plugin.entryFileForKind(kind);
    if (file == null || !file.existsSync()) {
      return _ConfigErrorCard(
        title: 'Entry point faltante',
        message: 'El plugin "${plugin.manifest?.id ?? plugin.id}" no expone un '
            'entry point válido para el kind "$kind".',
        origin: '${plugin.id}/manifest.json',
      );
    }

    final origin = '${plugin.manifest?.id ?? plugin.id}/${_basename(file.path)}';
    if (file.path.endsWith('.json')) {
      return DynamicWidgetEngine.parse(file.readAsStringSync(), origin: origin);
    }
    return QmlInterpreter.interpret(
      source: file.readAsStringSync(),
      originDir: plugin.folder.path,
      originFile: _basename(file.path),
    ).widget;
  }
}

/// Tipos de entry point: JSON o QML (interpretables) o ausente.
enum EntryKind { json, qml, missing }

/// Catálogo del marketplace comunitario (omarchyplugins.com/registry.json).
class MarketplaceEntry {
  const MarketplaceEntry({
    required this.id,
    required this.name,
    required this.description,
    required this.author,
    required this.version,
    required this.category,
    required this.tags,
    required this.repoUrl,
    required this.installCommand,
    required this.installNote,
    required this.isSuite,
  });

  final String id;
  final String name;
  final String description;
  final String author;
  final String version;
  final String category;
  final List<String> tags;
  final String repoUrl;
  final String installCommand;
  final String installNote;
  final bool isSuite;
}

class MarketplaceRegistry {
  MarketplaceRegistry._();

  static const String primaryUrl = 'https://omarchyplugins.com/registry.json';
  static const String fallbackUrl =
      'https://raw.githubusercontent.com/HANCORE-linux/omarchy-plugin-marketplace/main/registry.json';

  /// Descarga y parsea el registro oficial del marketplace.
  static Future<List<MarketplaceEntry>> fetch() async {
    final body =
        await HttpUtil.fetch(primaryUrl) ?? await HttpUtil.fetch(fallbackUrl);
    if (body == null) {
      throw HttpException('No se pudo descargar el registro del marketplace.');
    }
    return _parse(jsonDecode(body));
  }

  static List<MarketplaceEntry> _parse(Object? decoded) {
    final out = <MarketplaceEntry>[];
    if (decoded is! Map<String, dynamic>) return out;
    final sources = decoded['sources'];
    if (sources is! List) return out;

    for (final source in sources) {
      final s = _mapOf(source);
      if (s == null) continue;
      final repo = s['repo'] is String ? s['repo'] as String : '';

      // Suites: tienen un objeto "catalog".
      final catalog = _mapOf(s['catalog']);
      if (catalog != null) {
        out.add(MarketplaceEntry(
          id: _str(catalog['id'], 'suite'),
          name: _str(catalog['name'], _str(catalog['id'], 'Suite')),
          description: _str(catalog['description'], ''),
          author: _str(catalog['author'], ''),
          version: _str(catalog['version'], ''),
          category: _str(catalog['category'], 'Desktop'),
          tags: _strList(catalog['tags']),
          repoUrl: repo,
          installCommand: _str(catalog['installCommand'], ''),
          installNote: _str(catalog['installNote'], ''),
          isSuite: true,
        ));
      }

      // Fuentes de plugins: mapa id -> metadatos.
      final plugins = _mapOf(s['plugins']);
      plugins?.forEach((id, meta) {
        final m = _mapOf(meta);
        final installation = _mapOf(m?['installation']);
        out.add(MarketplaceEntry(
          id: id,
          name: _humanize(id),
          description: _str(m?['description'], 'Plugin comunitario para Omarchy.'),
          author: _str(m?['author'], ''),
          version: _str(m?['version'], ''),
          category: _str(m?['category'], 'Widgets'),
          tags: _strList(m?['tags']),
          repoUrl: repo,
          installCommand: '',
          installNote: _str(installation?['note'], ''),
          isSuite: false,
        ));
      });
    }
    return out;
  }

  static String _humanize(String id) {
    final words = id.replaceAll(RegExp(r'[._-]'), ' ').trim();
    if (words.isEmpty) return id;
    return words.split(' ').map((w) {
      if (w.isEmpty) return w;
      return w[0].toUpperCase() + w.substring(1);
    }).join(' ');
  }
}

Map<String, dynamic>? _mapOf(Object? v) => v is Map<String, dynamic> ? v : null;

String _str(Object? v, String fallback) => v is String ? v : fallback;

List<String> _strList(Object? v) => v is List ? v.whereType<String>().toList() : const [];
