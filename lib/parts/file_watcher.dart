part of 'package:ohm_launcher/main.dart';

// ============================================================================
//  2. FILE WATCHER ENGINE
//  ============================================================================
//  Listens for changes in widgets_config.json and in the plugins folder.
//  400ms debounce: mobile editors (Acode, etc.) usually write in
//  bursts and we want to reload ONCE per save.
// ============================================================================

class FileWatcherEngine {
  FileWatcherEngine({this.debounce = const Duration(milliseconds: 400)});

  final Duration debounce;

  final List<StreamSubscription<FileSystemEvent>> _subscriptions = [];
  Timer? _debounceTimer;

  /// Watches [configDir] (widgets_config.json file) and [pluginsDir]
  /// (recursive, to reload any manifest or entry point).
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
        // If the watcher fails (e.g. folder not yet created) it is ignored:
        // manual reload from the UI still works.
      });
      _subscriptions.add(sub);
    } catch (_) {/* non-existent folder or no watch support */}
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
