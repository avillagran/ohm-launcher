part of 'package:ohm_launcher/main.dart';

// ============================================================================
//  5. INSTALLED APPS (native channel) + platform bridge
// ============================================================================

/// Real installed app on the device, exposed by Android.
class InstalledApp {
  const InstalledApp({
    required this.label,
    required this.package,
    required this.activity,
    this.iconBytes,
  });

  final String label;
  final String package;
  final String activity;
  final Uint8List? iconBytes;

  /// Unique key for favorites (package/activity).
  String get key => '$package/$activity';
}

/// A system (provider) AppWidget that can be added to the desktop.
class SystemWidgetInfo {
  const SystemWidgetInfo({
    required this.label,
    required this.package,
    required this.provider,
    this.minWidth = 0,
    this.minHeight = 0,
  });

  final String label;
  final String package;
  final String provider;
  final int minWidth;
  final int minHeight;
}

/// Bridge with the Android native code (MethodChannel com.ohm/ohm).
class OhmPlatform {
  OhmPlatform._();

  static const MethodChannel _channel = MethodChannel('com.ohm/ohm');

  /// Callbacks for the result of native widget binding.
  static void Function(int id, String provider)? onWidgetBound;
  static void Function(int id, String provider)? onWidgetBindFailed;

  /// Callback when a QR `omarchy://<ip>:<port>?id=<name>` is scanned
  /// (the system camera routes the intent to the launcher and it passes it here).
  static void Function(String ip, int port, String id)? onOmarchyPeerLink;

  /// Initializes the handler for native callbacks (e.g. widget binding).
  static void init() {
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'widgetBound') {
        final args = call.arguments as Map?;
        final id = (args?['id'] as num?)?.toInt() ?? -1;
        final provider = args?['provider'] as String? ?? '';
        onWidgetBound?.call(id, provider);
      } else if (call.method == 'widgetBindFailed') {
        final args = call.arguments as Map?;
        final id = (args?['id'] as num?)?.toInt() ?? -1;
        final provider = args?['provider'] as String? ?? '';
        onWidgetBindFailed?.call(id, provider);
      } else if (call.method == 'onOmarchyPeerLink') {
        final args = call.arguments as Map?;
        final uri = args?['uri'] as String? ?? '';
        final parsed = _parseOmarchyUri(uri);
        if (parsed != null) {
          onOmarchyPeerLink?.call(parsed.$1, parsed.$2, parsed.$3);
        }
      }
      return null;
    });
  }

  /// Parses `omarchy://<ip>:<port>?id=<name>` -> (ip, port, id).
  static (String, int, String)? _parseOmarchyUri(String uri) {
    try {
      if (!uri.startsWith('omarchy://')) return null;
      final u = Uri.parse(uri);
      final host = u.host;
      final port = u.port;
      if (host.isEmpty || port == 0) return null;
      final id = u.queryParameters['id'] ?? host;
      return (host, port, id);
    } catch (_) {
      return null;
    }
  }

  /// Lists the launchable apps on the device (launcher intents).
  static Future<List<InstalledApp>> getInstalledApps() async {
    final raw = await _channel.invokeMethod<List<dynamic>>('getInstalledApps');
    if (raw == null) return const [];
    final apps = <InstalledApp>[];
    for (final e in raw) {
      if (e is! Map) continue;
      apps.add(InstalledApp(
        label: e['label'] is String ? e['label'] as String : 'App',
        package: e['package'] is String ? e['package'] as String : '',
        activity: e['activity'] is String ? e['activity'] as String : '',
        iconBytes: null,
      ));
    }
    return apps;
  }

  static Future<List<SystemWidgetInfo>> getInstalledAppWidgets() async {
    final raw = await _channel.invokeMethod<List<dynamic>>('getInstalledAppWidgets');
    if (raw == null) return const [];
    final widgets = <SystemWidgetInfo>[];
    for (final e in raw) {
      if (e is! Map) continue;
      widgets.add(SystemWidgetInfo(
        label: e['label'] is String ? e['label'] as String : 'Widget',
        package: e['package'] is String ? e['package'] as String : '',
        provider: e['provider'] is String ? e['provider'] as String : '',
        minWidth: (e['minWidth'] as num?)?.toInt() ?? 0,
        minHeight: (e['minHeight'] as num?)?.toInt() ?? 0,
      ));
    }
    return widgets;
  }

  /// Binds a system AppWidget and returns its appWidgetId to render it.
  /// If the system requires explicit authorization, start the bind flow and
  /// returns the id with needsBind=true; the result arrives via _widgetChannel.
  static Future<Map<String, dynamic>> bindAppWidget(String provider) async {
    final raw = await _channel.invokeMethod<dynamic>('bindAppWidget', {'provider': provider});
    if (raw is int) return {'id': raw, 'needsBind': false};
    if (raw is Map) {
      return {
        'id': (raw['id'] as num?)?.toInt() ?? -1,
        'needsBind': raw['needsBind'] as bool? ?? false,
      };
    }
    throw Exception('No se pudo enlazar el widget');
  }

  static Future<void> unbindAppWidget(int id) async {
    try {
      await _channel.invokeMethod<void>('unbindAppWidget', {'id': id});
    } catch (_) {}
  }

  /// Loads a specific app's PNG icon on demand.
  static final Map<String, Uint8List?> _iconCache = {};

  static Future<Uint8List?> getAppIcon(InstalledApp app) async {
    if (app.package.isEmpty) return null;
    final key = '${app.package}/${app.activity}';
    if (_iconCache.containsKey(key)) return _iconCache[key];
    final raw = await _channel.invokeMethod<List<dynamic>>('getAppIcon', {
      'package': app.package,
      'activity': app.activity,
    });
    final bytes = (raw == null || raw.isEmpty) ? null : Uint8List.fromList(raw.cast<int>());
    _iconCache[key] = bytes;
    return bytes;
  }

  static Uint8List? peekIcon(InstalledApp app) {
    if (app.package.isEmpty) return null;
    return _iconCache['${app.package}/${app.activity}'];
  }

  /// Launches an installed application.
  static Future<bool> launchApp(InstalledApp app) async {
    final ok = await _channel.invokeMethod<bool>('launchApp', {
      'package': app.package,
      'activity': app.activity,
    });
    return ok ?? false;
  }

  /// Battery percentage (0-100) or -1 if not available.
  static Future<int> getBatteryLevel() async {
    final level = await _channel.invokeMethod<int>('getBatteryLevel');
    return level ?? -1;
  }

  /// True if Ohm Launcher is the system default launcher.
  static Future<bool> isDefaultLauncher() async {
    final ok = await _channel.invokeMethod<bool>('isDefaultLauncher');
    return ok ?? false;
  }

  /// Opens the system picker to choose the default launcher.
  static Future<void> requestDefaultLauncher() =>
      _channel.invokeMethod<void>('requestDefaultLauncher');

  /// Opens the app info screen (to manage permissions).
  static Future<void> openAppSettings() async {
    await _channel.invokeMethod<void>('openAppSettings');
  }

  /// Opens system Settings -> Navigation (gestures) after switching launcher.
  static Future<void> openNavigationSettings() async {
    try {
      await _channel.invokeMethod<void>('openNavigationSettings');
    } catch (_) {}
  }

  /// Returns the system navigation mode: 0=buttons, 2=gestures.
  static Future<int> getNavigationMode() async {
    try {
      final v = await _channel.invokeMethod<int>('getNavigationMode');
      return v ?? 0;
    } catch (_) {
      return 0;
    }
  }

  /// Restores gesture navigation on Xiaomi/HyperOS (navigation_mode=2).
  /// Returns true if successful. Requires WRITE_SECURE_SETTINGS granted via ADB.
  static Future<bool> restoreGestureNavigation() async {
    try {
      final ok = await _channel.invokeMethod<bool>('restoreGestureNavigation');
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Opens the accessibility settings so the user can enable the gesture service.
  static Future<void> openAccessibilitySettings() async {
    try {
      await _channel.invokeMethod<void>('openAccessibilitySettings');
    } catch (_) {}
  }

  /// Indicates whether the Ohm gesture accessibility service is active.
  static Future<bool> isGestureAccessibilityEnabled() async {
    try {
      final enabled = await _channel.invokeMethod<bool>('isGestureAccessibilityEnabled');
      return enabled ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Opens recent apps (system recents) if possible.
  static Future<void> openRecents() async {
    try {
      await _channel.invokeMethod<void>('openRecents');
    } catch (_) {}
  }

  /// Restarts the launcher (recreates the Activity and the Flutter engine).
  static Future<void> restartApp() async {
    try {
      await _channel.invokeMethod<void>('restartApp');
    } catch (_) {}
  }

  /// Starts the background clipboard monitor that pushes copied text to the
  /// connected Omarchy peer PC. [ip]/[port] identify the peer; pass null to
  /// stop monitoring.
  static Future<void> startClipboardMonitor(String ip, int port) async {
    try {
      await _channel.invokeMethod<void>('startClipboardMonitor', {
        'ip': ip,
        'port': port,
      });
    } catch (_) {}
  }

  /// Stops the background clipboard monitor.
  static Future<void> stopClipboardMonitor() async {
    try {
      await _channel.invokeMethod<void>('stopClipboardMonitor');
    } catch (_) {}
  }

  /// Enables/disables immersive mode (hides the system navigation bar).
  static Future<void> setImmersiveMode(bool enabled) async {
    try {
      await _channel.invokeMethod<void>('setImmersiveMode', {'enabled': enabled});
    } catch (_) {}
  }
}
