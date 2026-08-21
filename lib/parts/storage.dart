part of 'package:ohm_launcher/main.dart';

// ============================================================================
//  1. STORAGE — rutas, permisos de almacenamiento y semilla inicial
//  ============================================================================
//  Prioriza la ruta pública /sdcard/OhmLauncher (editable con Acode o
//  cualquier editor). Si los permisos no se conceden, degrada a la carpeta
//  externa privada de la app, que no exige permisos.
// ============================================================================

class StorageService {
  StorageService._();
  static final StorageService instance = StorageService._();

  static const String kPublicRoot = '/sdcard/OhmLauncher';
  static const String kLegacyPublicRoot = '/sdcard/OmarchyLauncher';
  static const String kConfigFileName = 'widgets_config.json';
  static const String kPluginsDirName = 'plugins';
  static const String kFavoritesFileName = 'favorites.json';
  static const String kSettingsFileName = 'settings.json';

  Directory? _baseDir;

  /// Carpeta activa elegida tras [ensureInitialized].
  Directory? get baseDir => _baseDir;

  /// True si se logró usar la ruta pública editable por el usuario.
  bool get usesPublicPath => _baseDir?.path == kPublicRoot;

  String get configPath => '${_baseDir!.path}/$kConfigFileName';
  String get pluginsPath => '${_baseDir!.path}/$kPluginsDirName';
  String get favoritesPath => '${_baseDir!.path}/$kFavoritesFileName';
  String get settingsPath => '${_baseDir!.path}/$kSettingsFileName';
  String get runtimeWidgetsPath => '${_baseDir!.path}/runtime_widgets.json';

  /// Capa de componentes generados en caliente (IA / API). Es un array JSON
  /// de nodos que se renderizan en un overlay flotante y se pueden limpiar
  /// sin tocar la configuración del usuario.
  List<Map<String, dynamic>> loadRuntimeWidgets() {
    try {
      final file = File(runtimeWidgetsPath);
      if (!file.existsSync()) return <Map<String, dynamic>>[];
      final decoded = jsonDecode(file.readAsStringSync());
      if (decoded is List) {
        return decoded.whereType<Map<String, dynamic>>().toList();
      }
    } catch (_) {/* corrupto */}
    return <Map<String, dynamic>>[];
  }

  Future<void> appendRuntimeWidget(Map<String, dynamic> node) async {
    final list = loadRuntimeWidgets();
    list.add(node);
    await File(runtimeWidgetsPath)
        .writeAsString(const JsonEncoder.withIndent('  ').convert(list), flush: true);
  }

  Future<void> clearRuntimeWidgets() async {
    try {
      await File(runtimeWidgetsPath).writeAsString('[]', flush: true);
    } catch (_) {/* noop */}
  }

  /// Inicializa el almacenamiento eligiendo la mejor ruta disponible.
  Future<Directory> ensureInitialized() async {
    if (_baseDir != null) return _baseDir!;

    await _migrateLegacyRoot();

    final publicDir = Directory(kPublicRoot);
    if (await _grantStorageAccess() && await _probeWrite(publicDir)) {
      _baseDir = publicDir;
      return _baseDir!;
    }

    // Degradación: almacenamiento externo privado (sin permisos).
    Directory? appExt;
    try {
      appExt = await getExternalStorageDirectory();
    } catch (_) {/* noop */}
    if (appExt == null) {
      final docs = await getApplicationDocumentsDirectory();
      _baseDir = Directory('${docs.path}/OhmLauncher');
    } else {
      _baseDir = Directory('${appExt.path}/OhmLauncher');
    }
    await _baseDir!.create(recursive: true);
    return _baseDir!;
  }

  /// Migración única: mueve /sdcard/OmarchyLauncher -> /sdcard/OhmLauncher
  /// para no perder plugins, favoritos ni configuración al renombrar.
  Future<void> _migrateLegacyRoot() async {
    try {
      final legacy = Directory(kLegacyPublicRoot);
      final current = Directory(kPublicRoot);
      if (legacy.existsSync() && !current.existsSync()) {
        await legacy.rename(kPublicRoot);
      }
    } catch (_) {/* sin permisos o ya migrado */}
  }

  /// Solicita de forma segura el acceso a /sdcard según la versión de Android.
  Future<bool> _grantStorageAccess() async {
    if (!Platform.isAndroid) return true;
    try {
      // Android 11+ -> All Files Access (MANAGE_EXTERNAL_STORAGE).
      if (await Permission.manageExternalStorage.isGranted) return true;
      if (await Permission.manageExternalStorage.request().isGranted) return true;
      // Android <= 10 -> permisos clásicos.
      return await Permission.storage.request().isGranted;
    } catch (_) {
      return false;
    }
  }

  /// Comprueba que realmente podemos escribir en la ruta antes de usarla.
  Future<bool> _probeWrite(Directory dir) async {
    try {
      await dir.create(recursive: true);
      final probe = File('${dir.path}/.omarchy_probe');
      await probe.writeAsString('ok', flush: true);
      await probe.delete();
      return true;
    } catch (_) {
      return false;
    }
  }

  /// Config por defecto: si no existe widgets_config.json se crea con un
  /// escritorio elegante (fondo oscuro + reloj en vivo + bienvenida).
  static const String kDefaultConfig = '''
{
  "type": "container",
  "color": "#0B0F14",
  "padding": 24,
  "children": [
    {
      "type": "tiling_layout",
      "orientation": "column",
      "mainAxisAlignment": "center",
      "crossAxisAlignment": "center",
      "spacing": 12,
      "children": [
        { "type": "clock", "format": "HH:mm", "fontSize": 76, "color": "#66E0FF", "fontWeight": "w300", "letterSpacing": 4 },
        { "type": "text", "value": "OHM", "fontSize": 14, "color": "#3A4654", "fontWeight": "w600", "letterSpacing": 8 },
        { "type": "text", "value": "Bienvenido al escritorio autogestionado", "fontSize": 16, "color": "#9AA7B4" },
        { "type": "text", "value": "Edita widgets_config.json y guarda: la UI se recarga sola", "fontSize": 12, "color": "#5A6B7A" }
      ]
    }
  ]
}
''';

  /// Garantiza que el archivo de config existe y devuelve su contenido.
  Future<String> ensureConfigFile() async {
    final file = File(configPath);
    if (!await file.exists()) {
      await file.writeAsString(kDefaultConfigMulti, flush: true);
      return kDefaultConfigMulti;
    }
    final existing = await file.readAsString();
    // Migración del formato de escritorio único (legacy) al multi-escritorio.
    if (existing.trim() == kDefaultConfig.trim() ||
        existing.contains('Bienvenido al escritorio autogestionado') &&
            !existing.contains('desktops')) {
      await file.writeAsString(kDefaultConfigMulti, flush: true);
      return kDefaultConfigMulti;
    }
    return existing;
  }

  /// Siembra un plugin de ejemplo (bar-widget con reloj) la primera vez,
  /// replicando el tutorial oficial de Omarchy ("custom-clock") en JSON.
  static const String kSeedPluginId = 'io.github.ohm.demo.clock';

  Future<void> seedExamplePlugin() async {
    final folder = Directory('$pluginsPath/$kSeedPluginId');
    if (await folder.exists()) return;
    await folder.create(recursive: true);
    await File('${folder.path}/manifest.json').writeAsString(_seedManifest, flush: true);
    await File('${folder.path}/BarWidget.json').writeAsString(_seedBarWidget, flush: true);
    await File('${folder.path}/Panel.json').writeAsString(_seedPanel, flush: true);
  }

  static const String _seedManifest = '''
{
  "schemaVersion": 1,
  "id": "io.github.ohm.demo.clock",
  "name": "Ohm Clock",
  "version": "1.0.0",
  "author": "Ohm Launcher",
  "license": "MIT",
  "description": "Demo: bar-widget con reloj y panel de fecha, escrito en el DSL JSON de Ohm Launcher.",
  "kinds": ["bar-widget"],
  "entryPoints": { "barWidget": "BarWidget.json" },
  "barWidget": {
    "displayName": "Reloj",
    "category": "Tiempo",
    "allowMultiple": false,
    "defaultSection": "center"
  }
}
''';

  static const String _seedBarWidget = '''
{
  "type": "container",
  "color": "#12202B",
  "borderRadius": 12,
  "padding": { "left": 14, "top": 8, "right": 14, "bottom": 8 },
  "children": [
    {
      "type": "tiling_layout",
      "orientation": "row",
      "mainAxisAlignment": "center",
      "crossAxisAlignment": "center",
      "spacing": 10,
      "children": [
        { "type": "clock", "format": "HH:mm", "fontSize": 16, "color": "#E8F1F8", "fontWeight": "w600" },
        { "type": "text", "value": "·", "fontSize": 14, "color": "#5A6B7A" },
        { "type": "clock", "format": "dd MMM", "fontSize": 12, "color": "#9AA7B4" }
      ]
    }
  ]
}
''';

  static const String _seedPanel = '''
{
  "type": "container",
  "color": "#10161C",
  "borderRadius": 16,
  "padding": 20,
  "children": [
    {
      "type": "tiling_layout",
      "orientation": "column",
      "mainAxisAlignment": "center",
      "crossAxisAlignment": "center",
      "spacing": 8,
      "children": [
        { "type": "clock", "format": "EEEE, d MMMM yyyy", "fontSize": 16, "color": "#E8F1F8", "fontWeight": "w600" },
        { "type": "clock", "format": "HH:mm:ss", "fontSize": 40, "color": "#66E0FF", "fontWeight": "w300" },
        { "type": "text", "value": "Panel asociado al bar-widget · toca el reloj en el dock", "fontSize": 12, "color": "#7A8A99" }
      ]
    }
  ]
}
''';

  // ---------------------------------------------- favoritos

  /// Claves de apps favoritas (package/activity), persistidas en
  /// favorites.json. Ahora se respeta el orden definido por el usuario.
  List<String> loadFavorites() {
    try {
      final file = File(favoritesPath);
      if (!file.existsSync()) return <String>[];
      final data = jsonDecode(file.readAsStringSync());
      if (data is! List) return <String>[];
      return data.whereType<String>().toList();
    } catch (_) {
      return <String>[];
    }
  }

  Future<void> saveFavorites(List<String> keys) async {
    try {
      await File(favoritesPath)
          .writeAsString(const JsonEncoder.withIndent('  ').convert(keys), flush: true);
    } catch (_) {/* sin permisos: los favoritos no persisten */}
  }

  // ---------------------------------------------- ajustes del launcher

  /// Ajustes de UI del launcher (tipografía, escala, fondo).
  Map<String, dynamic> loadSettings() {
    try {
      final file = File(settingsPath);
      if (!file.existsSync()) return <String, dynamic>{};
      final data = jsonDecode(file.readAsStringSync());
      return data is Map<String, dynamic> ? data : <String, dynamic>{};
    } catch (_) {
      return <String, dynamic>{};
    }
  }

  Future<void> saveSettings(Map<String, dynamic> settings) async {
    try {
      await File(settingsPath)
          .writeAsString(const JsonEncoder.withIndent('  ').convert(settings), flush: true);
    } catch (_) {/* sin permisos */}
  }

  // ---------------------------------------------- escritorios (multi-desktop)

  /// Config por defecto con varios escritorios y widgets, para instalaciones
  /// nuevas y para migrar la configuración antigua de escritorio único.
  static const String kDefaultConfigMulti = '''
{
  "wallpaper": "#0B0F14",
  "desktops": [
    {
      "name": "Inicio",
      "widgets": [
        { "type": "clock", "format": "HH:mm", "fontSize": 56, "color": "#66E0FF", "fontWeight": "w300", "letterSpacing": 4 },
        { "type": "text", "value": "OHM", "fontSize": 14, "color": "#3A4654", "fontWeight": "w600", "letterSpacing": 8 },
        { "type": "text", "value": "Desliza para cambiar de escritorio · mantén pulsado el fondo", "fontSize": 14, "color": "#9AA7B4" }
      ]
    },
    {
      "name": "Sistema",
      "widgets": [
        { "type": "clock", "format": "HH:mm:ss", "fontSize": 36, "color": "#E8F1F8", "fontWeight": "w300" },
        { "type": "battery" }
      ]
    }
  ]
}
''';

  /// Lee el JSON actual de la config (mapa mutable) o null si no es objeto.
  Map<String, dynamic>? readConfigMap() {
    try {
      final file = File(configPath);
      if (!file.existsSync()) return null;
      final data = jsonDecode(file.readAsStringSync());
      return data is Map<String, dynamic> ? data : null;
    } catch (_) {
      return null;
    }
  }

  Future<void> writeConfigMap(Map<String, dynamic> map) async {
    await File(configPath)
        .writeAsString(const JsonEncoder.withIndent('  ').convert(map), flush: true);
  }

  /// Inserta/agrega un escritorio en la posición dada (índice relativo).
  Future<void> addDesktop({required int index}) async {
    final map = readConfigMap();
    if (map == null) return;
    final desktops = _desktopsOf(map);
    final template = desktops.isNotEmpty ? desktops[index.clamp(0, desktops.length - 1)] : null;
    final newDesktop = <String, dynamic>{
      'name': 'Escritorio ${desktops.length + 1}',
      'widgets': template is Map<String, dynamic> && template['widgets'] is List
          ? (template['widgets'] as List).toList()
          : <Object?>[],
    };
    desktops.insert(index.clamp(0, desktops.length), newDesktop);
    await writeConfigMap(map);
  }

  /// Agrega un widget al escritorio [index].
  Future<void> addWidgetToDesktop(int index, Map<String, dynamic> widgetNode) async {
    final map = readConfigMap();
    if (map == null) return;
    final desktops = _desktopsOf(map);
    if (index < 0 || index >= desktops.length) return;
    final desktop = desktops[index];
    final widgets = desktop['widgets'];
    if (widgets is! List) return;
    widgets.add(widgetNode);
    await writeConfigMap(map);
  }

  /// Número de escritorios en la config (1 si no hay array).
  static int desktopCountOf(Map<String, dynamic> map) {
    final d = map['desktops'];
    return d is List && d.isNotEmpty ? d.length : 1;
  }

  /// Muta la lista de widgets de un escritorio y persiste.
  Future<void> mutateDesktopWidgets(
    int index,
    List<dynamic> Function(List<dynamic>) fn,
  ) async {
    final map = readConfigMap();
    if (map == null) return;
    final desktops = _desktopsOf(map);
    if (index < 0 || index >= desktops.length) return;
    final desktop = desktops[index];
    if (desktop is! Map<String, dynamic>) return;
    final widgets = desktop['widgets'];
    if (widgets is! List) return;
    desktop['widgets'] = fn(widgets);
    await writeConfigMap(map);
  }

  /// Lista las cajas de borde configuradas.
  static List<Map<String, dynamic>> edgeBoxesOf(Map<String, dynamic> map) {
    final boxes = map['edgeBoxes'];
    if (boxes is! List) return <Map<String, dynamic>>[];
    return boxes.whereType<Map<String, dynamic>>().toList();
  }

  /// Añade una caja de borde vacía y devuelve su índice.
  Future<int> addEdgeBox({required String edge}) async {
    final map = readConfigMap() ?? <String, dynamic>{};
    final boxes = map['edgeBoxes'] is List ? List<Map<String, dynamic>>.from(map['edgeBoxes'] as List) : <Map<String, dynamic>>[];
    boxes.add({
      'id': 'box_${DateTime.now().millisecondsSinceEpoch}',
      'name': 'Caja ${boxes.length + 1}',
      'edge': edge,
      'direction': edge == 'left' || edge == 'right' ? 'vertical' : 'horizontal',
      'visible': true,
      'items': <Map<String, dynamic>>[],
    });
    map['edgeBoxes'] = boxes;
    await writeConfigMap(map);
    return boxes.length - 1;
  }

  /// Actualiza una caja de borde por índice.
  Future<void> updateEdgeBox(int index, Map<String, dynamic> Function(Map<String, dynamic>) fn) async {
    final map = readConfigMap();
    if (map == null) return;
    final boxes = map['edgeBoxes'];
    if (boxes is! List || index < 0 || index >= boxes.length) return;
    final box = boxes[index];
    if (box is! Map<String, dynamic>) return;
    boxes[index] = fn(box);
    await writeConfigMap(map);
  }

  /// Elimina una caja de borde por índice.
  Future<void> deleteEdgeBox(int index) async {
    final map = readConfigMap();
    if (map == null) return;
    final boxes = map['edgeBoxes'];
    if (boxes is! List || index < 0 || index >= boxes.length) return;
    boxes.removeAt(index);
    await writeConfigMap(map);
  }

  static List<dynamic> _desktopsOf(Map<String, dynamic> map) {
    final d = map['desktops'];
    return d is List ? d : <dynamic>[];
  }
}
