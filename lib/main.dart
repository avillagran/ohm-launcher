// ============================================================================
//  OHM LAUNCHER — MVP demo
//  ============================================================================
//  Launcher de Android minimalista inspirado en el ecosistema Omarchy de
//  escritorio:
//
//    * Interfaz autogestionada: la UI se describe con archivos JSON locales
//      en /sdcard/OhmLauncher y se actualiza EN CALIENTE al guardarlos.
//    * Motor de renderizado dinámico propio (sin dependencias de terceros):
//      nodos JSON -> Widgets Flutter reales.
//    * Compatibilidad con el CONTRATO de plugins de Omarchy
//      (omarchyplugins.com): misma especificación de manifest.json, misma
//      estructura de carpetas (plugins/<id>/manifest.json + entry points) y
//      mismo ciclo de hot-reload. La diferencia es que en Android los entry
//      points se escriben en nuestro DSL JSON (no QML, que no se puede
//      interpretar en un dispositivo móvil).
//
//  Estructura de carpetas gestionada:
//    /sdcard/OhmLauncher/
//    ├── widgets_config.json        <-- escritorio reactivo (raíz de la UI)
//    └── plugins/<plugin-id>/
//        ├── manifest.json          <-- contrato Omarchy (schemaVersion 1)
//        ├── BarWidget.json         <-- entry point JSON (bar-widget/bar)
//        └── Panel.json             <-- panel opcional asociado al bar-widget
//
//  Autor: Ohm Launcher
// ============================================================================

import 'dart:async';
import 'dart:convert';
import 'dart:developer';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/services.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter/gestures.dart';

import 'clock_styles.dart';
import 'clock_widget.dart';
import 'plugin_network.dart';
import 'qml_bridge/qml_widgets.dart';

part 'parts/storage.dart';
part 'parts/file_watcher.dart';
part 'parts/dynamic_engine.dart';
part 'parts/desktop_widgets.dart';
part 'parts/plugins.dart';
part 'parts/installer.dart';
part 'parts/platform.dart';
part 'parts/home_screen.dart';
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
//  0. PUNTO DE ENTRADA
// ============================================================================

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setEnabledSystemUIMode(SystemUiMode.edgeToEdge);
  OhmPlatform.init();
  runApp(const OhmApp());
}

/// Raíz de la aplicación: tema oscuro minimalista acorde a Omarchy.
class OhmApp extends StatelessWidget {
  const OhmApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Ohm Launcher',
      debugShowCheckedModeBanner: false,
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

