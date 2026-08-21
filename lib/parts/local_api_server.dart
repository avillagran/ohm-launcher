// ============================================================================
//  LOCAL API SERVER — servidor HTTP en localhost dentro del launcher
// ============================================================================
//  Expone un control remoto del launcher SIN abrir Termux ni la app:
//
//    GET  /health              -> {"ok":true,"name":"OhmLauncher"}
//    POST /command  {command, args?}        -> resultado del shell (embebido/Termux)
//    POST /widget   {source, format?}       -> inyecta un componente (json|qml) en caliente
//    POST /ai       {prompt, history?}      -> chat con la IA configurada
//    POST /install-bin {name, base64}       -> instala un binario propio en la app (herdr/opencode/claude…)
//    GET  /bins                     -> lista los binarios instalados
//    POST /uninstall-bin {name}     -> elimina un binario instalado
//
//  Las acciones pesadas se delegan vía callbacks para mantener el servidor
//  desacoplado de la UI y de los settings.
// ============================================================================

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';

import 'ai_client.dart';
import 'shell_executor.dart';

typedef CommandHandler = Future<ShellResult> Function(String command, List<String>? args);
typedef InjectHandler = Future<void> Function(String source, String format);
typedef ChatHandler = Future<AiResponse> Function(String prompt, List<AiMessage>? history);
typedef InstallBinHandler = Future<Map<String, dynamic>> Function(String name, List<int> bytes);
typedef ListBinsHandler = Future<List<Map<String, dynamic>>> Function();
typedef UninstallBinHandler = Future<Map<String, dynamic>> Function(String name);

class LocalApiServer {
  LocalApiServer({
    this.port = 8753,
    required this.onCommand,
    required this.onInjectWidget,
    this.onChat,
    this.onInstallBin,
    this.onListBins,
    this.onUninstallBin,
  });

  final int port;
  final CommandHandler onCommand;
  final InjectHandler onInjectWidget;
  final ChatHandler? onChat;
  final InstallBinHandler? onInstallBin;
  final ListBinsHandler? onListBins;
  final UninstallBinHandler? onUninstallBin;

  HttpServer? _server;
  bool _running = false;

  bool get isRunning => _running;

  Future<void> start() async {
    if (_running) return;
    try {
      _server = await HttpServer.bind(InternetAddress.loopbackIPv4, port);
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
    // Sin rutas: solo nombre de archivo, caracteres seguros.
    return !name.contains('/') &&
        !name.contains('\\') &&
        !name.contains('..') &&
        RegExp(r'^[A-Za-z0-9._-]+$').hasMatch(name);
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
