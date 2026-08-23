// ============================================================================
//  LOCAL API SERVER — HTTP server on localhost inside the launcher
// ============================================================================
//  Exposes a remote control of the launcher WITHOUT opening Termux or the app:
//
//    GET  /health              -> {"ok":true,"name":"OhmLauncher"}
//    POST /command  {command, args?}        -> shell result (embedded/Termux)
//    POST /widget   {source, format?}       -> inject a component (json|qml) hot
//    POST /ai       {prompt, history?}      -> chat with the configured AI
//    POST /install-bin {name, base64}       -> install an own binary in the app (herdr/opencode/claude…)
//    GET  /bins                     -> list installed binaries
//    POST /uninstall-bin {name}     -> remove an installed binary
//
//  Heavy actions are delegated via callbacks to keep the server
//  decoupled from the UI and the settings.
// ============================================================================

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'ai_client.dart';
import 'shell_executor.dart';
import 'omarchy_link.dart';

typedef CommandHandler = Future<ShellResult> Function(String command, List<String>? args);
typedef InjectHandler = Future<void> Function(String source, String format);
typedef ChatHandler = Future<AiResponse> Function(String prompt, List<AiMessage>? history);
typedef InstallBinHandler = Future<Map<String, dynamic>> Function(String name, List<int> bytes);
typedef InstallBinRawHandler = Future<Map<String, dynamic>> Function(String name, Stream<List<int>> bytes);
typedef ListBinsHandler = Future<List<Map<String, dynamic>>> Function();
typedef UninstallBinHandler = Future<Map<String, dynamic>> Function(String name);
typedef QuakeHandler = void Function(bool open);

class LocalApiServer {
  LocalApiServer({
    this.port = 8753,
    required this.onCommand,
    required this.onInjectWidget,
    this.onChat,
    this.onInstallBin,
    this.onInstallBinRaw,
    this.onListBins,
    this.onUninstallBin,
    this.onQuake,
    this.omarchyLink,
    this.lanMode = false,
  });

  final int port;
  final CommandHandler onCommand;
  final InjectHandler onInjectWidget;
  final ChatHandler? onChat;
  final InstallBinHandler? onInstallBin;
  final InstallBinRawHandler? onInstallBinRaw;
  final ListBinsHandler? onListBins;
  final UninstallBinHandler? onUninstallBin;
  final QuakeHandler? onQuake;
  final OmarchyLink? omarchyLink;
  /// true => listens on all interfaces (reachable from the LAN for the
  /// integration with Omarchy); false => loopback only (more secure).
  final bool lanMode;

  HttpServer? _server;
  bool _running = false;

  bool get isRunning => _running;

  Future<void> start() async {
    if (_running) return;
    try {
      final addr = lanMode ? InternetAddress.anyIPv4 : InternetAddress.loopbackIPv4;
      _server = await HttpServer.bind(addr, port);
      _running = true;
      unawaited(_serve());
    } on SocketException catch (e) {
      _running = false;
      if (kDebugMode) print('[LocalApiServer] no pudo enlazar :$port -> $e');
    }
  }

  Future<void> stop() async {
    _running = false;
    await _server?.close(force: true);
    _server = null;
  }

  Future<void> _serve() async {
    await for (final request in _server!) {
      _handle(request).ignore();
    }
  }

  Future<void> _handle(HttpRequest request) async {
    // Omarchy integration: delegates all /omarchy/* routes to the link module.
    if (omarchyLink != null) {
      if (request.uri.path == '/omarchy/ws') {
        await omarchyLink!.handleWs(request);
        return;
      }
      if (request.uri.path.startsWith('/omarchy/')) {
        await omarchyLink!.handleRest(request);
        return;
      }
    }
    _cors(request);
    if (request.method == 'OPTIONS') return;
    try {
      if (request.method == 'GET' && request.uri.path == '/health') {
        return _json(request, 200, {'ok': true, 'name': 'OhmLauncher'});
      }
      if (request.method == 'GET' && request.uri.path == '/bins') {
        if (onListBins == null) {
          return _json(request, 501, {'error': 'install_not_supported'});
        }
        final bins = await onListBins!();
        return _json(request, 200, {'bins': bins});
      }
      if (request.method != 'POST') {
        return _json(request, 405, {'error': 'method_not_allowed'});
      }
      if (request.uri.path == '/install-bin-raw') {
        return _installBinRaw(request);
      }
      final body = await _readBody(request);
      switch (request.uri.path) {
        case '/command':
          final cmd = body['command'] as String? ?? '';
          if (cmd.isEmpty) {
            return _json(request, 400, {'error': 'missing_command'});
          }
          final args = (body['args'] as List?)?.map((e) => '$e').toList();
          final result = await onCommand(cmd, args);
          return _json(request, 200, result.toJson());
        case '/widget':
          final source = body['source'] as String? ?? '';
          if (source.isEmpty) {
            return _json(request, 400, {'error': 'missing_source'});
          }
          final format = (body['format'] as String? ?? 'json').toLowerCase();
          await onInjectWidget(source, format);
          return _json(request, 200, {'ok': true});
        case '/ai':
          if (onChat == null) {
            return _json(request, 501, {'error': 'ai_not_configured'});
          }
          final prompt = body['prompt'] as String? ?? '';
          if (prompt.isEmpty) {
            return _json(request, 400, {'error': 'missing_prompt'});
          }
          final history = _parseHistory(body['history']);
          final resp = await onChat!(prompt, history);
          return _json(request, 200, {
            'text': resp.text,
            'widgetSource': resp.widgetSource,
            'widgetFormat': resp.widgetFormat,
          });
        case '/install-bin':
          if (onInstallBin == null) {
            return _json(request, 501, {'error': 'install_not_supported'});
          }
          final name = (body['name'] as String?)?.trim() ?? '';
          final b64 = (body['base64'] as String?)?.trim() ?? '';
          if (name.isEmpty || !_validBinName(name)) {
            return _json(request, 400, {'error': 'invalid_name'});
          }
          if (b64.isEmpty) {
            return _json(request, 400, {'error': 'missing_base64'});
          }
          List<int> bytes;
          try {
            bytes = base64Decode(b64);
          } catch (_) {
            return _json(request, 400, {'error': 'bad_base64'});
          }
          final res = await onInstallBin!(name, bytes);
          return _json(request, 200, res);
        case '/bins':
          if (onListBins == null) {
            return _json(request, 501, {'error': 'install_not_supported'});
          }
          final bins = await onListBins!();
          return _json(request, 200, {'bins': bins});
        case '/uninstall-bin':
          if (onUninstallBin == null) {
            return _json(request, 501, {'error': 'install_not_supported'});
          }
          final name = (body['name'] as String?)?.trim() ?? '';
          if (name.isEmpty || !_validBinName(name)) {
            return _json(request, 400, {'error': 'invalid_name'});
          }
          final res = await onUninstallBin!(name);
          return _json(request, 200, res);
        case '/quake':
          if (onQuake == null) {
            return _json(request, 501, {'error': 'quake_not_supported'});
          }
          final open = (body['open'] as bool?) ?? true;
          onQuake!(open);
          return _json(request, 200, {'ok': true, 'open': open});
        default:
          return _json(request, 404, {'error': 'not_found'});
      }
    } catch (e) {
      _json(request, 500, {'error': 'server_error', 'detail': '$e'});
    }
  }

  List<AiMessage>? _parseHistory(Object? raw) {
    if (raw is! List) return null;
    final out = <AiMessage>[];
    for (final item in raw) {
      if (item is Map && item['role'] is String && item['content'] is String) {
        out.add(AiMessage(role: item['role'] as String, content: item['content'] as String));
      }
    }
    return out.isNotEmpty ? out : null;
  }

  bool _validBinName(String name) {
    // No paths: only file name, safe characters.
    return !name.contains('/') &&
        !name.contains('\\') &&
        !name.contains('..') &&
        RegExp(r'^[A-Za-z0-9._-]+$').hasMatch(name);
  }

  /// Installs a binary receiving the bytes raw (no base64/JSON), which
  /// supports large files (e.g. bun ~90 MB) without loading them all into memory.
  /// Usage: POST /install-bin-raw?name=<bin>  (body = executable bytes).
  Future<void> _installBinRaw(HttpRequest request) async {
    final name = request.uri.queryParameters['name']?.trim() ?? '';
    if (name.isEmpty || !_validBinName(name)) {
      return _json(request, 400, {'error': 'invalid_name'});
    }
      if (onInstallBinRaw == null) {
        return _json(request, 501, {'error': 'install_not_supported'});
      }
      try {
        final res = await onInstallBinRaw!(name, request.expand((b) => [b]));
        return _json(request, 200, res);
      } catch (e) {
        return _json(request, 500, {'error': 'install_failed', 'detail': '$e'});
      }
    }

  Future<Map<String, dynamic>> _readBody(HttpRequest request) async {
    try {
      final data = await utf8.decoder.bind(request).join();
      if (data.isEmpty) return const {};
      final decoded = jsonDecode(data);
      return decoded is Map<String, dynamic> ? decoded : const {};
    } catch (_) {
      return const {};
    }
  }

  void _cors(HttpRequest request) {
    request.response.headers.set('Access-Control-Allow-Origin', '*');
    request.response.headers.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    request.response.headers.set('Access-Control-Allow-Headers', 'Content-Type');
    if (request.method == 'OPTIONS') {
      request.response
        ..statusCode = 204
        ..close();
    }
  }

  void _json(HttpRequest request, int code, Map<String, dynamic> data) {
    if (!request.response.headers.persistentConnection) return;
    request.response
      ..statusCode = code
      ..headers.contentType = ContentType.json
      ..write(jsonEncode(data))
      ..close();
  }
}
