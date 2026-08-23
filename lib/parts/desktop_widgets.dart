part of 'package:ohm_launcher/main.dart';

// ============================================================================
//  3b. DESKTOP WIDGETS (apps_grid, battery) and multi-desktop
// ============================================================================

/// Static snapshot of installed apps: the (static) dynamic engine reads from
/// here, and the main screen updates it when it loads the real apps.
class InstalledAppsSnapshot {
  InstalledAppsSnapshot._();

  static List<InstalledApp> latest = const [];
}

/// Static snapshot of discovered plugins (for plugin-type widgets).
class PluginSnapshot {
  PluginSnapshot._();

  static List<OhmPlugin> latest = const [];
}

/// Tile of an installed app for the `apps_grid` widget and the drawer.
///
/// `onTap` (optional) replaces the default launch. `onLongPress` (optional)
/// fires after 2s of sustained pressure (not at the default threshold of the
/// system), and in that case the normal tap does not launch the app.
class _DesktopAppTile extends StatefulWidget {
  const _DesktopAppTile({
    required this.app,
    this.onTap,
    this.onLongPress,
  });

  final InstalledApp app;
  final VoidCallback? onTap;
  final VoidCallback? onLongPress;

  @override
  State<_DesktopAppTile> createState() => _DesktopAppTileState();
}

class _DesktopAppTileState extends State<_DesktopAppTile> {
  Timer? _longPressTimer;

  void _cancel() {
    _longPressTimer?.cancel();
    _longPressTimer = null;
  }

  @override
  void dispose() {
    _cancel();
    super.dispose();
  }

  void _fireLongPress() {
    _longPressTimer = null;
    widget.onLongPress?.call();
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTapDown: (_) {
        if (widget.onLongPress != null) {
          _longPressTimer = Timer(const Duration(seconds: 2), _fireLongPress);
        }
      },
      onTapUp: (_) {
        if (_longPressTimer != null) {
          _cancel();
          (widget.onTap ?? () => unawaited(OhmPlatform.launchApp(widget.app))).call();
        }
      },
      onTapCancel: _cancel,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          _LazyAppIcon(app: widget.app, size: 52, padding: 10, radius: 16),
          const SizedBox(height: 6),
          Text(
            widget.app.label,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 10, color: Color(0xFF9AA7B4)),
          ),
        ],
      ),
    );
  }
}

/// Loads an app's icon on demand so as not to block startup.
class _LazyAppIcon extends StatefulWidget {
  const _LazyAppIcon({required this.app, this.size = 40, this.padding = 7, this.radius = 12});

  final InstalledApp app;
  final double size;
  final double padding;
  final double radius;

  @override
  State<_LazyAppIcon> createState() => _LazyAppIconState();
}

class _LazyAppIconState extends State<_LazyAppIcon> {
  Uint8List? _icon;
  bool _loading = false;

  @override
  void initState() {
    super.initState();
    _icon = OhmPlatform.peekIcon(widget.app);
    _load();
  }

  @override
  void didUpdateWidget(covariant _LazyAppIcon oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.app.key != widget.app.key) {
      _icon = OhmPlatform.peekIcon(widget.app);
      _loading = false;
      _load();
    }
  }

  Future<void> _load() async {
    if (_loading || _icon != null) return;
    setState(() => _loading = true);
    try {
      final bytes = await OhmPlatform.getAppIcon(widget.app);
      if (mounted) setState(() => _icon = bytes);
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      width: widget.size,
      height: widget.size,
      padding: EdgeInsets.all(widget.padding),
      decoration: BoxDecoration(
        color: const Color(0xFF16202A),
        borderRadius: BorderRadius.circular(widget.radius),
        border: Border.all(color: const Color(0x1FFFFFFF)),
      ),
      child: _icon != null && _icon!.isNotEmpty
          ? Image.memory(
              _icon!,
              fit: BoxFit.contain,
              errorBuilder: (_, _, _) =>
                  const Icon(Icons.android, color: Color(0xFF66E0FF), size: 22),
            )
          : const Icon(Icons.android, color: Color(0xFF66E0FF), size: 22),
    );
  }
}

/// Live battery widget (percentage via native channel).
class BatteryWidget extends StatefulWidget {
  const BatteryWidget({super.key, required this.style, this.showIcon = true});

  final TextStyle style;
  final bool showIcon;

  @override
  State<BatteryWidget> createState() => _BatteryWidgetState();
}

class _BatteryWidgetState extends State<BatteryWidget> {
  int _level = -1;
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 30), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    int level;
    try {
      level = await OhmPlatform.getBatteryLevel();
    } catch (_) {
      return;
    }
    if (mounted && level >= 0) setState(() => _level = level);
  }

  @override
  Widget build(BuildContext context) {
    if (_level < 0) return Text('—', style: widget.style);
    final icon = _level >= 90
        ? Icons.battery_full
        : _level >= 60
            ? Icons.battery_6_bar
            : _level >= 40
                ? Icons.battery_4_bar
                : _level >= 20
                    ? Icons.battery_3_bar
                    : Icons.battery_1_bar;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (widget.showIcon) ...[
          Icon(icon, size: 18, color: widget.style.color),
          const SizedBox(width: 6),
        ],
        Text('$_level%', style: widget.style),
      ],
    );
  }
}

/// PageView of virtual desktops rendered from `desktops[]`.
class _DesktopPager extends StatefulWidget {
  const _DesktopPager({
    required this.desktops,
    this.wallpaper,
    this.onPageChanged,
    this.onLongPressDesktop,
    this.editingWidget,
    this.onWidgetLongPress,
    this.onWidgetSelected,
    this.onWidgetMove,
    this.onWidgetResize,
    this.onWidgetDelete,
    this.onWidgetDrop,
    this.onWidgetGeometry,
  });

  final List<Object?> desktops;
  final String? wallpaper;
  final ValueChanged<int>? onPageChanged;
  final ValueChanged<int>? onLongPressDesktop;

  /// Selected widget index on the current desktop (null = not editing).
  final int? editingWidget;
  final ValueChanged<int>? onWidgetLongPress;
  final ValueChanged<int>? onWidgetSelected;
  final void Function(int index, int delta)? onWidgetMove;
  final void Function(int index, int delta)? onWidgetResize;
  final ValueChanged<int>? onWidgetDelete;
  final void Function(int from, int to)? onWidgetDrop;
  final void Function(int index, int x, int y, int w, int h)? onWidgetGeometry;

  @override
  State<_DesktopPager> createState() => _DesktopPagerState();
}

class _DesktopPagerState extends State<_DesktopPager> {
  late final PageController _controller;

  @override
  void initState() {
    super.initState();
    _controller = PageController();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return PageView.builder(
      controller: _controller,
      physics: widget.editingWidget != null
          ? const NeverScrollableScrollPhysics()
          : null,
      onPageChanged: widget.onPageChanged,
      itemCount: widget.desktops.length,
      itemBuilder: (context, i) => _DesktopPage(
        raw: widget.desktops[i],
        wallpaper: widget.wallpaper,
        desktopIndex: i,
        onLongPress: () => widget.onLongPressDesktop?.call(i),
        editingWidget: widget.editingWidget,
        onWidgetLongPress: (wi) => widget.onWidgetLongPress?.call(wi),
        onWidgetSelected: (wi) => widget.onWidgetSelected?.call(wi),
        onWidgetMove: widget.onWidgetMove,
        onWidgetResize: widget.onWidgetResize,
        onWidgetDelete: widget.onWidgetDelete,
        onWidgetDrop: widget.onWidgetDrop,
        onWidgetGeometry: widget.onWidgetGeometry,
      ),
    );
  }
}

/// Wrapper that detects a 2-second long-press on a desktop
/// desktop to enter edit mode. The tap is consumed so it does not
/// fire the background menu when touching the widget.
class _LongPressWidget extends StatefulWidget {
  const _LongPressWidget({required this.index, required this.child, this.onLongPress});

  final int index;
  final Widget child;
  final ValueChanged<int>? onLongPress;

  @override
  State<_LongPressWidget> createState() => _LongPressWidgetState();
}

class _LongPressWidgetState extends State<_LongPressWidget> {
  @override
  Widget build(BuildContext context) {
    return RawGestureDetector(
      gestures: {
        LongPressGestureRecognizer: GestureRecognizerFactoryWithHandlers<
            LongPressGestureRecognizer>(
          () => LongPressGestureRecognizer(
            duration: const Duration(seconds: 2),
          ),
          (instance) {
            instance.onLongPress = () {
              HapticFeedback.mediumImpact();
              widget.onLongPress?.call(widget.index);
            };
          },
        ),
      },
      child: widget.child,
    );
  }
}

/// A desktop page: background + widgets, with tap on the background for the menu
/// radial and 2s long-press on a widget to activate edit mode.
class _DesktopPage extends StatelessWidget {
  const _DesktopPage({
    required this.raw,
    this.wallpaper,
    required this.desktopIndex,
    this.onLongPress,
    this.editingWidget,
    this.onWidgetLongPress,
    this.onWidgetSelected,
    this.onWidgetMove,
    this.onWidgetResize,
    this.onWidgetDelete,
    this.onWidgetDrop,
    this.onWidgetGeometry,
  });

  final Object? raw;
  final String? wallpaper;
  final int desktopIndex;
  final VoidCallback? onLongPress;
  final int? editingWidget;
  final ValueChanged<int>? onWidgetLongPress;
  final ValueChanged<int>? onWidgetSelected;
  final void Function(int index, int delta)? onWidgetMove;
  final void Function(int index, int delta)? onWidgetResize;
  final ValueChanged<int>? onWidgetDelete;
  final void Function(int from, int to)? onWidgetDrop;
  final void Function(int index, int x, int y, int w, int h)? onWidgetGeometry;

  @override
  Widget build(BuildContext context) {
    final map = DynamicWidgetEngine.asMapPublic(raw);
    if (map == null) {
      return _ConfigErrorCard(
        title: 'Escritorio no válido',
        message: 'Cada entrada de "desktops" debe ser un objeto JSON.',
        origin: 'widgets_config.json',
      );
    }
    final name = map['name'] is String ? map['name'] as String : 'Escritorio ${desktopIndex + 1}';
    final bg = map['background'] is String ? map['background'] as String : (wallpaper ?? '#0B0F14');
    final bgImage = map['backgroundImage'] is String ? map['backgroundImage'] as String : '';
    final fontF = map['fontFamily'] is String ? map['fontFamily'] as String : '';
    final titleF = map['titleFont'] is String ? map['titleFont'] as String : '';
    final widgets = map['widgets'];
    final isEditing = editingWidget != null;
    final gridCols = (map['gridColumns'] as num?)?.toInt() ?? 14;
    final gridRows = (map['gridRows'] as num?)?.toInt() ?? 10;

    DynamicWidgetEngine.baseFont = fontF;
    DynamicWidgetEngine.titleFont = titleF;

    Widget content;
    if (map.containsKey('type')) {
      content = DynamicWidgetEngine.buildNode(map, origin: 'desktop "$name"');
    } else if (widgets is List) {
      if (isEditing) {
        content = _buildEditableWidgets(context, widgets, gridCols: gridCols, gridRows: gridRows);
      } else {
        content = _buildWidgetGrid(context, widgets, bg, name,
            backgroundImage: bgImage, gridCols: gridCols, gridRows: gridRows);
      }
    } else {
      content = const SizedBox.shrink();
    }

    // Clears the active typographies after building the desktop content.
    DynamicWidgetEngine.baseFont = '';
    DynamicWidgetEngine.titleFont = '';

    return RawGestureDetector(
      gestures: {
        LongPressGestureRecognizer: GestureRecognizerFactoryWithHandlers<
            LongPressGestureRecognizer>(
          () => LongPressGestureRecognizer(duration: const Duration(seconds: 2)),
          (r) => r.onLongPress = () {
            if (!isEditing) onLongPress?.call();
          },
        ),
      },
      child: GestureDetector(
        behavior: isEditing ? HitTestBehavior.opaque : HitTestBehavior.translucent,
        onTap: isEditing ? () => onWidgetSelected?.call(-1) : null,
        child: content,
      ),
    );
  }

  /// Grid: each widget is placed in cells (x, y, w, h) over a grid
  /// per desktop (columns x rows, default 12x8).
  Widget _buildWidgetGrid(BuildContext context, List<Object?> widgets, String bg, String name,
      {String backgroundImage = '', int gridCols = 12, int gridRows = 8}) {
    final size = MediaQuery.sizeOf(context);
    final padTop = MediaQuery.paddingOf(context).top + 70;
    final padBottom = 72.0;
    final padH = 16.0;
    final areaW = size.width - padH * 2;
    final areaH = size.height - padTop - padBottom;
    final cellW = areaW / gridCols;
    final cellH = areaH / gridRows;

    final placed = <Widget>[];
    for (var i = 0; i < widgets.length; i++) {
      final node = DynamicWidgetEngine.asMapPublic(widgets[i]);
      if (node == null) continue;
      final x = DynamicWidgetEngine.asIntPublic(node['x'], 0).clamp(0, gridCols - 1);
      final y = DynamicWidgetEngine.asIntPublic(node['y'], i).clamp(0, gridRows - 1);
      final spanFallback = DynamicWidgetEngine.asIntPublic(node['span'], 4);
      final wcell = DynamicWidgetEngine.asIntPublic(node['w'], spanFallback).clamp(1, gridCols - x);
      final hcell = DynamicWidgetEngine.asIntPublic(node['h'], 1).clamp(1, gridRows - y);
      final inner = DynamicWidgetEngine.buildNode(
        {...node, 'index': i},
        origin: 'desktop "$name"',
      );
      placed.add(
        Positioned(
          left: padH + x * cellW,
          top: padTop + y * cellH,
          width: wcell * cellW,
          height: hcell * cellH,
          child: onWidgetLongPress != null
              ? _LongPressWidget(index: i, onLongPress: onWidgetLongPress, child: inner)
              : inner,
        ),
      );
    }

    final stack = Stack(
      fit: StackFit.expand,
      children: [
        if (backgroundImage.isNotEmpty && File(backgroundImage).existsSync())
          Image.file(
            File(backgroundImage),
            fit: BoxFit.cover,
            errorBuilder: (_, _, _) => const SizedBox.shrink(),
          ),
        ...placed,
      ],
    );
    return Container(
      color: DynamicWidgetEngine.colorFromHex(bg),
      child: stack,
    );
  }

  /// Edit view over a grid: the cells are visible and each widget is
  /// drag to move and resizes with the corner handle.
  Widget _buildEditableWidgets(BuildContext context, List<Object?> widgets,
      {int gridCols = 12, int gridRows = 8}) {
    final size = MediaQuery.sizeOf(context);
    final padTop = MediaQuery.paddingOf(context).top + 70;
    final padBottom = 72.0;
    final padH = 16.0;
    final areaW = size.width - padH * 2;
    final areaH = size.height - padTop - padBottom;
    final cellW = areaW / gridCols;
    final cellH = areaH / gridRows;

    final placed = <Widget>[];
    for (var i = 0; i < widgets.length; i++) {
      final node = DynamicWidgetEngine.asMapPublic(widgets[i]);
      if (node == null) continue;
      final x = DynamicWidgetEngine.asIntPublic(node['x'], 0).clamp(0, gridCols - 1);
      final y = DynamicWidgetEngine.asIntPublic(node['y'], i).clamp(0, gridRows - 1);
      final wcell = DynamicWidgetEngine.asIntPublic(node['w'], DynamicWidgetEngine.asIntPublic(node['span'], 4)).clamp(1, gridCols - x);
      final hcell = DynamicWidgetEngine.asIntPublic(node['h'], 1).clamp(1, gridRows - y);
      final indexedNode = {...node, 'index': i};
      placed.add(
        Positioned(
          left: padH + x * cellW,
          top: padTop + y * cellH,
          width: wcell * cellW,
          height: hcell * cellH,
          child: _EditableGridTile(
            index: i,
            node: indexedNode,
            selected: editingWidget == i,
            gridCols: gridCols,
            gridRows: gridRows,
            cellW: cellW,
            cellH: cellH,
            baseX: x,
            baseY: y,
            baseW: wcell,
            baseH: hcell,
            onSelect: () => onWidgetSelected?.call(i),
            onGeometry: (x, y, w, h) => onWidgetGeometry?.call(i, x, y, w, h),
            onDelete: () => onWidgetDelete?.call(i),
          ),
        ),
      );
    }

    return Stack(
      fit: StackFit.expand,
      children: [
        Container(color: const Color(0x14000000)),
        CustomPaint(
          painter: _GridPainter(
            cols: gridCols,
            rows: gridRows,
            padTop: padTop,
            padBottom: padBottom,
            padH: padH,
          ),
        ),
        Positioned(
          top: 8,
          right: 16,
          child: FilledButton.icon(
            style: FilledButton.styleFrom(
              backgroundColor: const Color(0xFF1F3B4D),
              foregroundColor: const Color(0xFF66E0FF),
              visualDensity: VisualDensity.compact,
            ),
            onPressed: () => onWidgetSelected?.call(-1),
            icon: const Icon(Icons.check, size: 16),
            label: const Text('Listo'),
          ),
        ),
        ...placed,
      ],
    );
  }
}

class _GridPainter extends CustomPainter {
  const _GridPainter({required this.cols, required this.rows, required this.padTop, required this.padBottom, required this.padH});

  final int cols;
  final int rows;
  final double padTop;
  final double padBottom;
  final double padH;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = const Color(0x22FFFFFF)
      ..strokeWidth = 1;
    final areaW = size.width - padH * 2;
    final areaH = size.height - padTop - padBottom;
    final cellW = areaW / cols;
    final cellH = areaH / rows;
    for (var c = 0; c <= cols; c++) {
      final dx = padH + c * cellW;
      canvas.drawLine(Offset(dx, padTop), Offset(dx, padTop + areaH), paint);
    }
    for (var r = 0; r <= rows; r++) {
      final dy = padTop + r * cellH;
      canvas.drawLine(Offset(padH, dy), Offset(padH + areaW, dy), paint);
    }
  }

  @override
  bool shouldRepaint(covariant _GridPainter oldDelegate) =>
      oldDelegate.cols != cols || oldDelegate.rows != rows;
}

/// Editable tile over the grid: drag to move, handle to
/// resize and buttons to change the width in cells.
class _EditableGridTile extends StatefulWidget {
  const _EditableGridTile({
    required this.index,
    required this.node,
    required this.selected,
    required this.gridCols,
    required this.gridRows,
    required this.cellW,
    required this.cellH,
    required this.baseX,
    required this.baseY,
    required this.baseW,
    required this.baseH,
    required this.onSelect,
    required this.onGeometry,
    required this.onDelete,
  });

  final int index;
  final Map<String, dynamic> node;
  final bool selected;
  final int gridCols;
  final int gridRows;
  final double cellW;
  final double cellH;
  final int baseX;
  final int baseY;
  final int baseW;
  final int baseH;
  final VoidCallback onSelect;
  final void Function(int x, int y, int w, int h) onGeometry;
  final VoidCallback onDelete;

  @override
  State<_EditableGridTile> createState() => _EditableGridTileState();
}

class _EditableGridTileState extends State<_EditableGridTile> {
  Offset _dragOffset = Offset.zero;
  Offset _resizeOffset = Offset.zero;
  Offset _down = Offset.zero;
  bool _resizing = false;

  String get _label {
    final type = widget.node['type'];
    if (type == 'clock') {
      final s = widget.node['style'];
      if (s == 'particles') return 'Reloj arena';
      if (s == 'ticker') return 'Reloj ticker';
      return 'Reloj';
    }
    if (type == 'text') return 'Texto';
    if (type == 'apps_grid') return 'Aplicaciones';
    if (type == 'battery') return 'Batería';
    if (type == 'spacer') return 'Separador';
    if (type == 'plugin_widget') return 'Plugin';
    if (type == 'system_widget') return (widget.node['label'] as String?) ?? 'Sistema';
    return 'Widget ${widget.index + 1}';
  }

  double get _tileW => widget.cellW * widget.baseW;
  double get _tileH => widget.cellH * widget.baseH;

  bool _inHandle(Offset p) {
    if (!widget.selected) return false;
    return p.dx > _tileW - 28 && p.dy > _tileH - 28;
  }

  void _onDown(PointerDownEvent e) {
    _down = e.localPosition;
    _resizing = _inHandle(_down);
    setState(() {
      if (_resizing) {
        _resizeOffset = Offset.zero;
      } else {
        _dragOffset = Offset.zero;
      }
    });
  }

  void _onMove(PointerMoveEvent e) {
    final d = e.localPosition - _down;
    setState(() {
      if (_resizing) {
        _resizeOffset = d;
      } else {
        _dragOffset = d;
      }
    });
  }

  void _onUp(PointerUpEvent e) {
    if (_resizing) {
      _commitResize();
      setState(() => _resizeOffset = Offset.zero);
    } else {
      final committed = _commitMove();
      if (!committed) setState(() => _dragOffset = Offset.zero);
    }
  }

  @override
  void didUpdateWidget(covariant _EditableGridTile oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.baseX != widget.baseX ||
        oldWidget.baseY != widget.baseY ||
        oldWidget.baseW != widget.baseW ||
        oldWidget.baseH != widget.baseH) {
      _dragOffset = Offset.zero;
      _resizeOffset = Offset.zero;
    }
  }

  void _onCancel(PointerCancelEvent e) {
    setState(() {
      _resizeOffset = Offset.zero;
      _dragOffset = Offset.zero;
    });
  }

  bool _commitMove() {
    final dxCells = (_dragOffset.dx / widget.cellW).round();
    final dyCells = (_dragOffset.dy / widget.cellH).round();
    final x = (widget.baseX + dxCells).clamp(0, widget.gridCols - widget.baseW);
    final y = (widget.baseY + dyCells).clamp(0, widget.gridRows - widget.baseH);
    if (x != widget.baseX || y != widget.baseY) {
      widget.onGeometry(x, y, widget.baseW, widget.baseH);
      return true;
    }
    return false;
  }

  void _commitResize() {
    final dw = (_resizeOffset.dx / widget.cellW).round();
    final dh = (_resizeOffset.dy / widget.cellH).round();
    final w = (widget.baseW + dw).clamp(1, widget.gridCols - widget.baseX);
    final h = (widget.baseH + dh).clamp(1, widget.gridRows - widget.baseY);
    if (w != widget.baseW || h != widget.baseH) {
      widget.onGeometry(widget.baseX, widget.baseY, w, h);
      return;
    }
    setState(() => _resizeOffset = Offset.zero);
  }

  void _resizeBy(int dw, int dh) {
    final w = (widget.baseW + dw).clamp(1, widget.gridCols - widget.baseX);
    final h = (widget.baseH + dh).clamp(1, widget.gridRows - widget.baseY);
    widget.onGeometry(widget.baseX, widget.baseY, w, h);
  }

  @override
  Widget build(BuildContext context) {
    final content = Container(
      decoration: BoxDecoration(
        color: widget.selected ? const Color(0x221F3B4D) : const Color(0x1616202A),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(
          color: widget.selected ? const Color(0xFF66E0FF) : const Color(0x5FFFFFFF),
          width: widget.selected ? 2 : 1,
        ),
      ),
      padding: const EdgeInsets.all(4),
      child: Stack(
        fit: StackFit.expand,
        children: [
          Positioned.fill(
            child: ClipRect(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(2, 22, 2, 18),
                child: DynamicWidgetEngine.buildNode(widget.node, origin: 'widget edit'),
              ),
            ),
          ),
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: Row(
              children: [
                const Icon(Icons.drag_indicator, size: 14, color: Color(0xFF66E0FF)),
                const SizedBox(width: 2),
                Expanded(
                  child: Text(
                    _label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 9, color: Color(0xFF9AA7B4)),
                  ),
                ),
                if (widget.selected)
                  IconButton(
                    visualDensity: VisualDensity.compact,
                    icon: const Icon(Icons.close, size: 14, color: Color(0xFFFF6B7A)),
                    onPressed: widget.onDelete,
                  ),
              ],
            ),
          ),
          Positioned(
            bottom: 0,
            left: 0,
            child: Text(
              '${widget.baseW}x${widget.baseH}',
              style: const TextStyle(fontSize: 9, color: Color(0xFF5A6B7A)),
            ),
          ),
          if (widget.selected)
            Positioned(
              bottom: 0,
              right: 0,
              child: Transform.translate(
                offset: _resizeOffset,
                child: Container(
                  width: 24,
                  height: 24,
                  decoration: const BoxDecoration(
                    color: Color(0xFF1F3B4D),
                    borderRadius: BorderRadius.only(topLeft: Radius.circular(6)),
                    border: Border.fromBorderSide(BorderSide(color: Color(0xFF66E0FF), width: 1)),
                  ),
                  child: const Icon(Icons.open_in_full, size: 13, color: Color(0xFF66E0FF)),
                ),
              ),
            ),
        ],
      ),
    );

    return Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: _onDown,
      onPointerMove: _onMove,
      onPointerUp: _onUp,
      onPointerCancel: _onCancel,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: widget.onSelect,
        child: Transform.translate(
          offset: _dragOffset,
          child: Stack(
            fit: StackFit.expand,
            children: [
              content,
              if (widget.selected)
                Positioned(
                  top: 20,
                  right: 0,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _sizeBtn(Icons.add, () => _resizeBy(1, 0)),
                      _sizeBtn(Icons.remove, () => _resizeBy(-1, 0)),
                    ],
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _sizeBtn(IconData icon, VoidCallback onTap) {
    return InkWell(
      onTap: onTap,
      child: Container(
        width: 20,
        height: 20,
        margin: const EdgeInsets.only(top: 2),
        decoration: const BoxDecoration(
          color: Color(0xFF1F3B4D),
          shape: BoxShape.circle,
        ),
        child: Icon(icon, size: 13, color: const Color(0xFF66E0FF)),
      ),
    );
  }
}


/// The live time text (`ClockText`) and its formatter now live in
/// clock_widget.dart, shared with the QML bridge.
