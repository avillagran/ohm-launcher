part of 'package:ohm_launcher/main.dart';

// Accent color palette for the edge boxes.
const _kBoxAccentColors = <String>[
  '#66E0FF',
  '#7EE787',
  '#B48AFF',
  '#F5DE6A',
  '#FFA657',
  '#FF6B7A',
  '#FF87C7',
  '#E8F1F8',
];

// ============================================================================
//  6. MAIN SCREEN — OhmHomeScreen
//  ============================================================================
//  * Reactive desktop rendered from widgets_config.json.
//  * File watcher: instant reload on saving the JSON.
//  * Bottom dock with the installed plugins' bar-widgets.
//  * "Central Command Launcher" (Super+Space): filters simulated apps,
//    installed plugins and the marketplace catalog.
// ============================================================================

class OhmHomeScreen extends StatefulWidget {
  const OhmHomeScreen({super.key});

  @override
  State<OhmHomeScreen> createState() => _OhmHomeScreenState();
}

class _OhmHomeScreenState extends State<OhmHomeScreen> {
  final StorageService _storage = StorageService.instance;
  final FileWatcherEngine _watcher = FileWatcherEngine();

  static bool get _isTestEnvironment => Platform.environment.containsKey('FLUTTER_TEST');

  bool _busy = true;
  String? _bootError;
  Widget _desktop = const SizedBox.shrink();
  String _configSource = '';

  int _desktopIndex = 0;
  int _desktopCount = 1;
  List<String> _favorites = const [];
  int? _editDesktop;
  int? _editWidget;
  Map<String, dynamic> _settings = <String, dynamic>{};
  int _systemNavigationMode = 0;
  bool _accessibilityServiceEnabled = false;
  final ValueNotifier<String?> _barDragTarget = ValueNotifier<String?>(null);
  final ValueNotifier<Color> _boxDragAccent = ValueNotifier<Color>(const Color(0xFF66E0FF));
  final ValueNotifier<String> _boxDragSourceEdge = ValueNotifier<String>('bottom');
  int? _boxDragIndex;
  final ValueNotifier<Offset> _boxDragPos = ValueNotifier<Offset>(Offset.zero);
  List<dynamic>? _originalBoxOrder;
  final Map<int, Rect> _boxRects = <int, Rect>{};
  bool _boxRectsDirty = false;

  /// Layer of components generated hot (AI / local API).
  List<Map<String, dynamic>> _runtimeWidgets = const [];
  bool _aiPanelOpen = false;
  LocalApiServer? _apiServer;
  OmarchyLink? _omarchyLink;
  OmarchyAnnouncer? _announcer;
  ScreenCapture? _screenCapture;
  // Omarchy peer detected via `omarchy://` QR (system camera).
  ({String ip, int port, String id})? _omarchyPeer;

  /// Quake-style terminal: unfolds with a swipe-down from the upper half.
  bool _quakeOpen = false;
  bool _quakeEnabled = true;
  double _quakeStartY = 0;
  double _quakeAccumDy = 0;
  final GlobalKey<_QuakeTerminalState> _quakeTerminalKey = GlobalKey();

  /// Private app folder where own bins are installed (herdr/opencode/
  /// claude…) executables from the embedded shell. Does not depend on Termux.
  String? _binDir;
  String? _appHomeDir;

  /// Live visual shift of the target edge boxes to leave
  /// the gap where the dragged box would fall.
  ({int idx, String edge, int insertAt, Size size})? _liveBoxShift;

  final GlobalKey<_AppDrawerState> _drawerKey = GlobalKey<_AppDrawerState>();

  List<OhmPlugin> _plugins = const [];
  List<MarketplaceEntry> _marketplace = const [];
  List<InstalledApp> _installedApps = const [];
  bool _registryFailed = false;

  @override
  void initState() {
    super.initState();
    _bootstrap();
    DynamicWidgetEngine.onBoxAddContent = _showBoxContentPicker;
    // When scanning an `omarchy://` QR with the system camera, the launcher
    // opens and connects to the peer PC.
    OhmPlatform.onOmarchyPeerLink = (ip, port, id) {
      if (mounted) {
        setState(() => _omarchyPeer = (ip: ip, port: port, id: id));
        _showOmarchyPeerConnected(id, ip, port);
      }
    };
    if (!_isTestEnvironment) {
      _updateSystemNavigationMode();
      // Forces gesture navigation if Xiaomi disabled it and we have permission.
      unawaited(_ensureGestureNavigation());
    }
  }

  @override
  void dispose() {
    _watcher.stop();
    unawaited(_apiServer?.stop());
    _apiServer = null;
    DynamicWidgetEngine.onBoxAddContent = null;
    super.dispose();
  }

  Future<void> _updateSystemNavigationMode() async {
    final mode = await OhmPlatform.getNavigationMode();
    if (mounted) setState(() => _systemNavigationMode = mode);
  }

  Future<void> _ensureGestureNavigation() async {
    // Always draws behind the navigation bar to avoid a white background.
    await SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
    final accessibilityEnabled = await OhmPlatform.isGestureAccessibilityEnabled();
    final mode = await OhmPlatform.getNavigationMode();
    if (mounted) {
      setState(() {
        _accessibilityServiceEnabled = accessibilityEnabled;
        _systemNavigationMode = mode;
      });
    }
    // By default Android buttons are used (system bar). Gestures
    // remain as an option: enable them in the launcher config ("Force gestures"
    // or the accessibility service). We do not force anything here.
  }

  Future<void> _bootstrap() async {
    setState(() => _busy = true);
    try {
      final dir = await _storage.ensureInitialized();
      final config = await _storage.ensureConfigFile();
      await _storage.seedExamplePlugin();

      _watcher.start(
        configDir: dir,
        pluginsDir: Directory(_storage.pluginsPath),
        onConfigChanged: _reloadConfig,
        onPluginsChanged: _rescanPlugins,
      );

      setState(() {
        _settings = _storage.loadSettings();
        _favorites = _storage.loadFavorites();
        _configSource = config;
        _desktopCount = DynamicWidgetEngine.desktopCount(config);
        _desktopIndex = 0;
        _desktop = _buildDesktopTree(config);
        _busy = false;
      });

      _runtimeWidgets = _storage.loadRuntimeWidgets();
      await _initBinDir();
      _quakeEnabled = (_settings['quakeTerminal'] as bool? ?? true);
      if ((_settings['apiServerEnabled'] as bool? ?? true)) {
        unawaited(_startApiServer());
      }

      await _rescanPlugins();
      if (!_isTestEnvironment) {
        unawaited(_loadMarketplace());
        unawaited(_loadInstalledApps());
      }
    } catch (e) {
      setState(() {
        _bootError = '$e';
        _busy = false;
      });
    }
  }

  void _refreshDesktop() {
    if (!mounted) return;
    setState(() {
      _desktop = _buildDesktopTree(_configSource);
    });
  }

  Widget _buildDesktopTree(String source) {
    return DynamicWidgetEngine.parseDesktop(
      source,
      onPageChanged: (i) {
        if (mounted) setState(() => _desktopIndex = i);
      },
      onLongPressDesktop: _showRadialMenu,
      editingWidget: _editDesktop == _desktopIndex ? _editWidget : null,
      onWidgetLongPress: (wi) {
        if (!mounted) return;
        _editDesktop = _desktopIndex;
        _editWidget = wi;
        _refreshDesktop();
      },
      onWidgetSelected: (wi) {
        if (!mounted) return;
        _editWidget = wi < 0 ? null : wi;
        _refreshDesktop();
      },
      onWidgetMove: (wi, delta) => unawaited(_moveWidget(wi, delta)),
      onWidgetResize: (wi, delta) => unawaited(_resizeWidget(wi, delta)),
      onWidgetDelete: (wi) => unawaited(_deleteWidget(wi)),
      onWidgetDrop: (from, to) => unawaited(_dropWidget(from, to)),
      onWidgetGeometry: (wi, x, y, w, h) => unawaited(_setWidgetGeometry(wi, x, y, w, h)),
    );
  }

  Future<void> _setWidgetGeometry(int index, int x, int y, int w, int h) async {
    await _mutateWidget(index, (list) {
      if (index >= list.length) return list;
      final node = DynamicWidgetEngine.asMapPublic(list[index]);
      if (node == null) return list;
      node['x'] = x;
      node['y'] = y;
      node['w'] = w;
      node['h'] = h;
      list[index] = node;
      return list;
    });
  }

  // ---------------------------------------------- widget editing

  Future<void> _mutateWidget(int index, List<dynamic> Function(List<dynamic>) fn) async {
    await _storage.mutateDesktopWidgets(_desktopIndex, fn);
    await _reloadConfig();
  }

  Future<void> _moveWidget(int index, int delta) async {
    await _mutateWidget(index, (list) {
      final to = (index + delta).clamp(0, list.length - 1);
      if (to == index || list.isEmpty) return list;
      final copy = List<dynamic>.from(list);
      final item = copy.removeAt(index);
      copy.insert(to, item);
      return copy;
    });
  }

  Future<void> _resizeWidget(int index, int delta) async {
    await _mutateWidget(index, (list) {
      if (index >= list.length) return list;
      final node = DynamicWidgetEngine.asMapPublic(list[index]);
      if (node == null) return list;
      final span = (DynamicWidgetEngine.asIntPublic(node['span'], 1) + delta).clamp(1, 4);
      node['span'] = span;
      list[index] = node;
      return list;
    });
  }

  Future<void> _deleteWidget(int index) async {
    await _mutateWidget(index, (list) {
      if (index >= list.length) return list;
      final copy = List<dynamic>.from(list);
      copy.removeAt(index);
      return copy;
    });
    if (mounted) setState(() => _editWidget = null);
  }

  Future<void> _dropWidget(int from, int to) async {
    await _mutateWidget(from, (list) {
      if (from >= list.length || to >= list.length || from == to) return list;
      final copy = List<dynamic>.from(list);
      final item = copy.removeAt(from);
      copy.insert(to, item);
      return copy;
    });
  }

  Future<void> _reloadConfig() async {
    try {
      final source = await File(_storage.configPath).readAsString();
      if (!mounted) return;
      setState(() {
        _configSource = source;
        _desktopCount = DynamicWidgetEngine.desktopCount(source);
        _desktopIndex = _desktopIndex.clamp(0, _desktopCount - 1);
        _desktop = _buildDesktopTree(source);
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _desktop = _ConfigErrorCard(
          title: 'No se pudo leer la config',
          message: '$e',
          origin: _storage.configPath,
        );
      });
    }
  }

  Future<void> _rescanPlugins() async {
    final plugins = await PluginDiscovery.discover(Directory(_storage.pluginsPath));
    if (!mounted) return;
    // The desktop engine (_buildPluginWidget) resolves the plugin_widget
    // against PluginSnapshot.latest, so it must stay in sync with the
    // state of the home; otherwise widgets added to the desktop
    // fail with "not installed or not valid".
    PluginSnapshot.latest = plugins;
    setState(() => _plugins = plugins);
  }

  // --------------------------------------------------- local API + AI layer

  AiClient _buildAiClient() {
    final baseUrl = (_settings['aiBaseUrl'] as String? ?? '').trim();
    final apiKey = (_settings['aiApiKey'] as String? ?? '').trim();
    final model = (_settings['aiModel'] as String? ?? '').trim();
    final system = (_settings['aiSystemPrompt'] as String? ?? '').trim();
    return AiClient(
      baseUrl: baseUrl.isEmpty ? 'https://api.openai.com/v1' : baseUrl,
      apiKey: apiKey,
      model: model.isEmpty ? 'gpt-4o-mini' : model,
      systemPrompt: system.isEmpty
          ? 'Eres Ohm, un asistente que construye la interfaz del launcher. '
              'Cuando crees o modifiques un componente de UI, responde con tu '
              'explicación y luego un bloque ```json (nodo del DynamicWidgetEngine: '
              'container/text/clock/tiling_layout/box/spacer/...) o ```qml '
              '(componente Quickshell). Solo un bloque de componente por respuesta.'
          : system,
    );
  }

  Future<void> _initBinDir() async {
    try {
      final support = await getApplicationSupportDirectory();
      final bin = Directory('${support.path}/bin');
      if (!await bin.exists()) await bin.create(recursive: true);
      _binDir = bin.path;
      _appHomeDir = support.path;
      // Copies the bundled terminfo database for tmux/ssh/etc.
      final terminfoDir = Directory('${support.path}/.terminfo/x');
      if (!await terminfoDir.exists()) await terminfoDir.create(recursive: true);
      final terminfoFile = File('${terminfoDir.path}/xterm-256color');
      if (!await terminfoFile.exists()) {
        final data = await rootBundle.load('assets/terminfo/x/xterm-256color');
        await terminfoFile.writeAsBytes(data.buffer.asUint8List(), flush: true);
      }
      // Ensures Dropbear symlinks if installed.
      final dropbear = File('${bin.path}/dropbearmulti');
      if (await dropbear.exists()) {
        for (final name in ['ssh', 'dbclient', 'scp', 'dropbearkey']) {
          final link = Link('${bin.path}/$name');
          if (!await link.exists()) {
            try {
              await link.create('dropbearmulti');
            } catch (_) {}
          }
        }
      }
    } catch (_) {
      _binDir = null;
      _appHomeDir = null;
    }
  }

  /// Installs an own binary in the private app folder and gives it permission
  /// of execution. Returns a map with the result. The bins stay in the
  /// PATH of the embedded shell so we can invoke them by name.
  Future<Map<String, dynamic>> _installBin(String name, List<int> bytes) async {
    if (_binDir == null) return {'ok': false, 'error': 'bin_dir_unavailable'};
    final file = File('$_binDir/$name');
    await file.writeAsBytes(bytes, flush: true);
    try {
      await Process.run('/system/bin/chmod', ['0755', file.path]);
    } catch (_) {
      /* noop */
    }
    final stat = await file.stat();
    return {
      'ok': true,
      'name': name,
      'size': stat.size,
      'path': file.path,
    };
  }

  /// Installs a binary receiving the raw byte stream (no base64),
  /// writing it directly to disk to support large files (bun ~90 MB).
  Future<Map<String, dynamic>> _installBinRaw(String name, Stream<List<int>> bytes) async {
    if (_binDir == null) return {'ok': false, 'error': 'bin_dir_unavailable'};
    final file = File('$_binDir/$name');
    var size = 0;
    try {
      final out = file.openWrite();
      await for (final chunk in bytes) {
        out.add(chunk);
        size += chunk.length;
      }
      await out.flush();
      await out.close();
    } catch (e) {
      return {'ok': false, 'error': 'write_failed', 'detail': '$e'};
    }
    try {
      await Process.run('/system/bin/chmod', ['0755', file.path]);
    } catch (_) {
      /* noop */
    }
    return {
      'ok': true,
      'name': name,
      'size': size,
      'path': file.path,
    };
  }

  Future<List<Map<String, dynamic>>> _listBins() async {
    if (_binDir == null) return const [];
    final dir = Directory(_binDir!);
    if (!await dir.exists()) return const [];
    final out = <Map<String, dynamic>>[];
    await for (final e in dir.list(followLinks: false)) {
      if (e is File) {
        final s = await e.stat();
        out.add({
          'name': e.path.split('/').last,
          'size': s.size,
          'path': e.path,
        });
      }
    }
    out.sort((a, b) => (a['name'] as String).compareTo(b['name'] as String));
    return out;
  }

  Future<Map<String, dynamic>> _uninstallBin(String name) async {
    if (_binDir == null) return {'ok': false, 'error': 'bin_dir_unavailable'};
    final file = File('$_binDir/$name');
    final existed = await file.exists();
    if (existed) await file.delete();
    return {'ok': true, 'name': name, 'removed': existed};
  }

  void _openQuake() {
    if (!_quakeEnabled || _quakeOpen) return;
    setState(() => _quakeOpen = true);
  }

  void _closeQuake() {
    if (!_quakeOpen) return;
    setState(() => _quakeOpen = false);
  }

  Future<void> _startApiServer() async {
    if (_apiServer?.isRunning == true) return;
    final port = ((_settings['apiServerPort'] as num?) ?? 8753).toInt();
    final link = OmarchyLink(
      onDiscover: () async => {
        'name': 'OhmLauncher',
        'model': 'Android',
        'version': 1,
        'lan_ip': await _lanIp(),
        'port': port,
        'capabilities': ['clipboard', 'file', 'theme', 'screen', 'photos'],
      },
      onClipboardGet: () async {
        final data = await Clipboard.getData('text/plain');
        return data?.text ?? '';
      },
      onClipboardSet: (text) async {
        await Clipboard.setData(ClipboardData(text: text));
      },
      onThemeGet: () async => _themeSnapshot(),
      onThemeSet: (theme) async {
        for (final e in theme.entries) {
          await _saveSetting(e.key, e.value);
        }
        if (mounted) setState(() {});
      },
      onFileReceive: (name, bytes) async {
        final dir = Directory('${StorageService.kPublicRoot}/shared');
        await dir.create(recursive: true);
        final f = File('${dir.path}/$name');
        await f.writeAsBytes(bytes);
        return {'ok': true, 'path': f.path, 'bytes': bytes.length};
      },
      onFileSend: (path) async {
        final f = File(path);
        if (!await f.exists()) return <int>[];
        return await f.readAsBytes();
      },
      onScreenStart: () async {
        _screenCapture ??= ScreenCapture(
          onFrame: (jpeg) => _omarchyLink?.broadcast(screenFrameMessage(jpeg)),
        );
        final ok = await _screenCapture!.start();
        return {'status': ok ? 'started' : 'denied'};
      },
      onScreenStop: () async {
        await _screenCapture?.stop();
      },
      onPhotosBackup: () async {
        final roots = [
          '/storage/emulated/0/DCIM/Camera',
          '/storage/emulated/0/DCIM',
          '/storage/emulated/0/Pictures',
          '/sdcard/DCIM/Camera',
          '/sdcard/DCIM',
        ];
        final photos = <Map<String, dynamic>>[];
        for (final r in roots) {
          final dir = Directory(r);
          if (!await dir.exists()) continue;
          await for (final e in dir.list(recursive: true, followLinks: false)) {
            if (e is File) {
              final ext = e.path.split('.').last.toLowerCase();
              if (['jpg', 'jpeg', 'png', 'heic', 'webp', 'mp4', 'mov'].contains(ext)) {
                final st = await e.stat();
                photos.add({
                  'path': e.path,
                  'name': e.path.split('/').last,
                  'size': st.size,
                  'modified': st.modified.millisecondsSinceEpoch,
                });
              }
            }
          }
        }
        return {'status': 'ok', 'count': photos.length, 'photos': photos};
      },
    );
    _omarchyLink = link;
    _announcer = OmarchyAnnouncer(port: port, lanIp: await _lanIp());
    unawaited(_announcer!.start());
    _apiServer = LocalApiServer(
      port: port,
      lanMode: true,
      omarchyLink: link,
      onCommand: (cmd, args) => ShellExecutor.run(
        cmd,
        args: args,
        useTermux: (_settings['shellPreferTermux'] as bool?) ?? false,
        binDir: _binDir,
        homeDir: _appHomeDir,
      ),
      onInjectWidget: (source, format) => _injectRuntimeWidget(source, format),
      onChat: (prompt, history) async {
        final resp = await _buildAiClient().chat(prompt, history: history);
        if (resp.widgetSource != null && resp.widgetSource!.isNotEmpty) {
          await _injectRuntimeWidget(resp.widgetSource!, resp.widgetFormat ?? 'json');
        }
        return resp;
      },
      onInstallBin: _installBin,
      onInstallBinRaw: _installBinRaw,
      onListBins: _listBins,
      onUninstallBin: _uninstallBin,
      onQuake: (open) => open ? _openQuake() : _closeQuake(),
    );
    await _apiServer!.start();
  }

  /// Preferred LAN IP for Omarchy to connect (first non-loopback).
  Future<String> _lanIp() async {
    try {
      final ifaces = await NetworkInterface.list(type: InternetAddressType.IPv4);
      for (final i in ifaces) {
        for (final a in i.addresses) {
          if (!a.isLoopback) return a.address;
        }
      }
    } catch (_) {}
    return '127.0.0.1';
  }

  /// Subset of settings representing the launcher's theme/colors.
  Map<String, dynamic> _themeSnapshot() {
    final colors = <String, dynamic>{};
    for (final e in _settings.entries) {
      if (e.key.toLowerCase().contains('color') ||
          e.key.toLowerCase().contains('tema') ||
          e.key.toLowerCase().contains('theme') ||
          e.key.toLowerCase().contains('background') ||
          e.key.toLowerCase().contains('accent')) {
        colors[e.key] = e.value;
      }
    }
    return {'colors': colors};
  }

  Future<void> _stopApiServer() async {
    await _apiServer?.stop();
    _omarchyLink?.dispose();
    _omarchyLink = null;
    await _announcer?.stop();
    _announcer = null;
    _apiServer = null;
  }

  /// Shows the QR dialog to connect Omarchy (manual fallback to the
  /// network/Bluetooth auto-detection).
  void _showOmarchyQr() {
    final ann = _announcer;
    if (ann == null) return;
    showDialog(
      context: context,
      builder: (_) => OmarchyQrDialog(
        uri: ann.qrUri,
        lanIp: ann.lanIp,
        port: ann.port,
      ),
    );
  }

  /// Visual confirmation when the Omarchy peer is detected via `omarchy://` QR
  /// scanned with the system camera.
  void _showOmarchyPeerConnected(String id, String ip, int port) {
    final l10n = AppLocalizations.of(context)!;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        backgroundColor: const Color(0xFF16202A),
        content: Text(
          l10n.connectedToOmarchy(id, ip, port),
          style: const TextStyle(color: Color(0xFF66E0FF)),
        ),
        duration: const Duration(seconds: 4),
      ),
    );
  }

  Future<void> _reloadRuntimeWidgets() async {
    final list = _storage.loadRuntimeWidgets();
    if (mounted) setState(() => _runtimeWidgets = list);
  }

  /// Injects a component (JSON from DynamicWidgetEngine or QML) into the layer
  /// floating hot. [source] is the node/component text.
  Future<void> _injectRuntimeWidget(String source, String format) async {
    try {
      if (format == 'qml') {
        // The QML bridge expects a file; we save it as a loose widget and
        // we reference it by embedded content in the overlay.
        await _storage.appendRuntimeWidget({
          'type': 'qml',
          'source': source,
        });
      } else {
        final decoded = jsonDecode(source);
        if (decoded is Map<String, dynamic>) {
          await _storage.appendRuntimeWidget(decoded);
        } else if (decoded is List) {
          for (final node in decoded) {
            if (node is Map<String, dynamic>) {
              await _storage.appendRuntimeWidget(node);
            }
          }
        }
      }
      await _reloadRuntimeWidgets();
    } catch (e) {
      if (mounted) {
        setState(() {
          _runtimeWidgets = [
            ..._runtimeWidgets,
            {
              'type': 'container',
              'color': '#1A1F26',
              'padding': '12',
              'children': [
                {'type': 'text', 'value': 'Error al inyectar componente', 'color': '#FF6B6B'},
                {'type': 'text', 'value': '$e', 'fontSize': 11, 'color': '#9AA7B4'},
              ],
            },
          ];
        });
      }
    }
  }

  Future<void> _clearRuntimeWidgets() async {
    await _storage.clearRuntimeWidgets();
    if (mounted) setState(() => _runtimeWidgets = const []);
  }

  Future<AiResponse> _chatWithAi(String prompt) async {
    final resp = await _buildAiClient().chat(prompt);
    if (resp.widgetSource != null && resp.widgetSource!.isNotEmpty) {
      await _injectRuntimeWidget(resp.widgetSource!, resp.widgetFormat ?? 'json');
    }
    return resp;
  }

  Future<void> _removeRuntimeWidgetAt(int index) async {
    final list = _storage.loadRuntimeWidgets();
    if (index < 0 || index >= list.length) return;
    list.removeAt(index);
    await File(_storage.runtimeWidgetsPath)
        .writeAsString(const JsonEncoder.withIndent('  ').convert(list), flush: true);
    await _reloadRuntimeWidgets();
  }

  /// Renders the layer of generated components (JSON or QML) in the
  /// upper part of the desktop. Each card has a button to remove itself.
  Widget _buildRuntimeLayer() {
    if (_runtimeWidgets.isEmpty) return const SizedBox.shrink();
    final cards = <Widget>[];
    for (var i = 0; i < _runtimeWidgets.length; i++) {
      final node = _runtimeWidgets[i];
      Widget child;
      try {
        if (node['type'] == 'qml') {
          final source = node['source'] as String? ?? '';
          child = QmlInterpreter.interpret(
            source: source,
            originDir: _storage.baseDir?.path ?? '/',
            originFile: 'runtime_$i.qml',
          ).widget;
        } else {
          child = DynamicWidgetEngine.buildNode(node, origin: 'runtime_widgets.json');
        }
      } catch (e) {
        child = Text('Error: $e', style: const TextStyle(color: Color(0xFFFF6B6B), fontSize: 12));
      }
      cards.add(
        Container(
          margin: const EdgeInsets.only(bottom: 10),
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            color: const Color(0xFF10161C),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: const Color(0x2BFFFFFF)),
          ),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(child: child),
              IconButton(
                icon: const Icon(Icons.close, size: 16, color: Color(0xFF7A8A99)),
                onPressed: () => unawaited(_removeRuntimeWidgetAt(i)),
              ),
            ],
          ),
        ),
      );
    }
    return SafeArea(
      child: Align(
        alignment: Alignment.topCenter,
        child: SingleChildScrollView(
          child: Container(
            width: double.infinity,
            margin: const EdgeInsets.only(top: 8, left: 12, right: 12),
            child: Column(children: cards),
          ),
        ),
      ),
    );
  }

  Future<void> _loadMarketplace() async {
    try {
      final entries = await MarketplaceRegistry.fetch();
      if (!mounted) return;
      setState(() {
        _marketplace = entries;
        _registryFailed = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() => _registryFailed = true);
    }
  }

  Future<void> _loadInstalledApps() async {
    try {
      final apps = await OhmPlatform.getInstalledApps();
      if (!mounted) return;
      InstalledAppsSnapshot.latest = apps;
      setState(() => _installedApps = apps);
    } catch (_) {/* native channel unavailable */}
  }

  // ---------------------------------------------- favorites

  void _toggleFavorite(InstalledApp app) {
    final key = app.key;
    setState(() {
      final list = List<String>.from(_favorites);
      if (list.contains(key)) {
        list.remove(key);
      } else {
        list.add(key);
      }
      _favorites = list;
    });
    unawaited(_storage.saveFavorites(_favorites));
  }

  void _reorderFavorites(List<String> order) {
    _favorites = order;
    unawaited(_storage.saveFavorites(_favorites));
  }

  // ---------------------------------------------- drawer: open / menu

  /// Opens an app from the drawer, clears the search and dismisses the keyboard.
  void _openAppFromDrawer(InstalledApp app) {
    unawaited(OhmPlatform.launchApp(app));
    _drawerKey.currentState?.clearSearch();
    _drawerKey.currentState?.close();
  }

  String _edgeLabel(String edge) {
    switch (edge) {
      case 'top':
        return 'Arriba';
      case 'bottom':
        return 'Abajo';
      case 'left':
        return 'Izquierda';
      case 'right':
        return 'Derecha';
      default:
        return edge;
    }
  }

  /// Context menu (long-press 2s) over a drawer app: favorites or box.
  Future<void> _showAppContextMenu(InstalledApp app) async {
    final isFav = _favorites.contains(app.key);
    final choice = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      isScrollControlled: true,
      builder: (ctx) => SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(
                app.label,
                style: const TextStyle(fontSize: 14, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 14),
              OutlinedButton.icon(
                onPressed: () => Navigator.pop(ctx, 'fav'),
                icon: Icon(isFav ? Icons.star : Icons.star_border, size: 18, color: Color(0xFF66E0FF)),
                label: Text(isFav ? 'Quitar de favoritos' : 'Agregar a favoritos',
                    style: const TextStyle(color: Color(0xFF66E0FF))),
              ),
              const SizedBox(height: 10),
              const Text('Agregar a caja de borde:', style: TextStyle(fontSize: 11, color: Color(0xFF9AA7B4))),
              const SizedBox(height: 10),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: const ['top', 'bottom', 'left', 'right']
                    .map((e) => ElevatedButton(
                          onPressed: () => Navigator.pop(ctx, 'box:$e'),
                          style: ElevatedButton.styleFrom(backgroundColor: const Color(0xFF1A2330)),
                          child: Text(_edgeLabel(e), style: const TextStyle(color: Color(0xFFE8F1F8))),
                        ))
                    .toList(),
              ),
            ],
          ),
        ),
      ),
    );
    if (choice == null || !mounted) return;
    if (choice == 'fav') {
      _toggleFavorite(app);
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(isFav ? 'Quitado de favoritos' : 'Agregado a favoritos')),
        );
      }
    } else if (choice.startsWith('box:')) {
      await _addAppToEdgeBox(app, choice.substring(4));
    }
  }

  /// Adds an app to the edge box [edge]; creates the box if it does not exist.
  Future<void> _addAppToEdgeBox(InstalledApp app, String edge) async {
    final map = _storage.readConfigMap() ?? <String, dynamic>{};
    final boxes = StorageService.edgeBoxesOf(map);
    var index = boxes.indexWhere((b) => (b['edge'] as String?) == edge);
    if (index < 0) index = await _storage.addEdgeBox(edge: edge);
    await _addItemsToBox(index, [
      {
        'type': 'app',
        'package': app.package,
        'activity': app.activity,
        'label': app.label,
      }
    ]);
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Agregado a caja (${_edgeLabel(edge)})')),
      );
    }
  }

  // ---------------------------------------------- multi-desktop

  Future<void> _addDesktop(int index) async {
    await _storage.addDesktop(index: index);
    await _reloadConfig();
  }

  Future<void> _addWidget(int index, Map<String, dynamic> node) async {
    await _storage.addWidgetToDesktop(index, node);
    await _reloadConfig();
  }

  /// Adds an empty edge box to the bottom edge.
  Future<void> _addBox(int index) async {
    await _storage.addEdgeBox(edge: 'bottom');
    await _reloadConfig();
  }

  /// Radial menu on long-press of the desktop background.
  void _showRadialMenu(int index) {
    final configSource = File(_storage.configPath).existsSync()
        ? File(_storage.configPath).readAsStringSync()
        : '';
    showDialog<void>(
      context: context,
      barrierColor: const Color(0x66000000),
      builder: (dialogContext) => _RadialDesktopMenu(
        desktopName: DynamicWidgetEngine.desktopName(configSource, index),
        onAddLeft: () {
          Navigator.of(dialogContext).pop();
          unawaited(_addDesktop(index));
        },
        onAddRight: () {
          Navigator.of(dialogContext).pop();
          unawaited(_addDesktop(index + 1));
        },
        onAddWidget: () {
          Navigator.of(dialogContext).pop();
          unawaited(_showWidgetPicker(index));
        },
        onAddBox: () {
          Navigator.of(dialogContext).pop();
          unawaited(_addBox(index));
        },
        onEditWidgets: () {
          Navigator.of(dialogContext).pop();
          _editDesktop = index;
          _editWidget = -1;
          _refreshDesktop();
        },
        onSettings: () {
          Navigator.of(dialogContext).pop();
          unawaited(_showDesktopSettings(index));
        },
        onLauncherSettings: () {
          Navigator.of(dialogContext).pop();
          unawaited(_showLauncherSettings());
        },
        onDeleteDesktop: () {
          Navigator.of(dialogContext).pop();
          unawaited(_deleteDesktop(index));
        },
        onRestart: () {
          Navigator.of(dialogContext).pop();
          _restartApp();
        },
        onConnectOmarchy: () {
          Navigator.of(dialogContext).pop();
          _showOmarchyQr();
        },
      ),
    );
  }

  /// Restarts the launcher (recreates the Activity and the Flutter engine).
  void _restartApp() {
    unawaited(OhmPlatform.restartApp());
  }

  Future<void> _deleteDesktop(int index) async {
    final map = _storage.readConfigMap();
    if (map == null) return;
    final desktops = map['desktops'];
    if (desktops is! List || desktops.isEmpty) return;
    if (desktops.length <= 1) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('No se puede eliminar el último escritorio')),
        );
      }
      return;
    }
    desktops.removeAt(index.clamp(0, desktops.length - 1));
    await _storage.writeConfigMap(map);
    await _reloadConfig();
  }

  /// System widget picker to add to the current desktop.
  Future<void> _showWidgetPicker(int index) async {
    final chosen = await showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      isScrollControlled: true,
      builder: (_) => const _WidgetPickerSheet(),
    );
    if (chosen == null) return;
    await _addWidget(index, chosen);
  }

  /// Content picker for a box (app, system widget or plugin).
  Future<void> _showBoxContentPicker(int boxIndex) async {
    final kind = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 16, 20, 12),
              child: Text(
                '¿Qué quieres agregar a la caja?',
                style: TextStyle(fontSize: 14, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600),
              ),
            ),
            ListTile(
              leading: const Icon(Icons.android, color: Color(0xFF66E0FF)),
              title: const Text('Aplicación', style: TextStyle(color: Color(0xFFE8F1F8))),
              onTap: () => Navigator.of(context).pop('app'),
            ),
            ListTile(
              leading: const Icon(Icons.widgets_outlined, color: Color(0xFF66E0FF)),
              title: const Text('Widget del sistema', style: TextStyle(color: Color(0xFFE8F1F8))),
              onTap: () => Navigator.of(context).pop('widget'),
            ),
            ListTile(
              leading: const Icon(Icons.extension_outlined, color: Color(0xFF66E0FF)),
              title: const Text('Plugin', style: TextStyle(color: Color(0xFFE8F1F8))),
              onTap: () => Navigator.of(context).pop('plugin'),
            ),
            const SizedBox(height: 12),
          ],
        ),
      ),
    );
    if (kind == null || !mounted) return;

    switch (kind) {
      case 'app':
        await _showBoxAppPicker(boxIndex);
        break;
      case 'widget':
        await _showBoxWidgetPicker(boxIndex);
        break;
      case 'plugin':
        await _showBoxPluginPicker(boxIndex);
        break;
    }
  }

  /// App picker for a box: list with stars and multi-selection.
  Future<void> _showBoxAppPicker(int boxIndex) async {
    final selected = await showModalBottomSheet<List<InstalledApp>>(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      isScrollControlled: true,
      builder: (_) => _BoxAppPickerSheet(apps: _installedApps, favorites: _favorites),
    );
    if (selected == null || selected.isEmpty || !mounted) return;
    await _addItemsToBox(boxIndex, selected.map((app) => {
      'type': 'app',
      'package': app.package,
      'activity': app.activity,
      'label': app.label,
    }).toList());
  }

  /// System widget picker for a box.
  Future<void> _showBoxWidgetPicker(int boxIndex) async {
    final chosen = await showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      isScrollControlled: true,
      builder: (_) => const _WidgetPickerSheet(),
    );
    if (chosen == null || !mounted) return;
    await _addItemsToBox(boxIndex, [chosen]);
  }

  /// Plugin picker for a box.
  Future<void> _showBoxPluginPicker(int boxIndex) async {
    final plugins = PluginSnapshot.latest.where((p) => p.isValid).toList();
    final chosen = await showModalBottomSheet<Map<String, dynamic>>(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 16, 20, 12),
              child: Text(
                'Elige un plugin',
                style: TextStyle(fontSize: 14, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600),
              ),
            ),
            if (plugins.isEmpty)
              const Padding(
                padding: EdgeInsets.all(20),
                child: Text('No hay plugins instalados.', style: TextStyle(color: Color(0xFF7A8A99))),
              )
            else
              for (final p in plugins)
                ListTile(
                  leading: const Icon(Icons.extension_outlined, color: Color(0xFFB48AFF)),
                  title: Text(p.manifest?.name ?? p.id, style: const TextStyle(color: Color(0xFFE8F1F8))),
                  subtitle: Text(p.id, style: const TextStyle(fontSize: 11, color: Color(0xFF7A8A99))),
                  onTap: () => Navigator.of(context).pop(<String, dynamic>{
                    'type': 'plugin',
                    'pluginId': p.id,
                    'label': p.manifest?.name ?? p.id,
                  }),
                ),
            const SizedBox(height: 12),
          ],
        ),
      ),
    );
    if (chosen == null || !mounted) return;
    await _addItemsToBox(boxIndex, [chosen]);
  }

  /// Edge box configuration: address, show title, add content.
  Future<void> _showEdgeBoxConfig(int boxIndex) async {
    final map = _storage.readConfigMap();
    if (map == null) return;
    final boxes = StorageService.edgeBoxesOf(map);
    if (boxIndex >= boxes.length) return;

    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      isScrollControlled: true,
      builder: (context) => StatefulBuilder(
        builder: (context, sheetSetState) {
          final map = _storage.readConfigMap();
          if (map == null) return const SizedBox.shrink();
          final boxes = StorageService.edgeBoxesOf(map);
          if (boxIndex >= boxes.length) return const SizedBox.shrink();
          final box = boxes[boxIndex];
          final items = box['items'] is List ? box['items'] as List : const [];

          Future<void> apply(Future<void> Function() action) async {
            await action();
            await _reloadConfig();
            if (context.mounted) sheetSetState(() {});
          }

          return SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
              Text(
                'Configurar "${box['name'] as String? ?? 'Caja'}"',
                style: const TextStyle(fontSize: 14, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 16),
              const Text('DIRECCIÓN', style: TextStyle(fontSize: 10, color: Color(0xFF5A6B7A), letterSpacing: 3)),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: [
                  for (final dir in const ['horizontal', 'vertical', 'grid', 'list'])
                    ChoiceChip(
                      label: Text(dir == 'horizontal'
                          ? 'Horizontal'
                          : dir == 'vertical'
                              ? 'Vertical'
                              : dir == 'grid'
                                  ? 'Grilla'
                                  : 'Lista'),
                      selected: (box['direction'] as String? ?? 'horizontal') == dir,
                      materialTapTargetSize: MaterialTapTargetSize.padded,
                      onSelected: (_) {
                        unawaited(apply(() => _storage.updateEdgeBox(boxIndex, (b) {
                          b['direction'] = dir;
                          return b;
                        })));
                      },
                ),
              ],
              ),
              const SizedBox(height: 16),
              const Text('MOSTRAR', style: TextStyle(fontSize: 10, color: Color(0xFF5A6B7A), letterSpacing: 3)),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: [
                  ChoiceChip(
                    label: const Text('Icono + Título'),
                    selected: box['showTitle'] as bool? ?? true,
                    materialTapTargetSize: MaterialTapTargetSize.padded,
                    onSelected: (_) {
                      unawaited(apply(() => _storage.updateEdgeBox(boxIndex, (b) {
                        b['showTitle'] = true;
                        return b;
                      })));
                    },
                  ),
                  ChoiceChip(
                    label: const Text('Solo Icono'),
                    selected: !(box['showTitle'] as bool? ?? true),
                    materialTapTargetSize: MaterialTapTargetSize.padded,
                    onSelected: (_) {
                      unawaited(apply(() => _storage.updateEdgeBox(boxIndex, (b) {
                        b['showTitle'] = false;
                        return b;
                      })));
                    },
                  ),
                ],
              ),
              const SizedBox(height: 16),
              const Text('COLOR', style: TextStyle(fontSize: 10, color: Color(0xFF5A6B7A), letterSpacing: 3)),
              const SizedBox(height: 8),
              Wrap(
                spacing: 10,
                children: [
                  for (final hex in _kBoxAccentColors)
                    InkWell(
                    onTap: () {
                      unawaited(apply(() => _storage.updateEdgeBox(boxIndex, (b) {
                        b['color'] = hex;
                        return b;
                      })));
                    },
                      customBorder: const CircleBorder(),
                      child: Padding(
                        padding: const EdgeInsets.all(4),
                        child: Container(
                          width: 40,
                          height: 40,
                        decoration: BoxDecoration(
                          color: DynamicWidgetEngine.colorFromHex(hex),
                          shape: BoxShape.circle,
                          border: Border.all(
                            color: (box['color'] as String? ?? _kBoxAccentColors.first) == hex
                                ? const Color(0xFF66E0FF)
                                : const Color(0xFF3A4654),
                            width: (box['color'] as String? ?? _kBoxAccentColors.first) == hex ? 3 : 1,
                          ),
                        ),
                      ),
                    ),
                  ),
                  // Button to revert to the default color.
                  InkWell(
                    onTap: () {
                      unawaited(apply(() => _storage.updateEdgeBox(boxIndex, (b) {
                        b.remove('color');
                        return b;
                      })));
                    },
                    customBorder: const CircleBorder(),
                    child: Padding(
                      padding: const EdgeInsets.all(4),
                      child: Container(
                        width: 40,
                        height: 40,
                      decoration: BoxDecoration(
                        color: const Color(0xFF10161C),
                        shape: BoxShape.circle,
                        border: Border.all(
                          color: (box['color'] is String) ? const Color(0xFF3A4654) : const Color(0xFF66E0FF),
                          width: (box['color'] is String) ? 1 : 3,
                        ),
                      ),
                      child: const Icon(Icons.refresh, size: 18, color: Color(0xFF5A6B7A)),
                    ),
                  ),
                ),
              ],
              ),
              const SizedBox(height: 16),
              const Text('MODO COMPACTO', style: TextStyle(fontSize: 10, color: Color(0xFF5A6B7A), letterSpacing: 3)),
              const SizedBox(height: 8),
              Row(
                children: [
                  const Expanded(
                    child: Text(
                      'Icono único al colapsar',
                      style: TextStyle(fontSize: 12, color: Color(0xFF9AA7B4)),
                    ),
                  ),
                  Switch(
                    value: box['compact'] as bool? ?? false,
                    onChanged: (v) {
                      unawaited(apply(() => _storage.updateEdgeBox(boxIndex, (b) {
                        b['compact'] = v;
                        return b;
                      })));
                    },
                  ),
                ],
              ),
              if (items.isNotEmpty) ...[
                const SizedBox(height: 8),
                const Text('ÍCONO COMPACTO', style: TextStyle(fontSize: 10, color: Color(0xFF5A6B7A), letterSpacing: 3)),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 8,
                  children: [
                    for (var i = 0; i < items.length; i++)
                      ChoiceChip(
                        label: Text(
                          (items[i] is Map && items[i]['label'] is String)
                              ? items[i]['label'] as String
                              : 'Elemento ${i + 1}',
                          style: const TextStyle(fontSize: 11),
                        ),
                        selected: ((box['compactItem'] as num?)?.toInt() ?? 0) == i,
                        materialTapTargetSize: MaterialTapTargetSize.padded,
                        onSelected: (_) {
                          unawaited(apply(() => _storage.updateEdgeBox(boxIndex, (b) {
                            b['compactItem'] = i;
                            return b;
                          })));
                        },
                      ),
                  ],
                ),
              ],
              const SizedBox(height: 16),
              const Text('CONTENIDO', style: TextStyle(fontSize: 10, color: Color(0xFF5A6B7A), letterSpacing: 3)),
              const SizedBox(height: 8),
              ListTile(
                leading: const Icon(Icons.add_circle_outline, color: Color(0xFF66E0FF)),
                title: const Text('Agregar apps, widgets o plugins', style: TextStyle(color: Color(0xFFE8F1F8))),
                onTap: () {
                  Navigator.of(context).pop();
                  unawaited(_showBoxContentPicker(boxIndex));
                },
              ),
              const SizedBox(height: 8),
              ListTile(
                leading: const Icon(Icons.delete_outline, color: Color(0xFFFF6B7A)),
                title: const Text('Eliminar caja', style: TextStyle(color: Color(0xFFFF6B7A))),
                onTap: () {
                  Navigator.of(context).pop();
                  unawaited(_deleteEdgeBox(boxIndex));
                },
              ),
              const SizedBox(height: 16),
              ElevatedButton(
                onPressed: () => Navigator.of(context).pop(),
                style: ElevatedButton.styleFrom(
                  backgroundColor: const Color(0xFF66E0FF),
                  foregroundColor: const Color(0xFF10161C),
                ),
                child: const Text('Listo'),
              ),
            ],
          ),
        ),
      );
    },
  ),
);
}
  Future<void> _addItemsToBox(int boxIndex, List<Map<String, dynamic>> newItems) async {
    await _storage.updateEdgeBox(boxIndex, (box) {
      final items = box['items'] is List ? List<Map<String, dynamic>>.from(box['items'] as List) : <Map<String, dynamic>>[];
      items.addAll(newItems);
      box['items'] = items;
      return box;
    });
    await _reloadConfig();
  }

  Map<String, dynamic>? _desktopMap(int desktopIndex) {
    final map = _storage.readConfigMap();
    if (map == null) return null;
    final desktops = map['desktops'];
    if (desktops is List && desktopIndex >= 0 && desktopIndex < desktops.length) {
      final d = desktops[desktopIndex];
      if (d is Map<String, dynamic>) return d;
    }
    return null;
  }

  String? _desktopFont(int index, String key) {
    final d = _desktopMap(index);
    if (d == null) return null;
    final v = d[key];
    return v is String && v.isNotEmpty ? v : null;
  }

  Future<void> _setDesktopValue(int desktopIndex, String key, Object? value) async {
    final map = _storage.readConfigMap();
    if (map == null) return;
    final desktops = map['desktops'];
    if (desktops is List && desktopIndex >= 0 && desktopIndex < desktops.length) {
      final d = desktops[desktopIndex];
      if (d is Map<String, dynamic>) {
        if (value == null || (value is String && value.isEmpty)) {
          d.remove(key);
        } else {
          d[key] = value;
        }
      }
    }
    await _storage.writeConfigMap(map);
    await _reloadConfig();
  }

  /// Launcher settings: typography, background and default launcher.
  Future<void> _showDesktopSettings(int desktopIndex) async {
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      isScrollControlled: true,
      builder: (_) => _DesktopSettingsSheet(
        currentFontFamily: _desktopFont(desktopIndex, 'fontFamily') ??
            _settings['fontFamily'] as String? ??
            'Predeterminada',
        currentTitleFont: _desktopFont(desktopIndex, 'titleFont') ??
            _settings['fontFamily'] as String? ??
            'Predeterminada',
        currentGridCols: (_desktopMap(desktopIndex)?['gridColumns'] as num?)?.toInt() ?? 14,
        currentGridRows: (_desktopMap(desktopIndex)?['gridRows'] as num?)?.toInt() ?? 10,
        onFontFamily: (f) => unawaited(_setDesktopValue(desktopIndex, 'fontFamily', f)),
        onTitleFont: (f) => unawaited(_setDesktopValue(desktopIndex, 'titleFont', f)),
        onBackground: (color) => unawaited(_setDesktopBackground(desktopIndex, color)),
        onBackgroundImage: (path) => unawaited(_setDesktopValue(desktopIndex, 'backgroundImage', path)),
        onGridCols: (v) => unawaited(_setDesktopValue(desktopIndex, 'gridColumns', v)),
        onGridRows: (v) => unawaited(_setDesktopValue(desktopIndex, 'gridRows', v)),
      ),
    );
  }

  Future<void> _showLauncherSettings() async {
    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      isScrollControlled: true,
      builder: (_) => _LauncherSettingsSheet(
        currentTextScale: ((_settings['textScale'] as num?) ?? 1.0).toDouble(),
        currentBoxSpacing: ((_settings['boxSpacing'] as num?) ?? 1.0).toDouble(),
        currentLanguage: (_settings['language'] as String?) ?? 'auto',
        gestureNavigationEnabled: (_settings['gestureNavigationEnabled'] as bool?) ?? false,
        showTapBoxes: (_settings['showTapBoxes'] as bool?) ?? false,
        onTextScale: (s) => _saveSetting('textScale', s),
        onBoxSpacing: (s) => _saveSetting('boxSpacing', s),
        onLanguage: (l) => _saveSetting('language', l),
        onGestureNavigationEnabled: (v) => _saveSetting('gestureNavigationEnabled', v),
        onShowTapBoxes: (v) => _saveSetting('showTapBoxes', v),
        currentFavoritesBarMode: _settings['favoritesBarMode'] as String?,
        onFavoritesBarMode: (m) =>
            _saveSetting('favoritesBarMode', m == 'auto' ? null : m),
        onGesturesForced: () => unawaited(_updateSystemNavigationMode()),
        apiServerEnabled: (_settings['apiServerEnabled'] as bool?) ?? true,
        apiServerPort: ((_settings['apiServerPort'] as num?) ?? 8753).toInt(),
        onApiServerEnabled: (v) async {
          await _saveSetting('apiServerEnabled', v);
          if (v) {
            await _startApiServer();
          } else {
            await _stopApiServer();
          }
        },
        onApiServerPort: (p) => _saveSetting('apiServerPort', p),
        shellPreferTermux: (_settings['shellPreferTermux'] as bool?) ?? false,
        onShellPreferTermux: (v) => _saveSetting('shellPreferTermux', v),
        quakeTerminal: (_settings['quakeTerminal'] as bool?) ?? true,
        onQuakeTerminal: (v) {
          setState(() => _quakeEnabled = v);
          unawaited(_saveSetting('quakeTerminal', v));
        },
        aiBaseUrl: (_settings['aiBaseUrl'] as String?) ?? '',
        aiApiKey: (_settings['aiApiKey'] as String?) ?? '',
        aiModel: (_settings['aiModel'] as String?) ?? '',
        aiSystemPrompt: (_settings['aiSystemPrompt'] as String?) ?? '',
        onAiBaseUrl: (v) => _saveSetting('aiBaseUrl', v),
        onAiApiKey: (v) => _saveSetting('aiApiKey', v),
        onAiModel: (v) => _saveSetting('aiModel', v),
        onAiSystemPrompt: (v) => _saveSetting('aiSystemPrompt', v),
        currentBoxRadius: ((_settings['boxRadius'] as num?) ?? 14).toDouble(),
        currentBarRadius: ((_settings['barRadius'] as num?) ?? 18).toDouble(),
        onBoxRadius: (v) => _saveSetting('boxRadius', v),
        onBarRadius: (v) => _saveSetting('barRadius', v),
      ),
    );
  }

  Future<void> _setDesktopBackground(int desktopIndex, String colorHex) async {
    final map = _storage.readConfigMap();
    if (map == null) return;
    final desktops = map['desktops'];
    if (desktops is List && desktopIndex >= 0 && desktopIndex < desktops.length) {
      final d = desktops[desktopIndex];
      if (d is Map<String, dynamic>) d['background'] = colorHex;
    } else {
      map['wallpaper'] = colorHex;
    }
    await _storage.writeConfigMap(map);
    await _reloadConfig();
  }

  Future<void> _saveSetting(String key, Object? value) async {
    _settings[key] = value;
    if (mounted) setState(() {});
    await _storage.saveSettings(_settings);
  }

  Widget _buildBottomBar(String position, double radius) {
    final visible = _settings['bottomBarVisible'] as bool? ?? true;
    return _OhmBottomBar(
      plugins: _plugins,
      apps: _installedApps,
      marketplace: _marketplace,
      registryFailed: _registryFailed,
      favorites: _favorites,
      onRetryRegistry: _loadMarketplace,
      onPluginsInstalled: _rescanPlugins,
      onToggleFavorite: _toggleFavorite,
      visible: visible,
      onToggle: _toggleBottomBarVisible,
      onOpenSettings: _showLauncherSettings,
      position: position,
      onPositionChange: (p) => _saveSetting('bottomBarPosition', p),
      publicPath: _storage.usesPublicPath,
      pluginCount: _plugins.where((p) => p.isValid).length,
      serviceCount: _plugins.where((p) => p.kinds.contains('service')).length,
      orientation: (position == 'left' || position == 'right') ? 'vertical' : 'horizontal',
      radius: radius,
      disabledPluginIds: _storage.disabledPluginIds(),
      onTogglePluginEnabled: _togglePluginEnabled,
      onDeletePlugin: _deletePlugin,
      onAddPluginWidget: _addPluginToDesktop,
    );
  }

  /// Renders edge boxes grouped by edge, sharing the space
  /// of the edge with the favorites bar and the bottom plugins bar to
  /// that they do not overlap.
  List<Widget> _buildEdgeBoxes() {
    final bottomBarPosition = _settings['bottomBarPosition'] as String? ?? 'bottom';
    final favoriteAppsByKey = {for (final a in _installedApps) a.key: a};
    final favoriteApps = _favorites
        .where((k) => favoriteAppsByKey.containsKey(k))
        .map((k) => favoriteAppsByKey[k]!)
        .toList();
    final favBarVisible = _settings['favoritesBarVisible'] as bool? ?? true;
    final favBarPosition = _settings['favoritesBarPosition'] as String? ?? 'top';
    final favBarModeRaw = _settings['favoritesBarMode'];
    final favBarMode = favBarModeRaw is String && favBarModeRaw.isNotEmpty
        ? favBarModeRaw
        : (favBarPosition == 'left' || favBarPosition == 'right' ? 'vertical' : 'horizontal');
    // Edge radii: 0 = square corners (no rounding).
    final boxRadius = ((_settings['boxRadius'] as num?) ?? 14).toDouble();
    final barRadius = ((_settings['barRadius'] as num?) ?? 18).toDouble();

    final map = _storage.readConfigMap();
    final boxes = map == null ? const <Map<String, dynamic>>[] : StorageService.edgeBoxesOf(map);

    final result = <Widget>[];
    for (final edge in const ['top', 'bottom', 'left', 'right']) {
      final isVertical = edge == 'left' || edge == 'right';
      final group = boxes
          .asMap()
          .entries
          .where((e) => (e.value['edge'] as String? ?? 'bottom') == edge)
          .map((e) => {...e.value, 'index': e.key})
          .toList();
      final shift = _liveBoxShift;
      final draggedOriginalPos = (shift != null && shift.edge == edge)
          ? group.indexWhere((b) => (b['index'] as int) == shift.idx)
          : -1;
      final items = <Widget>[];

      for (var j = 0; j < group.length; j++) {
        final box = group[j];
        final i = box['index'] as int;
        Widget child = Padding(
          padding: EdgeInsets.only(
            bottom: isVertical ? 8 : 0,
            right: isVertical ? 0 : 8,
          ),
          child: _EdgeBox(
            box: box,
            boxIndex: i,
            visible: box['visible'] as bool? ?? true,
            onToggle: () => _toggleEdgeBoxVisible(i),
            onAddContent: () => _showBoxContentPicker(i),
            onConfig: () => _showEdgeBoxConfig(i),
            onDragUpdate: (d, accent) => _onBarDragUpdate('edgeBox_$i', d, accent),
            onDragEnd: (d) => _onEdgeBoxDragEnd(i, d),
            onItemDropped: (sourceBox, itemIndex, targetBox) =>
                unawaited(_onEdgeBoxItemDropped(sourceBox, itemIndex, targetBox)),
            onItemsReordered: (boxIndex, from, to) =>
                unawaited(_reorderEdgeBoxItems(boxIndex, from, to)),
            onReportRect: (boxIndex, rect) => _reportEdgeBoxRect(boxIndex, rect),
            boxRects: _boxRects,
            boxSpacing: ((_settings['boxSpacing'] as num?) ?? 1.0).toDouble(),
            radius: boxRadius,
          ),
        );

        if (shift != null && shift.edge == edge && i != shift.idx) {
          const gap = 8.0;
          final rawOffset = isVertical
              ? Offset(0.0, shift.size.height + gap)
              : Offset(shift.size.width + gap, 0.0);
          var displacement = 0;
          if (draggedOriginalPos < 0) {
            // The dragged box comes from another edge: pushes forward the
            // starting from the insertion point.
            if (j >= shift.insertAt) displacement = 1;
          } else {
            // Same edge: the boxes between the original and new position are
            // shift one place to leave/release the gap.
            if (draggedOriginalPos < shift.insertAt) {
              if (j > draggedOriginalPos && j <= shift.insertAt) {
                displacement = -1;
              }
            } else if (draggedOriginalPos > shift.insertAt) {
              if (j >= shift.insertAt && j < draggedOriginalPos) {
                displacement = 1;
              }
            }
          }
          if (displacement != 0) {
            final endOffset =
                Offset(rawOffset.dx * displacement, rawOffset.dy * displacement);
            child = TweenAnimationBuilder<Offset>(
              tween: Tween(begin: Offset.zero, end: endOffset),
              duration: const Duration(milliseconds: 220),
              curve: Curves.easeOut,
              builder: (context, value, c) => Transform.translate(offset: value, child: c),
              child: child,
            );
          }
        }
        items.add(child);
      }

      // Favorites bar: shares the edge with the boxes. Always mounted;
      // the `visible` flag controls the collapse to its inner handle, so
      // the user can expand/close by touching the handle.
      if (favoriteApps.isNotEmpty && favBarPosition == edge) {
        items.add(
          Padding(
            padding: EdgeInsets.only(
              bottom: isVertical ? 8 : 0,
              right: isVertical ? 0 : 8,
            ),
            child: _wrapDrag(
              'favorites',
              _FavoritesBar(
                apps: favoriteApps,
                visible: favBarVisible,
                onToggle: _toggleFavBarVisible,
                position: favBarPosition,
                onPositionChange: (p) => _saveSetting('favoritesBarPosition', p),
                orientation: (edge == 'left' || edge == 'right') ? 'vertical' : 'horizontal',
                mode: favBarMode,
                onReordered: (order) => setState(() => _reorderFavorites(order)),
                radius: barRadius,
              ),
            ),
          ),
        );
      }

      // Bottom plugins bar: shares the edge with the boxes. Always
      // mounts; `visible` controls the collapse to the handle.
      if (bottomBarPosition == edge) {
        items.add(
          Padding(
            padding: EdgeInsets.only(
              bottom: isVertical ? 8 : 0,
              right: isVertical ? 0 : 8,
            ),
            child: _wrapDrag('bottom', _buildBottomBar(edge, barRadius)),
          ),
        );
      }

      if (items.isEmpty) continue;
      final groupKey = ValueKey('$edge:${items.length}');
      result.add(
        AnimatedSwitcher(
          duration: const Duration(milliseconds: 250),
          transitionBuilder: (child, anim) => FadeTransition(
            opacity: anim,
            child: ScaleTransition(
              scale: Tween<double>(begin: 0.96, end: 1).animate(anim),
              child: child,
            ),
          ),
          child: KeyedSubtree(
            key: groupKey,
            child: _alignBar(
              edge,
              isVertical
                  ? Column(mainAxisSize: MainAxisSize.min, children: items)
                  : Row(mainAxisSize: MainAxisSize.min, children: items),
            ),
          ),
        ),
      );
    }
    return result;
  }

  Future<void> _toggleEdgeBoxVisible(int index) async {
    await _storage.updateEdgeBox(index, (box) {
      box['visible'] = !(box['visible'] as bool? ?? true);
      return box;
    });
    await _reloadConfig();
  }

  /// Collapses/expands the favorites bar (toggles its handle).
  void _toggleFavBarVisible() {
    setState(() {
      _saveSetting('favoritesBarVisible', !((_settings['favoritesBarVisible'] as bool?) ?? true));
    });
  }

  /// Collapses/expands the plugins bar (toggles its handle).
  void _toggleBottomBarVisible() {
    setState(() {
      _saveSetting('bottomBarVisible', !((_settings['bottomBarVisible'] as bool?) ?? true));
    });
  }

  /// Enables/disables a plugin (moves it between plugins/ and plugins.disabled/).
  Future<void> _togglePluginEnabled(String id) async {
    final disabled = _storage.disabledPluginIds();
    if (disabled.contains(id)) {
      await _storage.enablePlugin(id);
    } else {
      await _storage.disablePlugin(id);
    }
    await _rescanPlugins();
  }

  /// Physically removes a plugin and rescans.
  Future<void> _deletePlugin(String id) async {
    await _storage.deletePlugin(id);
    await _rescanPlugins();
  }

  /// Adds a plugin (bar-widget) as a floating widget on the current desktop.
  Future<void> _addPluginToDesktop(OhmPlugin plugin) async {
    final id = plugin.manifest?.id ?? plugin.id;
    final node = <String, dynamic>{
      'type': 'plugin_widget',
      'pluginId': id,
      'kind': 'bar-widget',
    };
    await _addWidget(_desktopIndex, node);
  }

  Future<void> _deleteEdgeBox(int index) async {
    await _storage.deleteEdgeBox(index);
    await _reloadConfig();
  }

  void _reportEdgeBoxRect(int index, Rect rect) {
    _boxRects[index] = rect;
    // Only forces a rebuild if the box debug overlay is active;
    // otherwise the map updates without rebuilding the desktop.
    if (!((_settings['showTapBoxes'] as bool?) ?? false)) return;
    if (!_boxRectsDirty) {
      _boxRectsDirty = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _boxRectsDirty = false;
        if (mounted) setState(() {});
      });
    }
  }

  /// Reorders items within a box (drag with long-press, 1s).
  Future<void> _reorderEdgeBoxItems(int boxIndex, int from, int to) async {
    if (from == to) return;
    final map = _storage.readConfigMap();
    if (map == null) return;
    final boxes = map['edgeBoxes'];
    if (boxes is! List || boxIndex >= boxes.length) return;
    final box = boxes[boxIndex];
    if (box is! Map<String, dynamic>) return;
    final items = box['items'] is List ? List<dynamic>.from(box['items'] as List) : <dynamic>[];
    if (from < 0 || from >= items.length) return;
    final item = items.removeAt(from);
    final toSafe = to.clamp(0, items.length);
    items.insert(toSafe, item);
    box['items'] = items;
    await _storage.writeConfigMap(map);
    await _reloadConfig();
  }

  void _onEdgeBoxDragEnd(int index, DragEndDetails d) {
    final target = _barDragTarget.value;
    final dropPos = d.globalPosition;
    final original = _originalBoxOrder;
    _barDragTarget.value = null;
    _boxDragAccent.value = const Color(0xFF66E0FF);
    _boxDragSourceEdge.value = 'bottom';
    _boxDragPos.value = Offset.zero;
    _boxDragIndex = null;
    _liveBoxShift = null;
    _originalBoxOrder = null;

    if (target != null && original != null) {
      final map = _storage.readConfigMap();
      if (map != null) {
        final (insertAt, targetOrder) =
            _computeBoxDropOrder(target, dropPos, index, original);
        final newGroup = List<int>.from(targetOrder)..insert(insertAt, index);
        final newList = <dynamic>[];
        var s = 0;
        for (var i = 0; i < original.length; i++) {
          if (targetOrder.contains(i) || i == index) {
            if (newGroup[s] == index) {
              final dragged = Map<String, dynamic>.from(
                  original[index] as Map<String, dynamic>);
              dragged['edge'] = target;
              dragged['direction'] =
                  (target == 'left' || target == 'right') ? 'vertical' : 'horizontal';
              newList.add(dragged);
            } else {
              newList.add(original[newGroup[s]]);
            }
            s++;
          } else {
            newList.add(original[i]);
          }
        }
        map['edgeBoxes'] = newList;
        unawaited(_storage.writeConfigMap(map).then((_) => _reloadConfig()));
        return;
      }
    }

    // No target edge: discards the move and returns to the previous state.
    if (original != null) {
      setState(() {});
    }
  }

  /// Moves an item from one box to another (drag with long-press).
  Future<void> _onEdgeBoxItemDropped(int sourceBox, int itemIndex, int targetBox) async {
    if (sourceBox == targetBox) return;
    final map = _storage.readConfigMap();
    if (map == null) return;
    final boxes = map['edgeBoxes'];
    if (boxes is! List || sourceBox >= boxes.length || targetBox >= boxes.length) return;
    final src = boxes[sourceBox];
    final dst = boxes[targetBox];
    if (src is! Map<String, dynamic> || dst is! Map<String, dynamic>) return;
    final srcItems = src['items'] is List ? List<dynamic>.from(src['items'] as List) : <dynamic>[];
    if (itemIndex < 0 || itemIndex >= srcItems.length) return;
    final item = srcItems.removeAt(itemIndex);
    final dstItems = dst['items'] is List ? List<dynamic>.from(dst['items'] as List) : <dynamic>[];
    dstItems.add(item);
    src['items'] = srcItems;
    dst['items'] = dstItems;
    await _storage.writeConfigMap(map);
    await _reloadConfig();
  }

  String _edgeFor(Offset p, Size s) {
    final dyTop = p.dy;
    final dyBottom = s.height - p.dy;
    final dxLeft = p.dx;
    final dxRight = s.width - p.dx;
    final min = [dyTop, dyBottom, dxLeft, dxRight].reduce((a, b) => a < b ? a : b);
    if (min == dyTop) return 'top';
    if (min == dyBottom) return 'bottom';
    if (min == dxLeft) return 'left';
    return 'right';
  }

  /// Same as [_edgeFor] but returns `null` if the finger is far from all
  /// the edges (more than [_kEdgeDragBand]). So boxes only "feel" a
  /// target edge when you actually get close to it.
  static const double _kEdgeDragBand = 120.0;
  String? _edgeForBoxDrag(Offset p, Size s) {
    final dyTop = p.dy;
    final dyBottom = s.height - p.dy;
    final dxLeft = p.dx;
    final dxRight = s.width - p.dx;
    final min = [dyTop, dyBottom, dxLeft, dxRight].reduce((a, b) => a < b ? a : b);
    if (min > _kEdgeDragBand) return null;
    if (min == dyTop) return 'top';
    if (min == dyBottom) return 'bottom';
    if (min == dxLeft) return 'left';
    return 'right';
  }

  void _onBarDragUpdate(String which, DragUpdateDetails d, [Color? accent]) {
    final size = MediaQuery.sizeOf(context);
    final String? edge = which.startsWith('edgeBox_')
        ? _edgeForBoxDrag(d.globalPosition, size)
        : _edgeFor(d.globalPosition, size);
    if (which.startsWith('edgeBox_')) {
      final idx = int.tryParse(which.substring('edgeBox_'.length));
      if (idx != null) {
        _boxDragIndex = idx;
        final map = _storage.readConfigMap();
        final boxes = map?['edgeBoxes'];
        if (boxes is List && idx < boxes.length) {
          _boxDragSourceEdge.value =
              (boxes[idx] as Map<String, dynamic>)['edge'] as String? ?? 'bottom';
          _originalBoxOrder ??= List<dynamic>.from(boxes);
        }
        if (edge != null && _originalBoxOrder is List) {
          final shift = _computeBoxShift(idx, d.globalPosition, edge, _originalBoxOrder!);
          if (_liveBoxShift == null ||
              _liveBoxShift!.edge != shift.edge ||
              _liveBoxShift!.insertAt != shift.insertAt) {
            _liveBoxShift = shift;
            setState(() {});
          }
        } else if (_liveBoxShift != null) {
          // Moved away from any edge: returns to the original order.
          _liveBoxShift = null;
          setState(() {});
        }
      }
    }
    // Only updates the wireframe (ValueNotifier); does NOT call setState on the home
    // so as not to rebuild the _EdgeBox and lose the pointer gesture.
    _barDragTarget.value = edge;
    _boxDragPos.value = d.globalPosition;
    if (accent != null) _boxDragAccent.value = accent;
  }

  /// Computes where box [idx] would be inserted within the edge group
  /// [edge] according to the finger position. Used to visually shift the
  /// the target boxes and show the live gap.
  ({int idx, String edge, int insertAt, Size size}) _computeBoxShift(
    int idx,
    Offset global,
    String edge,
    List<dynamic> boxes,
  ) {
    final isVertical = edge == 'left' || edge == 'right';
    final coord = isVertical ? global.dy : global.dx;
    double centerOf(int i) {
      final r = _boxRects[i];
      return r == null ? 0.0 : (isVertical ? r.center.dy : r.center.dx);
    }

    final targetIndices = <int>[];
    for (var i = 0; i < boxes.length; i++) {
      if (i == idx) continue;
      if (((boxes[i] as Map<String, dynamic>)['edge'] as String? ?? 'bottom') == edge) {
        targetIndices.add(i);
      }
    }

    targetIndices.sort((a, b) => centerOf(a).compareTo(centerOf(b)));
    var insertAt = 0;
    for (var i = 0; i < targetIndices.length; i++) {
      if (coord > centerOf(targetIndices[i])) insertAt = i + 1;
    }

    final size = _boxRects[idx]?.size ?? _dropGhostSize(0);
    return (idx: idx, edge: edge, insertAt: insertAt, size: size);
  }

  void _onBarDragEnd(String which, DragEndDetails d) {
    final target = _barDragTarget.value;
    _barDragTarget.value = null;
    if (target != null) {
      _saveSetting(which == 'favorites' ? 'favoritesBarPosition' : 'bottomBarPosition', target);
    }
  }

  Widget _wrapDrag(String which, Widget bar) {
    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onPanUpdate: (d) => _onBarDragUpdate(which, d),
      onPanEnd: (d) => _onBarDragEnd(which, d),
      child: bar,
    );
  }

  Widget _alignBar(String edge, Widget bar) {
    // top/bottom respect the safe area (status bar above, navigation bar
    // bottom) so as not to end up behind the system UI. Since the bars
    // render at the END of the Stack (above the desktop, including the
    // particles), SafeArea does not push them "behind" anything: it simply
    // shifts below the status bar / above the nav bar.
    // left/right keep no lateral safe area.
    final child = Align(
      alignment: switch (edge) {
        'top' => Alignment.topCenter,
        'bottom' => Alignment.bottomCenter,
        'left' => Alignment.centerLeft,
        _ => Alignment.centerRight,
      },
      child: bar,
    );
    if (edge == 'top' || edge == 'bottom') {
      return SafeArea(
        top: edge == 'top',
        bottom: edge == 'bottom',
        left: false,
        right: false,
        child: child,
      );
    }
    return child;
  }

  Widget _wireframeOverlay() {
    return ValueListenableBuilder<String?>(
      valueListenable: _barDragTarget,
      builder: (context, current, _) {
        if (current == null) return const SizedBox.shrink();
        final source = _boxDragSourceEdge.value;
        final accent = _boxDragAccent.value;
        // Glow band along each screen edge, in the style
        // Samsung's curved edge: a gradient that fades toward the
        // center. The edge under the finger is tinted with the box color; the rest
        // stays a faint blank.
        const band = 90.0;
        return IgnorePointer(
          child: Stack(
            children: [
              // Top edge.
              Positioned(
                left: 0,
                top: 0,
                right: 0,
                height: band,
                child: _PoleGlow(
                  key: const ValueKey('pole_top'),
                  edge: 'top',
                  highlight: current == 'top' || source == 'top',
                  color: accent,
                  isDest: current == 'top',
                ),
              ),
              // Bottom edge.
              Positioned(
                left: 0,
                bottom: 0,
                right: 0,
                height: band,
                child: _PoleGlow(
                  key: const ValueKey('pole_bottom'),
                  edge: 'bottom',
                  highlight: current == 'bottom' || source == 'bottom',
                  color: accent,
                  isDest: current == 'bottom',
                ),
              ),
              // Left edge.
              Positioned(
                left: 0,
                top: 0,
                bottom: 0,
                width: band,
                child: _PoleGlow(
                  key: const ValueKey('pole_left'),
                  edge: 'left',
                  highlight: current == 'left' || source == 'left',
                  color: accent,
                  isDest: current == 'left',
                ),
              ),
              // Right edge.
              Positioned(
                right: 0,
                top: 0,
                bottom: 0,
                width: band,
                child: _PoleGlow(
                  key: const ValueKey('pole_right'),
                  edge: 'right',
                  highlight: current == 'right' || source == 'right',
                  color: accent,
                  isDest: current == 'right',
                ),
              ),
              // Phantom box positioned on the target edge (when crossing
              // of edge): shows where the box will land.
              if (current != source)
                ValueListenableBuilder<Offset>(
                  valueListenable: _boxDragPos,
                  builder: (context, pos, _) {
                    return _buildDropGhost(current, pos, accent);
                  },
                ),
            ],
          ),
        );
      },
    );
  }

  /// Renders a phantom box positioned on the target edge, indicating
  /// where the dragged box will land on release. Only shown when crossing from
  /// one edge to another (not when reordering within the same edge).
  Widget _buildDropGhost(String edge, Offset pos, Color accent) {
    final idx = _boxDragIndex;
    if (idx == null) return const SizedBox.shrink();
    final boxes = _originalBoxOrder;
    if (boxes is! List || idx >= boxes.length) return const SizedBox.shrink();
    final box = boxes[idx] as Map<String, dynamic>;
    final items = box['items'] is List ? box['items'] as List : const [];
    final size = MediaQuery.sizeOf(context);
    final mini = _boxRects[idx]?.size ?? _dropGhostSize(items.length);
    final spacing = ((_settings['boxSpacing'] as num?) ?? 1.0).toDouble();

    final (ghostPos, insertAt) = _computeBoxDropSlot(edge, pos, idx, boxes, mini);
    double left, top;
    switch (edge) {
      case 'top':
        left = ghostPos.clamp(8.0, size.width - mini.width - 8);
        top = 16.0;
      case 'bottom':
        left = ghostPos.clamp(8.0, size.width - mini.width - 8);
        top = size.height - mini.height - 16;
      case 'left':
        left = 16.0;
        top = ghostPos.clamp(8.0, size.height - mini.height - 8);
      default:
        left = size.width - mini.width - 16;
        top = ghostPos.clamp(8.0, size.height - mini.height - 8);
    }

    return Positioned(
      left: left,
      top: top,
      child: IgnorePointer(
        child: RepaintBoundary(
          child: Opacity(
            opacity: 0.85,
            child: _EdgeBoxVisual(
              box: box,
              visible: box['visible'] as bool? ?? true,
              accent: accent,
              spacing: spacing,
            ),
          ),
        ),
      ),
    );
  }

  /// Computes where the dragged box will land within the edge group
  /// destination. Returns the position (along the edge) where the
  /// phantom and the insertion index within the group.
  (double, int) _computeBoxDropSlot(String edge, Offset pos, int draggedIndex, List<dynamic> boxes, Size ghostSize) {
    final isVertical = edge == 'left' || edge == 'right';
    final (insertAt, orderedTarget) = _computeBoxDropOrder(edge, pos, draggedIndex, boxes);

    final ghostExtent = isVertical ? ghostSize.height : ghostSize.width;
    const gap = 8.0;
    double position;

    if (orderedTarget.isEmpty) {
      final coord = isVertical ? pos.dy : pos.dx;
      return (coord - ghostExtent / 2, 0);
    }

    if (insertAt == 0) {
      final first = _boxRects[orderedTarget.first];
      final firstStart = first == null ? 0.0 : (isVertical ? first.top : first.left);
      position = firstStart - gap - ghostExtent;
    } else if (insertAt >= orderedTarget.length) {
      final last = _boxRects[orderedTarget.last];
      final lastEnd = last == null ? 0.0 : (isVertical ? last.bottom : last.right);
      position = lastEnd + gap;
    } else {
      final before = _boxRects[orderedTarget[insertAt - 1]];
      final after = _boxRects[orderedTarget[insertAt]];
      final beforeEnd = before == null ? 0.0 : (isVertical ? before.bottom : before.right);
      final afterStart = after == null ? 0.0 : (isVertical ? after.top : after.left);
      position = (beforeEnd + afterStart) / 2 - ghostExtent / 2;
    }

    return (position, insertAt);
  }

  /// Returns the insertion index within the target edge group and the
  /// list of group indices ordered by position.
  (int, List<int>) _computeBoxDropOrder(String edge, Offset pos, int draggedIndex, List<dynamic> boxes) {
    final isVertical = edge == 'left' || edge == 'right';
    final coord = isVertical ? pos.dy : pos.dx;

    final targetIndices = <int>[];
    for (var i = 0; i < boxes.length; i++) {
      if (i == draggedIndex) continue;
      if (((boxes[i] as Map<String, dynamic>)['edge'] as String? ?? 'bottom') == edge) {
        targetIndices.add(i);
      }
    }

    if (targetIndices.isEmpty) return (0, targetIndices);

    targetIndices.sort((a, b) {
      final ra = _boxRects[a];
      final rb = _boxRects[b];
      final ca = ra == null ? 0.0 : (isVertical ? ra.center.dy : ra.center.dx);
      final cb = rb == null ? 0.0 : (isVertical ? rb.center.dy : rb.center.dx);
      return ca.compareTo(cb);
    });

    var insertAt = 0;
    for (var i = 0; i < targetIndices.length; i++) {
      final r = _boxRects[targetIndices[i]];
      final c = r == null ? 0.0 : (isVertical ? r.center.dy : r.center.dx);
      if (coord > c) insertAt = i + 1;
    }

    return (insertAt, targetIndices);
  }

  @override
  Widget build(BuildContext context) {
    final fontFamily = (_settings['fontFamily'] as String? ?? 'Predeterminada');
    final textScale = ((_settings['textScale'] as num?) ?? 1.0).toDouble();
    final baseTheme = Theme.of(context);
    final rawTextTheme = fontFamily == 'Predeterminada'
        ? baseTheme.textTheme
        : (GoogleFonts.asMap().containsKey(fontFamily)
            ? GoogleFonts.getTextTheme(fontFamily, baseTheme.textTheme)
            : baseTheme.textTheme.apply(fontFamily: fontFamily));
    final textTheme = rawTextTheme.apply(fontSizeFactor: textScale);
    final themed = baseTheme.copyWith(textTheme: textTheme);
    final gestureFallback = _systemNavigationMode != 2 &&
        !_accessibilityServiceEnabled &&
        (_settings['gestureNavigationEnabled'] as bool? ?? false);
    // With Android buttons the system bar is painted with the launcher
    // launcher (if it were transparent, MIUI shows it white). In gesture mode
    // yes we leave the bar transparent.
    final useGestures = _systemNavigationMode == 2;
    final systemUi = SystemUiOverlayStyle(
      systemNavigationBarColor: useGestures ? Colors.transparent : const Color(0xFF0B0F14),
      systemNavigationBarDividerColor: Colors.transparent,
      systemNavigationBarContrastEnforced: false,
      statusBarColor: Colors.transparent,
      systemNavigationBarIconBrightness: Brightness.light,
      statusBarIconBrightness: Brightness.light,
    );
    return Theme(
      data: themed,
      child: AnnotatedRegion<SystemUiOverlayStyle>(
          value: systemUi,
          child: Scaffold(
            backgroundColor: Colors.transparent,
            resizeToAvoidBottomInset: false,
            body: Stack(
            children: [
              Positioned.fill(
                child: _busy
                    ? const _BootScreen()
                    : _bootError != null
                        ? _ConfigErrorCard(title: 'Fallo al iniciar', message: _bootError!)
                        : _desktop,
              ),
              // Drawer gestures (swipe-up) and Quake terminal (swipe-down): they
              // re-insert LOWER, above the bars, so that the
              // visible bars (favorites/plugins) do not intercept those gestures.
              // It is placed BEHIND the edge boxes so that taps on
              // these are not intercepted; it only captures swipes on the
              // edges where there is no box.
              _GestureNavigationOverlay(
                enabled: gestureFallback,
              ),
              // Gestures above the bars so that favorites/plugins do not
              // intercept. translucent => taps on bars keep working.
              if (_editWidget == null)
                Positioned(
                  top: MediaQuery.sizeOf(context).height * 0.45,
                  left: 0,
                  right: 0,
                  bottom: MediaQuery.paddingOf(context).bottom + 120,
                  child: RawGestureDetector(
                    behavior: HitTestBehavior.translucent,
                    gestures: <Type, GestureRecognizerFactory>{
                      _YieldingVerticalDragGestureRecognizer:
                          GestureRecognizerFactoryWithHandlers<_YieldingVerticalDragGestureRecognizer>(
                        () => _YieldingVerticalDragGestureRecognizer(),
                        (recognizer) {
                          recognizer.onStart = (d) =>
                              _drawerKey.currentState?.onVerticalDragStart(d);
                          recognizer.onUpdate = (d) =>
                              _drawerKey.currentState?.trackOpenGesture(d.delta.dy);
                          recognizer.onEnd = (d) =>
                              _drawerKey.currentState?.onVerticalDragEnd(d);
                        },
                      ),
                    },
                    child: const SizedBox.expand(),
                  ),
                ),
              if (_quakeEnabled && _editWidget == null)
                Positioned(
                  top: 0,
                  left: 0,
                  right: 0,
                  height: MediaQuery.sizeOf(context).height * 0.45,
                  child: RawGestureDetector(
                    behavior: HitTestBehavior.translucent,
                    gestures: <Type, GestureRecognizerFactory>{
                      _YieldingVerticalDragGestureRecognizer:
                          GestureRecognizerFactoryWithHandlers<_YieldingVerticalDragGestureRecognizer>(
                        () => _YieldingVerticalDragGestureRecognizer(),
                        (recognizer) {
                          recognizer.onStart = (d) {
                            _quakeStartY = d.localPosition.dy;
                            _quakeAccumDy = 0;
                          };
                          recognizer.onUpdate = (d) {
                            _quakeAccumDy += d.delta.dy;
                            final half = MediaQuery.sizeOf(context).height * 0.5;
                            if (!_quakeOpen &&
                                _quakeStartY < half &&
                                _quakeAccumDy > 60) {
                              _openQuake();
                            }
                          };
                        },
                      ),
                    },
                    child: const SizedBox.expand(),
                  ),
                ),
              // Desktop indicator (top).
              if (_desktopCount > 1)
                SafeArea(
                  child: Align(
                    alignment: Alignment.topCenter,
                    child: Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: _DesktopDots(count: _desktopCount, current: _desktopIndex),
                    ),
                  ),
                ),
              // Edge boxes and bars (favorites/plugins): are placed AT THE END
              // of the Stack to guarantee they are ALWAYS visible above
              // of the desktop (including the particle clock, which can occupy
              // almost the whole screen) and above the gesture detectors
              // translucent. Gestures keep working because the
              // detectors are translucent and the bars capture their own taps.
              ..._buildEdgeBoxes(),
              // Wireframe of the target while dragging a bar.
              _wireframeOverlay(),
              // App drawer (appears with swipe up from below).
              _AppDrawer(
                key: _drawerKey,
                apps: _installedApps,
                bottomOffset: MediaQuery.paddingOf(context).bottom,
                onAppTap: _openAppFromDrawer,
                onAppLongPress: _showAppContextMenu,
              ),
              // Debug: shows the zones that capture touches.
              if ((_settings['showTapBoxes'] as bool?) ?? false)
                _TapBoxesOverlay(
                  boxRects: _boxRects,
                  gestureFallback: gestureFallback,
                ),
              // Floating layer of components generated by the AI / local API.
              _buildRuntimeLayer(),
              // Button to open the AI assistant.
              Align(
                alignment: Alignment.bottomRight,
                child: Padding(
                  padding: EdgeInsets.only(
                    bottom: MediaQuery.paddingOf(context).bottom + 20,
                    right: 16,
                  ),
                  child: FloatingActionButton(
                    mini: true,
                    backgroundColor: const Color(0xFF66E0FF),
                    foregroundColor: const Color(0xFF0E141A),
                    onPressed: () => setState(() => _aiPanelOpen = true),
                    child: const Icon(Icons.bolt, size: 20),
                  ),
                ),
              ),
              // AI chat panel.
              if (_aiPanelOpen)
                AiPanel(
                  onSend: _chatWithAi,
                  onClose: () => setState(() => _aiPanelOpen = false),
                  onClear: () => unawaited(_clearRuntimeWidgets()),
                  configured: _buildAiClient().configured,
                ),
              // Quake-style terminal (swipe-down from the upper half of the desktop).
              if (_quakeEnabled)
                AnimatedPositioned(
                  duration: const Duration(milliseconds: 220),
                  curve: Curves.easeOut,
                  top: _quakeOpen
                      ? 0
                      : -MediaQuery.sizeOf(context).height * 0.45,
                  left: 0,
                  right: 0,
                  height: MediaQuery.sizeOf(context).height * 0.45,
                  child: RepaintBoundary(
                    child: ClipRect(
                      child: Column(
                        children: [
                          _QuakeHandle(
                            onClose: _closeQuake,
                            onCopy: () => _quakeTerminalKey.currentState?.copySelection(),
                          ),
                          Expanded(
                            child: QuakeTerminal(
                              key: _quakeTerminalKey,
                              binDir: _binDir,
                              homeDir: _appHomeDir,
                              visible: _quakeOpen,
                              onExit: _closeQuake,
                            ),
                          ),
                          // Bottom edge to close the terminal (tap or swipe-up).
                          GestureDetector(
                            behavior: HitTestBehavior.opaque,
                            onTap: _closeQuake,
                            onVerticalDragUpdate: (d) {
                              if (d.delta.dy < -6) _closeQuake();
                            },
                            child: Container(
                              height: 24,
                              color: const Color(0xFF111820),
                              child: const Center(
                                child: Icon(
                                  Icons.keyboard_arrow_up,
                                  color: Colors.white54,
                                  size: 18,
                                ),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
//  Vertical gesture recognizer that yields to desktop switching
// ---------------------------------------------------------------------------

/// Vertical recognizer that yields the gesture to the desktop PageView when the
/// the swipe is predominantly horizontal, preventing swipes
/// diagonals are "stolen" by the vertical gesture layer and break the switch
/// of desktop. Keeps tracking with the finger for the drawer/Quake.
class _YieldingVerticalDragGestureRecognizer
    extends VerticalDragGestureRecognizer {
  _YieldingVerticalDragGestureRecognizer();

  double _dx = 0;
  double _dy = 0;
  bool _resolved = false;
  bool _rejected = false;

  @override
  void addPointer(PointerDownEvent event) {
    _dx = 0;
    _dy = 0;
    _resolved = false;
    _rejected = false;
    super.addPointer(event);
  }

  @override
  void handleEvent(PointerEvent event) {
    if (_rejected) return;
    if (!_resolved && event is PointerMoveEvent) {
      _dx += event.delta.dx;
      _dy += event.delta.dy;
      // Clear horizontal domain: release the recognizer so the PageView
      // (change of desktop) to receive the gesture. 20px margin.
      if (_dx.abs() > _dy.abs() + 20) {
        _resolved = true;
        _rejected = true;
        resolve(GestureDisposition.rejected);
        return;
      }
      // Clear vertical movement: commit to the vertical axis.
      if (_dy.abs() > 20) _resolved = true;
    }
    super.handleEvent(event);
  }
}

// ---------------------------------------------------------------------------
//  Quake terminal: grab / close bar
// ---------------------------------------------------------------------------

class _QuakeHandle extends StatelessWidget {
  final VoidCallback onClose;
  final VoidCallback? onCopy;
  const _QuakeHandle({required this.onClose, this.onCopy});

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onVerticalDragUpdate: (d) {
        if (d.delta.dy < -6) onClose();
      },
      child: Container(
        height: 26,
        color: const Color(0xFF111820),
        child: Row(
          children: [
            const Expanded(
              child: Center(
                child: Icon(Icons.minimize, color: Colors.white54, size: 16),
              ),
            ),
            if (onCopy != null)
              GestureDetector(
                onTap: onCopy,
                child: const Padding(
                  padding: EdgeInsets.symmetric(horizontal: 10),
                  child: Icon(Icons.copy, color: Colors.white70, size: 15),
                ),
              ),
            GestureDetector(
              onTap: onClose,
              child: const Padding(
                padding: EdgeInsets.symmetric(horizontal: 12),
                child: Icon(Icons.close, color: Colors.white70, size: 16),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
//  Splash screen
// ---------------------------------------------------------------------------

class _BootScreen extends StatelessWidget {
  const _BootScreen();

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          SizedBox(
            width: 28,
            height: 28,
            child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF66E0FF)),
          ),
          SizedBox(height: 16),
          Text('OHM', style: TextStyle(letterSpacing: 6, color: Color(0xFF3A4654), fontSize: 12)),
        ],
      ),
    );
  }
}

/// Drag target for boxes with "neon" glow: pulses the brightness of the
/// and an outer halo while highlighted.
class _PoleGlow extends StatefulWidget {
  const _PoleGlow({
    super.key,
    required this.edge,
    required this.highlight,
    required this.color,
    required this.isDest,
  });

  final String edge;
  final bool highlight;
  final Color color;
  final bool isDest;

  @override
  State<_PoleGlow> createState() => _PoleGlowState();
}

class _PoleGlowState extends State<_PoleGlow>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;
  late final Animation<double> _pulse;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 800),
    );
    _pulse = Tween<double>(begin: 0.5, end: 1.0).animate(
      CurvedAnimation(parent: _ctrl, curve: Curves.easeInOut),
    );
    if (widget.highlight) _ctrl.repeat(reverse: true);
  }

  @override
  void didUpdateWidget(covariant _PoleGlow oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.highlight != oldWidget.highlight) {
      if (widget.highlight) {
        _ctrl.repeat(reverse: true);
      } else {
        _ctrl.stop();
        _ctrl.value = 0;
      }
    }
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.color;
    final base = widget.highlight
        ? (widget.isDest ? c : const Color(0xFFFFFFFF))
        : const Color(0x66FFFFFF);
    return AnimatedBuilder(
      animation: _ctrl,
      builder: (context, _) {
        final p = widget.highlight ? _pulse.value : 0.0;
        final alpha = widget.highlight ? (0.35 + 0.35 * p) : 0.08;
        final core = base.withValues(alpha: alpha);
        final fade = base.withValues(alpha: 0.0);
        // Gradient from the screen edge toward the center: a glow
        // wide and smooth, like Samsung's curved edge.
        final gradient = switch (widget.edge) {
          'top' => LinearGradient(
              begin: Alignment.topCenter,
              end: Alignment.bottomCenter,
              colors: [core, fade],
            ),
          'bottom' => LinearGradient(
              begin: Alignment.bottomCenter,
              end: Alignment.topCenter,
              colors: [core, fade],
            ),
          'left' => LinearGradient(
              begin: Alignment.centerLeft,
              end: Alignment.centerRight,
              colors: [core, fade],
            ),
          _ => LinearGradient(
              begin: Alignment.centerRight,
              end: Alignment.centerLeft,
              colors: [core, fade],
            ),
        };
        return Container(
          decoration: BoxDecoration(gradient: gradient),
        );
      },
    );
  }
}

/// Size of the drop-target phantom box depending on how many items it has.
Size _dropGhostSize(int itemCount) {
  if (itemCount <= 1) return const Size(64, 64);
  if (itemCount <= 4) return const Size(96, 96);
  return const Size(120, 120);
}

// ---------------------------------------------------------------------------
//  Debug: highlights the zones that capture touches (edge boxes and
//  edge gesture detectors) to diagnose blocked taps.
//  It is redrawn every frame while visible to follow changes of
//  shape/position of the boxes.
// ---------------------------------------------------------------------------

class _TapBoxesOverlay extends StatefulWidget {
  const _TapBoxesOverlay({required this.boxRects, required this.gestureFallback});

  final Map<int, Rect> boxRects;
  final bool gestureFallback;

  @override
  State<_TapBoxesOverlay> createState() => _TapBoxesOverlayState();
}

class _TapBoxesOverlayState extends State<_TapBoxesOverlay>
    with SingleTickerProviderStateMixin {
  static const double _kEdgeWidth = 32.0;
  late final Ticker _ticker = Ticker(_onTick)..start();

  void _onTick(Duration _) => setState(() {});

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final size = MediaQuery.sizeOf(context);
    final bottomInset = MediaQuery.paddingOf(context).bottom;
    final children = <Widget>[];

    // Edge boxes.
    for (final entry in widget.boxRects.entries) {
      children.add(
        Positioned.fromRect(
          rect: entry.value,
          child: Container(
            decoration: BoxDecoration(
              border: Border.all(color: Colors.greenAccent, width: 2),
              color: Colors.greenAccent.withValues(alpha: 0.08),
            ),
          ),
        ),
      );
    }

    // Edge gesture detectors (same zones as _GestureNavigationOverlay).
    if (widget.gestureFallback) {
      final detector = BoxDecoration(
        border: Border.all(color: Colors.redAccent, width: 2),
        color: const Color(0x22FF1744),
      );
      final top = size.height - 70 - bottomInset;
      children.addAll([
        Positioned(
          left: 0,
          top: 0,
          bottom: 0,
          width: _kEdgeWidth,
          child: Container(decoration: detector),
        ),
        Positioned(
          right: 0,
          top: 0,
          bottom: 0,
          width: _kEdgeWidth,
          child: Container(decoration: detector),
        ),
        Positioned(
          left: 0,
          right: 0,
          bottom: bottomInset + 8,
          height: 5 + 16,
          child: Center(
            child: Container(
              width: 120,
              height: 5,
              decoration: detector,
            ),
          ),
        ),
        Positioned(
          left: size.width / 2 - 60,
          top: top,
          width: 120,
          height: 5,
          child: Container(decoration: detector),
        ),
      ]);
    }

    return IgnorePointer(child: Stack(children: children));
  }
}
