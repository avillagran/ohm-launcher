// ============================================================================
//  OHM LAUNCHER — MVP demo
//  ============================================================================
//  Minimalist Android launcher inspired by the Omarchy ecosystem of
//  desktop:
//
//    * Self-managed interface: the UI is described with local JSON files
//      in /sdcard/OhmLauncher and updates HOT when saved.
//    * Own dynamic rendering engine (no third-party dependencies):
//      JSON nodes -> real Flutter Widgets.
//    * Compatibility with the Omarchy plugin CONTRACT
//      (omarchyplugins.com): same manifest.json spec, same
//      folder structure (plugins/<id>/manifest.json + entry points) and
//      same hot-reload cycle. On Android entry points can be
//      our JSON DSL (BarWidget.json) OR QML (BarWidget.qml): the QML bridge
//      (lib/qml_bridge) interprets QML hot WITHOUT compiling, so a
//      AI or an editor can modify the .qml and the UI updates on
//      re-scan the plugins folder (FileWatcherEngine, 400ms debounce).
//
//  Folder structure managed:
//    /sdcard/OhmLauncher/
//    ├── widgets_config.json        <-- reactive desktop (UI root)
//    └── plugins/<plugin-id>/
//        ├── manifest.json          <-- Omarchy contract (schemaVersion 1)
//        ├── BarWidget.json         <-- entry point JSON (bar-widget/bar)
//        └── Panel.json             <-- optional panel associated with the bar-widget
//
//  Author: Ohm Launcher
// ============================================================================

import 'dart:async';
import 'dart:convert';
import 'dart:developer';
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:ohm_launcher/l10n/app_localizations.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:flutter_pty/flutter_pty.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:xterm/xterm.dart';
import 'package:flutter/gestures.dart';

import 'clock_styles.dart';
import 'clock_widget.dart';
import 'plugin_network.dart';
import 'qml_bridge/qml_widgets.dart';
import 'parts/ai_client.dart';
import 'parts/ai_panel.dart';
import 'parts/local_api_server.dart';
import 'parts/omarchy_link.dart';
import 'parts/omarchy_discovery.dart';
import 'parts/screen_capture.dart';
import 'parts/shell_executor.dart';
import 'package:xterm/src/ui/render.dart';
import 'package:xterm/src/core/buffer/cell_offset.dart';

part 'parts/omarchy_control.dart';

// Access to xterm's internal geometry to place selection handles
// natives (RenderTerminal exposes cellSize / getOffset / getCellOffset
// public; we use them to position handles pixel-perfect without a fork).

part 'parts/storage.dart';
part 'parts/file_watcher.dart';
part 'parts/dynamic_engine.dart';
part 'parts/desktop_widgets.dart';
part 'parts/plugins.dart';
part 'parts/installer.dart';
part 'parts/platform.dart';
part 'parts/home_screen.dart';
part 'parts/quake_terminal.dart';
part 'parts/status_bar.dart';
part 'parts/edge_boxes.dart';
part 'parts/bars.dart';
part 'parts/gesture_overlay.dart';
part 'parts/radial_menu.dart';
part 'parts/pickers.dart';
part 'parts/settings.dart';
part 'parts/app_drawer.dart';
part 'parts/bottom_bar.dart';
part 'parts/marketplace.dart';
part 'parts/errors.dart';

// ============================================================================
//  0. ENTRY POINT
// ============================================================================

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  OhmPlatform.init();
  runApp(const OhmApp());
}

/// Root of the application: minimalist dark theme matching Omarchy.
class OhmApp extends StatelessWidget {
  const OhmApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Ohm Launcher',
      debugShowCheckedModeBanner: false,
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      supportedLocales: AppLocalizations.supportedLocales,
      // Respect the system locale; fall back to Spanish if unsupported.
      localeListResolutionCallback: (locales, supported) {
        for (final l in locales ?? const []) {
          final match = supported.firstWhere(
            (s) => s.languageCode == l.languageCode,
            orElse: () => const Locale('es'),
          );
          if (match.languageCode != 'es' || l.languageCode == 'es') return match;
        }
        return const Locale('es');
      },
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF0B0F14),
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF66E0FF),
          brightness: Brightness.dark,
        ),
      ),
      home: const OhmHomeScreen(),
    );
  }
}

