// ============================================================================
//  SHELL EXECUTOR — command execution EMBEDDED in the app process.
// ============================================================================
//  Main engine: Process.run with the system shell (/system/bin/sh),
//  entirely within the Ohm Launcher sandbox. Does NOT require any third-party app
//  third parties (nor Termux). It is the way the launcher runs commands of
//  autonomous form.
//
//  Termux (via Termux:API) is OPTIONAL and only used if explicitly enabled
//  in settings (shellPreferTermux) and is installed. It is never a requirement: if
//  fails or is not present, it falls back to the embedded executor.
// ============================================================================

import 'dart:async';
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
  final String via; // 'embedded' | 'termux' | 'error'

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

  /// Runs [command] embedded in the app process.
  ///
  /// If [useTermux] is true and Termux:API is installed, try Termux first
  /// (shares Termux's package environment) and, if it fails, falls back to the executor
  /// embedded. Without [useTermux], it always runs embedded (no dependencies).
  ///
  /// [binDir] (optional) is added to PATH so we can invoke own bins from the
  /// app (herdr, opencode, claude…) installed in its private folder. [homeDir]
  /// sets HOME for those bins.
  static Future<ShellResult> run(
    String command, {
    List<String>? args,
    bool useTermux = false,
    String? workingDirectory,
    String? binDir,
    String? homeDir,
  }) async {
    if (useTermux && await _termuxAvailable()) {
      final termux = await _runInTermux(command, args)
          .timeout(const Duration(seconds: 5), onTimeout: () => null);
      if (termux != null) return termux;
    }
    return _runEmbedded(command, args, workingDirectory, binDir, homeDir);
  }

  /// If [command] invokes an own bin installed in [binDir] (simple name without
  /// '/'), it runs without bypassing Android's noexec restriction: scripts
  /// via `sh <path>` and the ELF via the system linker (`linker64 <path>`),
  /// since both READ the file instead of executing it directly.
  static String _resolveOwnBin(String command, String binDir) {
    final idx = command.indexOf(' ');
    final token = idx < 0 ? command : command.substring(0, idx);
    final rest = idx < 0 ? '' : command.substring(idx);
    if (token.isEmpty || token.contains('/')) return command;
    final file = File('$binDir/$token');
    if (!file.existsSync()) return command;
    List<int> header;
    try {
      header = file.readAsBytesSync();
    } catch (_) {
      return command;
    }
    final isScript = header.isNotEmpty && header[0] == 0x23 && header.length > 1 && header[1] == 0x21;
    final interpreter = isScript ? '/system/bin/sh' : _linkerPath();
    return '$interpreter ${_quote(file.path)}$rest';
  }

  static String _linkerPath() {
    return File('/system/bin/linker64').existsSync()
        ? '/system/bin/linker64'
        : '/system/bin/linker';
  }

  static Future<bool> _termuxAvailable() async {
    try {
      return await _channel.invokeMethod<bool>('isTermuxApiInstalled') ?? false;
    } catch (_) {
      return false;
    }
  }

  /// Embedded executor: runs the command in the app sandbox with the shell
  /// of the system. Requires nothing external.
  static Future<ShellResult> _runEmbedded(
    String command,
    List<String>? args,
    String? workingDirectory,
    String? binDir,
    String? homeDir,
  ) async {
    try {
      final resolved = binDir != null && binDir.isNotEmpty ? _resolveOwnBin(command, binDir) : command;
      final full = args != null && args.isNotEmpty
          ? '$resolved ${args.map(_quote).join(' ')}'
          : resolved;
      final path = [
        if (binDir != null && binDir.isNotEmpty) binDir,
        '/system/bin',
        '/system/xbin',
        '/sbin',
        '/vendor/bin',
        '/odm/bin',
        '/product/bin',
      ].join(':');
      final res = await Process.run(
        '/system/bin/sh',
        ['-c', full],
        workingDirectory: workingDirectory,
        runInShell: false,
        environment: <String, String>{
          'PATH': path,
          'SHELL': '/system/bin/sh',
          if (binDir != null && binDir.isNotEmpty) 'LD_LIBRARY_PATH': binDir,
          if (homeDir != null && homeDir.isNotEmpty) 'HOME': homeDir,
          'TMPDIR': homeDir ?? workingDirectory ?? '/data/local/tmp',
        },
      );
      return ShellResult(
        exitCode: res.exitCode,
        stdout: res.stdout.toString(),
        stderr: res.stderr.toString(),
        via: 'embedded',
      );
    } catch (e) {
      return ShellResult(
        exitCode: -1,
        stdout: '',
        stderr: 'embedded_error: $e',
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
}
