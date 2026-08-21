// ============================================================================
//  SHELL EXECUTOR — ejecución de comandos sin abrir Termux
// ============================================================================
//  Estrategia "Ambos":
//   1) Intenta Termux vía el intent com.termux.RUN_COMMAND (requiere
//      Termux:API instalado). Devuelve stdout/stderr/exitCode reales.
//   2) Si Termux no responde (no instalado / sin permiso / timeout), cae a
//      Process.run('sh', ['-c', cmd]) dentro del sandbox de la app.
//
//  Nunca lanza: cualquier fallo se reporta en [ShellResult.stderr] con
//  exitCode -1 y via='error'.
// ============================================================================

import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';

class ShellResult {
  const ShellResult({
    required this.exitCode,
    required this.stdout,
    required this.stderr,
    required this.via,
  });

  final int exitCode;
  final String stdout;
  final String stderr;
  final String via; // 'termux' | 'process' | 'error'

  Map<String, dynamic> toJson() => {
        'exitCode': exitCode,
        'stdout': stdout,
        'stderr': stderr,
        'via': via,
      };

  @override
  String toString() => 'ShellResult(via=$via, exit=$exitCode, '
      'stdout=${stdout.length}b, stderr=${stderr.length}b)';
}

class ShellExecutor {
  ShellExecutor._();

  static const MethodChannel _channel = MethodChannel('com.ohm/ohm');

  /// Ejecuta [command] (con [args] opcionales). Termux primero, Process.run
  /// como fallback.
  static Future<ShellResult> run(
    String command, {
    List<String>? args,
  }) async {
    final termux = await _runInTermux(command, args)
        .timeout(const Duration(seconds: 3), onTimeout: () => null);
    if (termux != null) return termux;

    try {
      final full = args != null && args.isNotEmpty
          ? '$command ${args.map((a) => _quote(a)).join(' ')}'
          : command;
      final res = await Process.run('sh', ['-c', full]);
      return ShellResult(
        exitCode: res.exitCode,
        stdout: res.stdout.toString(),
        stderr: res.stderr.toString(),
        via: 'process',
      );
    } catch (e) {
      return ShellResult(
        exitCode: -1,
        stdout: '',
        stderr: 'process_fallback_error: $e',
        via: 'error',
      );
    }
  }

  static Future<ShellResult?> _runInTermux(
    String command,
    List<String>? args,
  ) async {
    try {
      final r = await _channel.invokeMethod('runInTermux', {
        'command': command,
        'args': args ?? const <String>[],
      });
      if (r is Map) {
        return ShellResult(
          exitCode: (r['exitCode'] as num?)?.toInt() ?? -1,
          stdout: (r['stdout'] as String?) ?? '',
          stderr: (r['stderr'] as String?) ?? '',
          via: 'termux',
        );
      }
    } on PlatformException {
      // Termux no disponible: caer al fallback silenciosamente.
      return null;
    } catch (_) {
      return null;
    }
    return null;
  }

  static String _quote(String a) {
    if (a.contains(RegExp(r"""[^\w@%+=:,./-]"""))) {
      return "'${a.replaceAll("'", r"'\''")}'";
    }
    return a;
  }

  /// Serializa un resultado para respuestas HTTP/JSON.
  static String toJsonString(ShellResult r) => jsonEncode(r.toJson());
}
