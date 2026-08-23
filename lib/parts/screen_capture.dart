// ============================================================================
//  SCREEN CAPTURE — Dart bridge to share screen (scrcpy-like)
// ============================================================================
//  The native side (MainActivity.kt) captures the screen with MediaProjection and
//  emits JPEG frames via the 'ohm/screen' MethodChannel. This module
//  forwards it over the OmarchyLink WebSocket to the peer (Omarchy), which paints it.
// ============================================================================

import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/services.dart';

class ScreenCapture {
  ScreenCapture({required this.onFrame});

  final void Function(Uint8List jpeg) onFrame;
  static const _channel = MethodChannel('ohm/screen');
  bool _running = false;

  /// Fires the MediaProjection intent (the user authorizes on the phone).
  Future<bool> start() async {
    if (_running) return true;
    _channel.setMethodCallHandler(_handleNative);
    try {
      await _channel.invokeMethod<bool>('startCapture');
      _running = true;
      return true;
    } catch (e) {
      return false;
    }
  }

  Future<void> stop() async {
    _running = false;
    try {
      await _channel.invokeMethod('stopCapture');
    } catch (_) {}
    _channel.setMethodCallHandler(null);
  }

  Future<void> _handleNative(MethodCall call) async {
    if (call.method == 'onFrame' && call.arguments is Uint8List) {
      onFrame(call.arguments as Uint8List);
    }
  }
}

/// Helper: packs a JPEG frame to send over the link WebSocket.
Map<String, dynamic> screenFrameMessage(Uint8List jpeg) =>
    {'type': 'screen_frame', 'data': base64Encode(jpeg)};
