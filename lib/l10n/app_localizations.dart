import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_es.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale) : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations? of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations);
  }

  static const LocalizationsDelegate<AppLocalizations> delegate = _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates = <LocalizationsDelegate<dynamic>>[
    delegate,
    GlobalMaterialLocalizations.delegate,
    GlobalCupertinoLocalizations.delegate,
    GlobalWidgetsLocalizations.delegate,
  ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('en'),
    Locale('es')
  ];

  /// Title of the Omarchy connection dialog
  ///
  /// In en, this message translates to:
  /// **'Connect with Omarchy'**
  String get connectWithOmarchy;

  /// Subtitle shown above the IP/port in the connection dialog
  ///
  /// In en, this message translates to:
  /// **'Shared connection info'**
  String get sharedConnectionInfo;

  /// Label for the mDNS/QR connection section
  ///
  /// In en, this message translates to:
  /// **'Network connection (mDNS/QR)'**
  String get networkConnectionMdnsQr;

  /// Button to scan for Omarchy peers over Bluetooth
  ///
  /// In en, this message translates to:
  /// **'Scan Bluetooth'**
  String get scanBluetooth;

  /// Status when no Bluetooth peer was found
  ///
  /// In en, this message translates to:
  /// **'No Omarchy peer nearby'**
  String get noPeerNearby;

  /// Dialog close button
  ///
  /// In en, this message translates to:
  /// **'Close'**
  String get close;

  /// Radial menu: add desktop to the left
  ///
  /// In en, this message translates to:
  /// **'Add left'**
  String get addLeft;

  /// Radial menu: add widget
  ///
  /// In en, this message translates to:
  /// **'Add widget'**
  String get addWidget;

  /// Radial menu: add edge box
  ///
  /// In en, this message translates to:
  /// **'Add box'**
  String get addBox;

  /// Radial menu: edit widgets
  ///
  /// In en, this message translates to:
  /// **'Edit widgets'**
  String get editWidgets;

  /// Radial menu: desktop settings
  ///
  /// In en, this message translates to:
  /// **'Desktop settings'**
  String get desktopSettings;

  /// Radial menu: launcher settings
  ///
  /// In en, this message translates to:
  /// **'Launcher settings'**
  String get launcherSettings;

  /// Radial menu: connect to Omarchy
  ///
  /// In en, this message translates to:
  /// **'Connect Omarchy'**
  String get connectOmarchy;

  /// Radial menu: restart launcher
  ///
  /// In en, this message translates to:
  /// **'Restart'**
  String get restart;

  /// Radial menu: add desktop to the right
  ///
  /// In en, this message translates to:
  /// **'Add right'**
  String get addRight;

  /// Snackbar shown after scanning the omarchy:// QR
  ///
  /// In en, this message translates to:
  /// **'Connected to Omarchy: {id} ({ip}:{port})'**
  String connectedToOmarchy(String id, String ip, int port);
}

class _AppLocalizationsDelegate extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) => <String>['en', 'es'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {


  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en': return AppLocalizationsEn();
    case 'es': return AppLocalizationsEs();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.'
  );
}
