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

  const QuakeTerminal({
    super.key,
    this.binDir,
    this.homeDir,
    required this.visible,
    this.onClose,
  });

  @override
  State<QuakeTerminal> createState() => _QuakeTerminalState();
}

class _QuakeTerminalState extends State<QuakeTerminal> {
  Pty? _pty;
  late final Terminal _terminal;

  void _writeToPty(String data) {
    if (_pty == null) return;
    _pty!.write(Uint8List.fromList(utf8.encode(data)));
  }

  void _start() {
    if (_pty != null) return;
    final env = <String, String>{
      'TERM': 'xterm-256color',
      'SHELL': '/system/bin/sh',
      'HOME': widget.homeDir ?? '/data/local/tmp',
      'PATH': '/system/bin:/system/xbin'
          '${widget.binDir != null ? ":${widget.binDir}" : ""}',
    };
    try {
      _pty = Pty.start(
        '/system/bin/sh',
        columns: 80,
        rows: 24,
        environment: env,
        workingDirectory: widget.homeDir ?? '/data/local/tmp',
      );
      _pty!.output.listen(
        (d) => _terminal.write(utf8.decode(d, allowMalformed: true)),
        onDone: () => _terminal.write('\r\n[proceso terminado]\r\n'),
      );
      _terminal.write('OhmLauncher :: terminal (bun listo en \$PATH)\r\n'
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

  @override
  void initState() {
    super.initState();
    _terminal = Terminal(
      onOutput: (data) => _writeToPty(data),
      onResize: (w, h, _, __) => _pty?.resize(h, w),
    );
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
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Colors.black,
      child: TerminalView(_terminal, theme: TerminalThemes.defaultTheme),
    );
  }
}
