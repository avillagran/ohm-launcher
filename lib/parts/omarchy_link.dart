// ============================================================================
//  OMARCHY LINK — OhmLauncher <-> Omarchy integration (Quickshell/QtQuick)
// ============================================================================
//  Exposes, on the same local API server (port 8753), a group of routes
//  `/omarchy/*` for synchronization between the launcher (Android) and the desktop
//  Omarchy (Linux). The Omarchy QML plugin (see examples/plugins) consumes
//  this contract and provides auto-detection UI (mDNS/QR) + 5 actions:
//
//    - Share files       (POST/GET /omarchy/file)
//    - Sync clipboard   (GET/PUT /omarchy/clipboard)
//    - Share screen      (POST /omarchy/screen/start|stop)  [scrcpy-like]
//    - Back up photos         (POST /omarchy/photos/backup)
//    - Sync themes      (GET/PUT /omarchy/theme)
//
//  Real-time events over WebSocket: ws://<ip>:8753/omarchy/ws
//    {type:'peer_hello', ...}  {type:'clipboard_changed', text}  {type:'progress', ...}
//
//  Heavy handlers are delegated via callbacks to keep this module
//  decoupled from the launcher UI/settings.
// ============================================================================

import 'dart:async';
import 'dart:convert';
import 'dart:io';

/// Callbacks that the launcher connects to its real capabilities.
typedef DiscoverInfo = Future<Map<String, dynamic>> Function();
typedef ClipboardGetter = Future<String> Function();
typedef ClipboardSetter = Future<void> Function(String text);
typedef FileReceiver = Future<Map<String, dynamic>> Function(String name, List<int> bytes);
typedef FileSender = Future<List<int>> Function(String path);
typedef ThemeGetter = Future<Map<String, dynamic>> Function();
typedef ThemeSetter = Future<void> Function(Map<String, dynamic> theme);
typedef ScreenStarter = Future<Map<String, dynamic>> Function();
typedef ScreenStopper = Future<void> Function();
typedef PhotosBackup = Future<Map<String, dynamic>> Function();

/// Provides a single screen frame (e.g. a PNG capture of the launcher UI) as bytes.
typedef ScreenFrameProvider = Future<List<int>> Function();

/// Handles the OhmLauncher <-> Omarchy integration contract.
class OmarchyLink {
  OmarchyLink({
    this.onDiscover,
    this.onClipboardGet,
    this.onClipboardSet,
    this.onFileReceive,
    this.onFileSend,
    this.onThemeGet,
    this.onThemeSet,
    this.onScreenStart,
    this.onScreenStop,
    this.onPhotosBackup,
    this.onScreenFrame,
  });

  final DiscoverInfo? onDiscover;
  final ClipboardGetter? onClipboardGet;
  final ClipboardSetter? onClipboardSet;
  final FileReceiver? onFileReceive;
  final FileSender? onFileSend;
  final ThemeGetter? onThemeGet;
  final ThemeSetter? onThemeSet;
  final ScreenStarter? onScreenStart;
  final ScreenStopper? onScreenStop;
  final PhotosBackup? onPhotosBackup;
  final ScreenFrameProvider? onScreenFrame;

  final Set<WebSocket> _clients = {};
  bool _screenActive = false;

  /// Emits an event to all connected WebSocket clients.
  void broadcast(Map<String, dynamic> event) {
    final msg = jsonEncode(event);
    for (final c in _clients) {
      if (c.readyState == WebSocket.open) c.add(msg);
    }
  }

  /// Handles a REST request under /omarchy/*.
  Future<void> handleRest(HttpRequest request) async {
    _cors(request);
    if (request.method == 'OPTIONS') return;
    try {
      final path = request.uri.path;
      if (request.method == 'GET' && path == '/omarchy/discover') {
        final info = await (onDiscover?.call() ?? Future.value(_defaultDiscover()));
        return _json(request, 200, info);
      }
      if (path == '/omarchy/ws') {
        return; // it is handled by handleWs separately (upgrade)
      }
      if (request.method == 'GET' && path == '/omarchy/clipboard') {
        if (onClipboardGet == null) return _json(request, 501, _unsupported('clipboard'));
        final text = await onClipboardGet!();
        return _json(request, 200, {'text': text});
      }
      if (request.method == 'PUT' && path == '/omarchy/clipboard') {
        if (onClipboardSet == null) return _json(request, 501, _unsupported('clipboard'));
        final body = await _readBody(request);
        final text = body['text'] as String? ?? '';
        await onClipboardSet!(text);
        broadcast({'type': 'clipboard_changed', 'text': text});
        return _json(request, 200, {'ok': true});
      }
      if (request.method == 'GET' && path == '/omarchy/theme') {
        if (onThemeGet == null) return _json(request, 501, _unsupported('theme'));
        return _json(request, 200, await onThemeGet!());
      }
      if (request.method == 'PUT' && path == '/omarchy/theme') {
        if (onThemeSet == null) return _json(request, 501, _unsupported('theme'));
        final body = await _readBody(request);
        await onThemeSet!(body);
        return _json(request, 200, {'ok': true});
      }
      if (request.method == 'POST' && path == '/omarchy/screen/start') {
        if (onScreenStart == null) return _json(request, 501, _unsupported('screen'));
        final r = await onScreenStart!();
        broadcast({'type': 'screen_started'});
        return _json(request, 200, r);
      }
      if (request.method == 'POST' && path == '/omarchy/screen/stop') {
        if (onScreenStop == null) return _json(request, 501, _unsupported('screen'));
        await onScreenStop!();
        broadcast({'type': 'screen_stopped'});
        return _json(request, 200, {'ok': true});
      }
      if (request.method == 'POST' && path == '/omarchy/photos/backup') {
        if (onPhotosBackup == null) return _json(request, 501, _unsupported('photos'));
        final r = await onPhotosBackup!();
        return _json(request, 200, r);
      }
      if (request.method == 'POST' && path == '/omarchy/file') {
        if (onFileReceive == null) return _json(request, 501, _unsupported('file'));
        final uploaded = await _readMultipart(request);
        if (uploaded == null) return _json(request, 400, {'error': 'bad_multipart'});
        final r = await onFileReceive!(uploaded.$1, uploaded.$2);
        return _json(request, 200, r);
      }
      if (request.method == 'GET' && path == '/omarchy/file') {
        if (onFileSend == null) return _json(request, 501, _unsupported('file'));
        final p = request.uri.queryParameters['path'] ?? '';
        if (p.isEmpty) return _json(request, 400, {'error': 'missing_path'});
        final bytes = await onFileSend!(p);
        request.response
          ..statusCode = 200
          ..headers.contentType = ContentType.binary
          ..add(bytes);
        return;
      }
      return _json(request, 404, {'error': 'not_found', 'path': path});
    } catch (e) {
      return _json(request, 500, {'error': 'internal', 'detail': '$e'});
    } finally {
      if (!request.response.headers.persistentConnection) {
        // noop
      }
      try {
        await request.response.close();
      } catch (_) {}
    }
  }

  /// Handles the WebSocket connection for events.
  Future<void> handleWs(HttpRequest request) async {
    try {
      final socket = await WebSocketTransformer.upgrade(request);
      _clients.add(socket);
      final hello = await (onDiscover?.call() ?? Future.value(_defaultDiscover()));
      hello['type'] = 'peer_hello';
      socket.add(jsonEncode(hello));
      await for (final msg in socket) {
        if (msg is String) {
          try {
            final data = jsonDecode(msg) as Map<String, dynamic>;
            if (data['type'] == 'ping') {
              socket.add(jsonEncode({'type': 'pong'}));
            } else if (data['type'] == 'screen_start') {
              _screenActive = true;
              _screenLoop(socket);
            } else if (data['type'] == 'screen_stop') {
              _screenActive = false;
            }
          } catch (_) {}
        }
      }
      _screenActive = false;
      _clients.remove(socket);
    } catch (e) {
      if (kDebugLog) print('[OmarchyLink] ws error: $e');
    }
  }

  /// Captures frames (via onScreenFrame) and streams them to the peer socket
  /// while _screenActive is true.
  Future<void> _screenLoop(WebSocket socket) async {
    while (_screenActive) {
      if (onScreenFrame == null) {
        await Future.delayed(const Duration(milliseconds: 500));
        continue;
      }
      try {
        final bytes = await onScreenFrame!();
        if (socket.readyState == WebSocket.open) socket.add(bytes);
      } catch (e) {
        if (kDebugLog) print('[OmarchyLink] frame error: $e');
      }
      await Future.delayed(const Duration(milliseconds: 200));
    }
  }

  void dispose() {
    for (final c in _clients) c.close();
    _clients.clear();
  }

  Map<String, dynamic> _defaultDiscover() => {
        'name': 'OhmLauncher',
        'model': 'Android',
        'version': 1,
        'capabilities': ['clipboard', 'file', 'theme', 'screen', 'photos'],
      };

  Map<String, dynamic> _unsupported(String cap) =>
      {'error': 'unsupported', 'capability': cap};

  void _cors(HttpRequest request) {
    request.response.headers
      ..set('Access-Control-Allow-Origin', '*')
      ..set('Access-Control-Allow-Methods', 'GET,POST,PUT,OPTIONS')
      ..set('Access-Control-Allow-Headers', 'Content-Type');
  }

  Future<Map<String, dynamic>> _readBody(HttpRequest request) async {
    final body = await utf8.decodeStream(request);
    if (body.isEmpty) return {};
    try {
      return jsonDecode(body) as Map<String, dynamic>;
    } catch (_) {
      return {};
    }
  }

  /// Reads a multipart/form-data with a 'file' field (name + bytes).
  Future<(String, List<int>)?> _readMultipart(HttpRequest request) async {
    final ct = request.headers.contentType;
    if (ct == null || !ct.mimeType.toLowerCase().contains('multipart')) return null;
    final boundary = ct.parameters['boundary'];
    if (boundary == null) return null;
    final out = <int>[];
    await for (final chunk in request) {
      out.addAll(chunk);
    }
    final raw = utf8.decode(out, allowMalformed: true);
    final nameRe = RegExp('filename="([^"]*)"');
    final m = nameRe.firstMatch(raw);
    final name = m?.group(1) ?? 'file.bin';
    // Extracts the byte block between the header and the final boundary.
    final startMarker = raw.indexOf('\r\n\r\n');
    if (startMarker < 0) return null;
    final bytesStart = startMarker + 4;
    final endMarker = raw.lastIndexOf('\r\n--');
    if (endMarker < bytesStart) return null;
    final bytes = out.sublist(bytesStart, endMarker);
    return (name, bytes);
  }

  void _json(HttpRequest request, int code, Map<String, dynamic> data) {
    request.response
      ..statusCode = code
      ..headers.contentType = ContentType.json
      ..write(jsonEncode(data));
  }
}

bool get kDebugLog => false;
