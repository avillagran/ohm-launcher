part of 'package:ohm_launcher/main.dart';

/// Quake-style terminal: unfolds from the top with a swipe-down gesture.
/// Runs a real shell (/system/bin/sh) inside a PTY using flutter_pty,
/// and shows it with the xterm emulator. From here you can control the system
/// and launch bun/opencode/claude/kimi, or tmux if available.
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
  final GlobalKey _termKey = GlobalKey();
  final FocusNode _kbFocus = FocusNode();
  bool _ctrlActive = false;
  bool _altActive = false;

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
        // Names with a dot are not valid shell identifiers.
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
    await rc.writeAsString('${lines.join('\n')}\n', flush: true);
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

  /// Copies the terminal's current selection to the clipboard.
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
    _controller.addListener(_onSelectionChanged);
    if (widget.visible) _start();
  }

  void _onSelectionChanged() => setState(() {});

  @override
  void didUpdateWidget(covariant QuakeTerminal old) {
    super.didUpdateWidget(old);
    // Show: if there is no live PTY (never started or terminated), start one.
    // Hide (visible=false): we do NOT kill the PTY; the session must persist
    // so we can reopen the terminal and resume where it left off.
    if (widget.visible && !old.visible && _pty == null) _start();
  }

  @override
  void dispose() {
    _stop();
    _kbFocus.dispose();
    _controller.dispose();
    super.dispose();
  }

  /// Sends a direct escape sequence to the PTY.
  void _send(String seq) => _writeToPty(seq);

  /// Intercepts the next soft-keyboard key when Ctrl/Alt are
  /// and combines it with the modifier, for basic terminal commands.
  KeyEventResult _onKey(KeyEvent event) {
    if (event is! KeyDownEvent) return KeyEventResult.ignored;
    final label = event.logicalKey.keyLabel;
    if ((_ctrlActive || _altActive) && label.length == 1) {
      final c = label.codeUnitAt(0);
      if (c >= 0x20 && c < 0x7f) {
        if (_ctrlActive) {
          // Ctrl+letter => control byte (A=0x01 ... Z=0x1A; space=0x00).
          final ctrl = c >= 0x61 && c <= 0x7a ? c - 0x60 : c & 0x1f;
          _send(String.fromCharCode(ctrl));
        } else {
          // Alt+letter => ESC prefix + letter (meta).
          _send('\x1b${label.toLowerCase()}');
        }
        _ctrlActive = false;
        _altActive = false;
        _kbFocus.unfocus();
        if (mounted) setState(() {});
        return KeyEventResult.handled;
      }
    }
    return KeyEventResult.ignored;
  }

  @override
  Widget build(BuildContext context) {
    // Hidden: we keep the State and the PTY alive (the session persists); only
    // we stop painting the panel. On becoming visible again it resumes without loss.
    if (!widget.visible) return const SizedBox.shrink();
    return Container(
      color: Colors.black,
      child: KeyboardListener(
        focusNode: _kbFocus,
        onKeyEvent: _onKey,
        child: Column(
          children: [
            // The terminal occupies the main space; the special-key row
            // goes DOWN (near the system soft keyboard) for comfort.
            Expanded(
              child: Stack(
                fit: StackFit.expand,
                children: [
                  TerminalView(
                    _terminal,
                    key: _termKey,
                    controller: _controller,
                    theme: TerminalThemes.defaultTheme,
                  ),
                  ..._buildSelectionHandles(),
                ],
              ),
            ),
            _buildKeyRow(),
          ],
        ),
      ),
    );
  }

  /// Gets the underlying [RenderTerminal] (exposes the cell geometry and the
  /// cell<->pixel mapping that xterm does not expose otherwise).
  RenderTerminal? _findRenderTerminal() {
    final obj = _termKey.currentContext?.findRenderObject();
    RenderObject? cur = obj;
    while (cur != null) {
      if (cur is RenderTerminal) return cur;
      RenderObject? child;
      cur.visitChildren((c) => child ??= c);
      cur = child;
    }
    return null;
  }

  /// Re-extends the selection by dragging a handle to [globalPos].
  void _adjustSelection(String which, Offset globalPos) {
    final rt = _findRenderTerminal();
    final sel = _controller.selection;
    if (rt == null || sel == null) return;
    final local = rt.globalToLocal(globalPos);
    // The fixed anchor is the opposite end from the handle being dragged.
    final fixed = which == 'start' ? sel.end : sel.begin;
    final fixedOffset = rt.getOffset(CellOffset(fixed.x, fixed.y));
    rt.selectCharacters(fixedOffset, local);
    setState(() {});
  }

  List<Widget> _buildSelectionHandles() {
    final sel = _controller.selection;
    final rt = _findRenderTerminal();
    if (sel == null || rt == null) return const [];
    // The end positions in the terminal's local pixel coordinates.
    final start = rt.getOffset(CellOffset(sel.begin.x, sel.begin.y));
    final end = rt.getOffset(CellOffset(sel.end.x, sel.end.y));
    final handle = (Offset p, String which) => Positioned(
          left: p.dx - 12,
          // The start handle sits above the cell; the end handle, below.
          top: (which == 'start' ? p.dy - 24 : p.dy + rt.lineHeight - 4),
          child: GestureDetector(
            onPanStart: (_) {},
            onPanUpdate: (d) => _adjustSelection(which, d.globalPosition),
            onPanEnd: (d) => _adjustSelection(which, d.globalPosition),
            child: Container(
              width: 24,
              height: 28,
              decoration: BoxDecoration(
                color: const Color(0xFF66E0FF),
                shape: BoxShape.circle,
                border: Border.all(color: Colors.white, width: 2),
                boxShadow: const [BoxShadow(color: Colors.black54, blurRadius: 4)],
              ),
              child: const Icon(Icons.drag_handle, size: 14, color: Colors.black),
            ),
          ),
        );
    return [
      handle(start, 'start'),
      handle(end, 'end'),
    ];
  }

  Widget _buildKeyRow() {
    return Container(
      color: const Color(0xFF0B0F14),
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
      child: Wrap(
        spacing: 6,
        runSpacing: 4,
        children: [
          _KeyBtn(
            label: 'Ctrl',
            active: _ctrlActive,
            onTap: () {
              setState(() => _ctrlActive = !_ctrlActive);
              if (_ctrlActive) _kbFocus.requestFocus();
            },
          ),
          _KeyBtn(
            label: 'Alt',
            active: _altActive,
            onTap: () {
              setState(() => _altActive = !_altActive);
              if (_altActive) _kbFocus.requestFocus();
            },
          ),
          _KeyBtn(label: 'Esc', onTap: () => _send('\x1b')),
          _KeyBtn(label: 'Tab', onTap: () => _send('\t')),
          _arrowBtn(Icons.arrow_upward, '\x1b[A'),
          _arrowBtn(Icons.arrow_downward, '\x1b[B'),
          _arrowBtn(Icons.arrow_back, '\x1b[D'),
          _arrowBtn(Icons.arrow_forward, '\x1b[C'),
        ],
      ),
    );
  }

  Widget _arrowBtn(IconData icon, String seq) =>
      _KeyBtn(icon: icon, onTap: () => _send(seq));
}

/// Compact special-key button for the Quake terminal keyboard.
class _KeyBtn extends StatelessWidget {
  const _KeyBtn({this.label, this.icon, this.active = false, required this.onTap});

  final String? label;
  final IconData? icon;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final child = label != null
        ? Text(label!, style: const TextStyle(fontSize: 12, color: Color(0xFFE8F1F8)))
        : Icon(icon, size: 16, color: const Color(0xFFE8F1F8));
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(8),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: active ? const Color(0xFF66E0FF) : const Color(0xFF16202A),
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: const Color(0x1FFFFFFF)),
        ),
        child: child,
      ),
    );
  }
}
