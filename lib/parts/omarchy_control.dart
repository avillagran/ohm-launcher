// ============================================================================
//  OMARCHY CONTROL TILE — launcher-side control widget for the OhmLauncher
//  <-> Omarchy integration. Flutter (NOT QML): it lives on the Android side,
//  not on the desktop. Looks like an edge box; in expanded mode it reveals the
//  action controls (connect, screen share, clipboard, files, photos, themes,
//  disconnect, delete). Draggable: the user picks where it stays.
//
//  Animations: entry uses an elastic scale+fade so a new connection does not
//  pop in abruptly; delete plays a "shatter" explosion (the tile bursts into
//  radial fragments) before the widget hides. Disconnect simply collapses the
//  menu back to the compact header.
// ============================================================================

part of 'package:ohm_launcher/main.dart';

class _OmarchyControlTile extends StatefulWidget {
  const _OmarchyControlTile({
    required this.position,
    required this.onPositionChanged,
    required this.peer,
    required this.screenSharing,
    required this.onShowQr,
    required this.onToggleScreen,
    required this.onCopyToPhone,
    required this.onCopyFromPhone,
    required this.onFiles,
    required this.onPhotos,
    required this.onThemes,
    required this.onDisconnect,
    required this.onDelete,
  });

  final Offset position;
  final ValueChanged<Offset> onPositionChanged;
  final ({String ip, int port, String id})? peer;
  final bool screenSharing;
  final VoidCallback onShowQr;
  final VoidCallback onToggleScreen;
  final VoidCallback onCopyToPhone;
  final VoidCallback onCopyFromPhone;
  final VoidCallback onFiles;
  final VoidCallback onPhotos;
  final VoidCallback onThemes;
  final VoidCallback onDisconnect;
  final VoidCallback onDelete;

  @override
  State<_OmarchyControlTile> createState() => _OmarchyControlTileState();
}

class _OmarchyControlTileState extends State<_OmarchyControlTile>
    with TickerProviderStateMixin {
  bool _expanded = false;
  late Offset _pos;
  bool _deleted = false;
  late final AnimationController _entry;
  late final AnimationController _exit;
  late final Animation<double> _entryScale;
  late final Animation<double> _entryFade;
  late final Animation<double> _exitScale;
  late final Animation<double> _exitFade;

  // Radial shatter fragments for the delete explosion.
  static const int _fragCount = 12;
  final List<double> _fragAngle = List.generate(_fragCount, (i) => i * (6.28318 / _fragCount));

  @override
  void initState() {
    super.initState();
    _pos = widget.position;
    _entry = AnimationController(vsync: this, duration: const Duration(milliseconds: 650));
    _exit = AnimationController(vsync: this, duration: const Duration(milliseconds: 520));
    _entryScale = CurvedAnimation(parent: _entry, curve: Curves.elasticOut);
    _entryFade = CurvedAnimation(parent: _entry, curve: Curves.easeOut);
    _exitScale = CurvedAnimation(parent: _exit, curve: Curves.easeOutBack);
    _exitFade = CurvedAnimation(parent: _exit, curve: Curves.easeIn);
    if (widget.peer != null) _entry.forward();
    _exit.addStatusListener((s) {
      if (s == AnimationStatus.completed) {
        if (mounted) setState(() => _deleted = true);
      }
    });
  }

  @override
  void didUpdateWidget(covariant _OmarchyControlTile old) {
    super.didUpdateWidget(old);
    // New connection while hidden -> reappear with the entry animation.
    if (old.peer == null && widget.peer != null && _deleted) {
      setState(() => _deleted = false);
      _entry.reset();
      _entry.forward();
    }
    // Peer lost -> collapse the menu (Disconnect), keep the tile visible.
    if (old.peer != null && widget.peer == null) {
      _expanded = false;
    }
  }

  @override
  void dispose() {
    _entry.dispose();
    _exit.dispose();
    super.dispose();
  }

  void _onPanUpdate(DragUpdateDetails d) {
    if (_dragging) {
      setState(() => _pos = Offset(_pos.dx + d.delta.dx, _pos.dy + d.delta.dy));
    }
  }

  bool _dragging = false;
  void _onPanStart(_) => _dragging = true;
  void _onPanEnd(_) {
    _dragging = false;
    widget.onPositionChanged(_pos);
  }

  void _onDelete() {
    widget.onDelete.call();
    setState(() {
      _expanded = false;
      _exit.reset();
      _exit.forward();
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_deleted) return const SizedBox.shrink();

    final accent = widget.peer != null ? Colors.greenAccent : Colors.orangeAccent;

    final header = Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        border: Border.all(color: accent.withValues(alpha: 0.7), width: 1.5),
        borderRadius: BorderRadius.circular(12),
        color: Colors.black.withValues(alpha: 0.55),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onPanStart: _onPanStart,
            onPanUpdate: _onPanUpdate,
            onPanEnd: _onPanEnd,
            child: const Icon(Icons.drag_indicator, color: Colors.white70, size: 16),
          ),
          const SizedBox(width: 6),
          Icon(Icons.link, color: accent, size: 16),
          const SizedBox(width: 6),
          Text(
            widget.peer != null
                ? 'Omarchy: ${widget.peer!.ip}'
                : 'Omarchy: sin conexión',
            style: TextStyle(color: accent, fontSize: 12),
          ),
          const SizedBox(width: 4),
          GestureDetector(
            onTap: () => setState(() => _expanded = !_expanded),
            child: Icon(_expanded ? Icons.expand_less : Icons.expand_more,
                color: accent, size: 18),
          ),
        ],
      ),
    );

    if (!_expanded) {
      return Positioned(left: _pos.dx, top: _pos.dy, child: _wrapAnimations(header));
    }

    final controls = <_OmarchyControlButton>[
      _OmarchyControlButton(
        icon: Icons.qr_code,
        label: 'Conectar',
        onTap: widget.onShowQr,
      ),
      _OmarchyControlButton(
        icon: widget.screenSharing ? Icons.stop_screen_share : Icons.screen_share,
        label: widget.screenSharing ? 'Detener' : 'Pantalla',
        onTap: widget.onToggleScreen,
      ),
      _OmarchyControlButton(
        icon: Icons.upload,
        label: 'Copiar → PC',
        onTap: widget.onCopyToPhone,
      ),
      _OmarchyControlButton(
        icon: Icons.download,
        label: 'Copiar ← PC',
        onTap: widget.onCopyFromPhone,
      ),
      _OmarchyControlButton(
        icon: Icons.folder_copy,
        label: 'Archivos',
        onTap: widget.onFiles,
      ),
      _OmarchyControlButton(
        icon: Icons.photo_library,
        label: 'Fotos',
        onTap: widget.onPhotos,
      ),
      _OmarchyControlButton(
        icon: Icons.palette,
        label: 'Temas',
        onTap: widget.onThemes,
      ),
      _OmarchyControlButton(
        icon: Icons.link_off,
        label: 'Desconectar',
        onTap: widget.onDisconnect,
      ),
      _OmarchyControlButton(
        icon: Icons.delete_forever,
        label: 'Eliminar',
        onTap: _onDelete,
      ),
    ];

    final grid = Container(
      margin: const EdgeInsets.only(top: 6),
      padding: const EdgeInsets.all(8),
      constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width - 16),
      decoration: BoxDecoration(
        border: Border.all(color: accent.withValues(alpha: 0.7), width: 1.5),
        borderRadius: BorderRadius.circular(12),
        color: Colors.black.withValues(alpha: 0.7),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: controls
                .map((c) => SizedBox(width: 56, height: 46, child: c))
                .toList(),
          ),
        ],
      ),
    );

    final content = Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [header, grid],
    );

    return Positioned(
      left: _pos.dx,
      top: _pos.dy,
      child: _wrapAnimations(content, includeFragments: true),
    );
  }

  /// Wraps [child] with the entry (elastic) and exit (shatter) animations.
  /// When [includeFragments] is true, radial explosion fragments are layered
  /// on top during the delete animation.
  Widget _wrapAnimations(Widget child, {bool includeFragments = false}) {
    final contentWithExit = AnimatedBuilder(
      animation: _exit,
      builder: (_, w) => Transform.scale(
        scale: 1.0 + (_exitScale.value - 1.0) * 0.6,
        child: Opacity(opacity: 1.0 - _exitFade.value, child: w),
      ),
      child: ScaleTransition(
        scale: _entryScale,
        child: FadeTransition(opacity: _entryFade, child: child),
      ),
    );

    if (!includeFragments) return contentWithExit;

    return Stack(
      children: [
        // Radial shatter fragments.
        ..._fragAngle.map((angle) {
          return AnimatedBuilder(
            animation: _exit,
            builder: (_, __) {
              final t = _exit.value;
              final dist = 90.0 * t;
              final off = Offset.fromDirection(angle, dist);
              final dx = off.dx;
              final dy = off.dy;
              return Transform.translate(
                offset: Offset(dx, dy),
                child: Transform.scale(
                  scale: 1.0 - t,
                  child: Opacity(
                    opacity: (1.0 - t).clamp(0.0, 1.0),
                    child: Container(
                      width: 18,
                      height: 18,
                      decoration: BoxDecoration(
                        color: (widget.peer != null
                                ? Colors.greenAccent
                                : Colors.orangeAccent)
                            .withValues(alpha: 0.9),
                        borderRadius: BorderRadius.circular(4),
                      ),
                    ),
                  ),
                ),
              );
            },
          );
        }),
        contentWithExit,
      ],
    );
  }
}

class _OmarchyControlButton extends StatelessWidget {
  const _OmarchyControlButton({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.white.withValues(alpha: 0.06),
      borderRadius: BorderRadius.circular(10),
      child: InkWell(
        borderRadius: BorderRadius.circular(10),
        onTap: onTap,
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, color: Colors.white70, size: 17),
            const SizedBox(height: 3),
            Text(label,
                style: const TextStyle(color: Colors.white70, fontSize: 9),
                textAlign: TextAlign.center),
          ],
        ),
      ),
    );
  }
}
