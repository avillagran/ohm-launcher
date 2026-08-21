part of 'package:ohm_launcher/main.dart';

// ============================================================================
//  2. MOTOR DE VIGILANCIA DE ARCHIVOS (File Watcher Engine)
//  ============================================================================
//  Escucha cambios en widgets_config.json y en la carpeta de plugins.
//  Debounce de 400ms: los editores móviles (Acode, etc.) suelen escribir en
//  ráfagas y queremos recargar UNA vez por guardado.
// ============================================================================

class FileWatcherEngine {
  FileWatcherEngine({this.debounce = const Duration(milliseconds: 400)});

  final Duration debounce;

  final List<StreamSubscription<FileSystemEvent>> _subscriptions = [];
  Timer? _debounceTimer;

  /// Vigila [configDir] (archivo widgets_config.json) y [pluginsDir]
  /// (recursivo, para recargar cualquier manifest o entry point).
  void start({
    required Directory configDir,
    required Directory pluginsDir,
    required VoidCallback onConfigChanged,
    required VoidCallback onPluginsChanged,
  }) {
    stop();
    _watch(
      configDir,
      onlyFiles: const {StorageService.kConfigFileName},
      onFire: onConfigChanged,
      recursive: false,
    );
    _watch(pluginsDir, onlyFiles: null, onFire: onPluginsChanged, recursive: true);
  }

  void _watch(
    Directory dir, {
    Set<String>? onlyFiles,
    required VoidCallback onFire,
    required bool recursive,
  }) {
    try {
      final sub = dir.watch(recursive: recursive).listen((event) {
        if (onlyFiles != null && !onlyFiles.contains(_basename(event.path))) return;
        _debounceTimer?.cancel();
        _debounceTimer = Timer(debounce, onFire);
      }, onError: (Object _) {
        // Si el watcher falla (p. ej. carpeta aún no creada) se ignora:
        // la recarga manual desde la UI sigue funcionando.
      });
      _subscriptions.add(sub);
    } catch (_) {/* carpeta inexistente o sin soporte de watch */}
  }

  void stop() {
    for (final sub in _subscriptions) {
      sub.cancel();
    }
    _subscriptions.clear();
    _debounceTimer?.cancel();
    _debounceTimer = null;
  }
}

String _basename(String path) => path.split(Platform.pathSeparator).last;
