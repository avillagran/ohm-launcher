part of 'package:ohm_launcher/main.dart';

/// Terminal tipo Quake: se despliega desde arriba con un gesto de swipe-down.
/// Corre un shell real (/system/bin/sh) dentro de un PTY usando flutter_pty,
/// y lo muestra con el emulador xterm. Desde aquí se puede controlar el sistema
/// y lanzar bun/opencode/claude/kimi, o tmux si está disponible.
class QuakeTerminal extends StatefulWidget {
  final String? binDir;
  final String? homeDir;
  final bool visible;
  final VoidCallback? onClose;
  final VoidCallback? onExit;

  const QuakeTerminal({
    super.key,
    this.binDir,
    this.homeDir,
    required this.visible,
    this.onClose,
    this.onExit,
  });

  @override
  State<QuakeTerminal> createState() => _QuakeTerminalState();
}

class _QuakeTerminalState extends State<QuakeTerminal> {
  Pty? _pty;
  late final Terminal _terminal;
  late final TerminalController _controller;

  Future<void> _writeBashrc() async {
    final home = widget.homeDir;
    final binDir = widget.binDir;
    if (home == null || home.isEmpty || binDir == null || binDir.isEmpty) return;
    final rc = File('$home/.ohm_bashrc');
    final lines = <String>[
      '# OhmLauncher: helpers para bins instalados en directorio noexec',
      'export PATH="/system/bin:/system/xbin:$binDir"',
      'export LD_LIBRARY_PATH="$binDir"',
      'export SHELL="/system/bin/sh"',
      'export HOME="$home"',
      'export TERMINFO="$home/.terminfo"',
      'export TMUX_TMPDIR="$home"',
      'export TMPDIR="$home"',
      '',
    ];
    final dir = Directory(binDir);
    if (await dir.exists()) {
      await for (final e in dir.list(followLinks: false)) {
        if (e is! File && e is! Link) continue;
        final name = e.path.split('/').last;
        // Los nombres con punto no son identificadores válidos de shell.
        if (!RegExp(r'^[A-Za-z_][A-Za-z0-9_]*$').hasMatch(name)) continue;
        final bytes = await File(e.path).openRead(0, 4).expand((b) => b).toList();
        final isElf = bytes.length >= 4 &&
            bytes[0] == 0x7f &&
            bytes[1] == 0x45 &&
            bytes[2] == 0x4c &&
            bytes[3] == 0x46;
        if (isElf) {
          lines.add('$name() { /system/bin/linker64 "$binDir/$name" "\$@"; }');
        } else {
          lines.add('$name() { /system/bin/sh "$binDir/$name" "\$@"; }');
        }
      }
    }
    await rc.writeAsString(lines.join('\n') + '\n', flush: true);
    try {
      await Process.run('/system/bin/chmod', ['0644', rc.path]);
    } catch (_) {}
  }
  void _writeToPty(String data) {
    if (_pty == null) return;
    _pty!.write(Uint8List.fromList(utf8.encode(data)));
  }

  void _start() async {
    if (_pty != null) return;
    await _writeBashrc();
    final home = widget.homeDir ?? '/data/local/tmp';
    final env = <String, String>{
      'TERM': 'xterm-256color',
      'SHELL': '/system/bin/sh',
      'HOME': home,
      'PATH': '/system/bin:/system/xbin'
          '${widget.binDir != null ? ":${widget.binDir}" : ""}',
      if (widget.binDir != null) 'LD_LIBRARY_PATH': widget.binDir!,
      'ENV': '$home/.ohm_bashrc',
      'TERMINFO': '$home/.terminfo',
      'TMUX_TMPDIR': home,
      'TMPDIR': home,
    };
    try {
      _pty = Pty.start(
        '/system/bin/sh',
        columns: 80,
        rows: 24,
        environment: env,
        workingDirectory: home,
      );
      _pty!.output.listen(
        (d) => _terminal.write(utf8.decode(d, allowMalformed: true)),
        onDone: () {
          _terminal.write('\r\n[proceso terminado]\r\n');
          _pty = null;
          widget.onExit?.call();
        },
      );
      _terminal.write('OhmLauncher :: terminal (bun/tmux listos)\r\n'
          '\$ ');
    } catch (e) {
      _terminal.write('Error al iniciar el PTY: $e\r\n');
    }
  }

  void _stop() {
    final p = _pty;
    _pty = null;
    p?.kill(ProcessSignal.sigkill);
  }

  /// Copia la selección actual del terminal al portapapeles.
  void copySelection() {
    final selection = _controller.selection;
    if (selection == null) return;
    final text = _terminal.buffer.getText(selection);
    if (text.isEmpty) return;
    Clipboard.setData(ClipboardData(text: text));
  }

  @override
  void initState() {
    super.initState();
    _terminal = Terminal(
      onOutput: (data) => _writeToPty(data),
      onResize: (w, h, _, __) => _pty?.resize(h, w),
    );
    _controller = TerminalController();
    if (widget.visible) _start();
  }

  @override
  void didUpdateWidget(covariant QuakeTerminal old) {
    super.didUpdateWidget(old);
    if (widget.visible && !old.visible) _start();
    if (!widget.visible && old.visible) _stop();
  }

  @override
  void dispose() {
    _stop();
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Colors.black,
      child: TerminalView(
        _terminal,
        controller: _controller,
        theme: TerminalThemes.defaultTheme,
      ),
    );
  }
}
