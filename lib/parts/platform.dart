part of 'package:ohm_launcher/main.dart';

// ============================================================================
//  5. APLICACIONES INSTALADAS (canal nativo) + puente de plataforma
// ============================================================================

/// Aplicación real instalada en el dispositivo, expuesta por Android.
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

  /// Clave única para favoritos (package/activity).
  String get key => '$package/$activity';
}

/// Un AppWidget del sistema (proveedor) que se puede añadir al escritorio.
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

/// Puente con el código nativo de Android (MethodChannel com.ohm/ohm).
class OhmPlatform {
  OhmPlatform._();

  static const MethodChannel _channel = MethodChannel('com.ohm/ohm');

  /// Callbacks para el resultado del binding de widgets nativos.
  static void Function(int id, String provider)? onWidgetBound;
  static void Function(int id, String provider)? onWidgetBindFailed;

  /// Inicializa el handler para callbacks nativos (p. ej. binding de widgets).
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
      }
      return null;
    });
  }

  /// Lista las apps lanzables del dispositivo (launcher intents).
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

  /// Enlaza un AppWidget del sistema y devuelve su appWidgetId para renderizarlo.
  /// Si el sistema requiere autorización explícita, inicia el flujo de bind y
  /// devuelve el id con needsBind=true; el resultado llega por _widgetChannel.
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

  /// Carga el icono PNG de una app concreta bajo demanda.
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

  /// Lanza una aplicación instalada.
  static Future<bool> launchApp(InstalledApp app) async {
    final ok = await _channel.invokeMethod<bool>('launchApp', {
      'package': app.package,
      'activity': app.activity,
    });
    return ok ?? false;
  }

  /// Porcentaje de batería (0-100) o -1 si no está disponible.
  static Future<int> getBatteryLevel() async {
    final level = await _channel.invokeMethod<int>('getBatteryLevel');
    return level ?? -1;
  }

  /// True si Ohm Launcher es el launcher por defecto del sistema.
  static Future<bool> isDefaultLauncher() async {
    final ok = await _channel.invokeMethod<bool>('isDefaultLauncher');
    return ok ?? false;
  }

  /// Abre el selector del sistema para elegir el launcher por defecto.
  static Future<void> requestDefaultLauncher() =>
      _channel.invokeMethod<void>('requestDefaultLauncher');

  /// Abre la pantalla de información de la app (para gestionar permisos).
  static Future<void> openAppSettings() async {
    await _channel.invokeMethod<void>('openAppSettings');
  }

  /// Abre los Ajustes del sistema → Navegación (gestos) tras cambiar de launcher.
  static Future<void> openNavigationSettings() async {
    try {
      await _channel.invokeMethod<void>('openNavigationSettings');
    } catch (_) {}
  }

  /// Devuelve el modo de navegación del sistema: 0=botones, 2=gestos.
  static Future<int> getNavigationMode() async {
    try {
      final v = await _channel.invokeMethod<int>('getNavigationMode');
      return v ?? 0;
    } catch (_) {
      return 0;
    }
  }

  /// Restaura la navegación por gestos en Xiaomi/HyperOS (navigation_mode=2).
  /// Devuelve true si tuvo éxito. Requiere WRITE_SECURE_SETTINGS concedido por ADB.
  static Future<bool> restoreGestureNavigation() async {
    try {
      final ok = await _channel.invokeMethod<bool>('restoreGestureNavigation');
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Abre los Ajustes de accesibilidad para que el usuario active el servicio de gestos.
  static Future<void> openAccessibilitySettings() async {
    try {
      await _channel.invokeMethod<void>('openAccessibilitySettings');
    } catch (_) {}
  }

  /// Indica si el servicio de accesibilidad de gestos de Ohm está activo.
  static Future<bool> isGestureAccessibilityEnabled() async {
    try {
      final enabled = await _channel.invokeMethod<bool>('isGestureAccessibilityEnabled');
      return enabled ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Abre las aplicaciones recientes (system recents) si es posible.
  static Future<void> openRecents() async {
    try {
      await _channel.invokeMethod<void>('openRecents');
    } catch (_) {}
  }

  /// Reinicia el launcher (recrea la Activity y el motor Flutter).
  static Future<void> restartApp() async {
    try {
      await _channel.invokeMethod<void>('restartApp');
    } catch (_) {}
  }

  /// Activa/desactiva el modo inmersivo (oculta barra de navegación del sistema).
  static Future<void> setImmersiveMode(bool enabled) async {
    try {
      await _channel.invokeMethod<void>('setImmersiveMode', {'enabled': enabled});
    } catch (_) {}
  }
}
