// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get connectWithOmarchy => 'Connect with Omarchy';

  @override
  String get sharedConnectionInfo => 'Shared connection info';

  @override
  String get networkConnectionMdnsQr => 'Network connection (mDNS/QR)';

  @override
  String get scanBluetooth => 'Scan Bluetooth';

  @override
  String get noPeerNearby => 'No Omarchy peer nearby';

  @override
  String get close => 'Close';

  @override
  String get addLeft => 'Add left';

  @override
  String get addWidget => 'Add widget';

  @override
  String get addBox => 'Add box';

  @override
  String get editWidgets => 'Edit widgets';

  @override
  String get desktopSettings => 'Desktop settings';

  @override
  String get launcherSettings => 'Launcher settings';

  @override
  String get connectOmarchy => 'Connect Omarchy';

  @override
  String get restart => 'Restart';

  @override
  String get addRight => 'Add right';

  @override
  String connectedToOmarchy(String id, String ip, int port) {
    return 'Connected to Omarchy: $id ($ip:$port)';
  }
}
