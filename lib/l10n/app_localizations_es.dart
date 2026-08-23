// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Spanish Castilian (`es`).
class AppLocalizationsEs extends AppLocalizations {
  AppLocalizationsEs([String locale = 'es']) : super(locale);

  @override
  String get connectWithOmarchy => 'Conectar con Omarchy';

  @override
  String get sharedConnectionInfo => 'Información de conexión compartida';

  @override
  String get networkConnectionMdnsQr => 'Conexión en la red (mDNS/QR)';

  @override
  String get scanBluetooth => 'Escanear Bluetooth';

  @override
  String get noPeerNearby => 'Ningún peer Omarchy cercano';

  @override
  String get close => 'Cerrar';

  @override
  String get addLeft => 'Agregar izquierda';

  @override
  String get addWidget => 'Agregar widget';

  @override
  String get addBox => 'Agregar caja';

  @override
  String get editWidgets => 'Editar widgets';

  @override
  String get desktopSettings => 'Config escritorio';

  @override
  String get launcherSettings => 'Config launcher';

  @override
  String get connectOmarchy => 'Conectar Omarchy';

  @override
  String get restart => 'Reiniciar';

  @override
  String get addRight => 'Agregar derecha';

  @override
  String connectedToOmarchy(String id, String ip, int port) {
    return 'Conectado a Omarchy: $id ($ip:$port)';
  }
}
