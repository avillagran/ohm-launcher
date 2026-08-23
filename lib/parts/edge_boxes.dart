part of 'package:ohm_launcher/main.dart';

// ---------------------------------------------------------------------------
//  Edge boxes: floating panels that stick to the edges of the
//  screen and can contain apps, widgets and plugins.
//
//  Interaction (3-phase drag & drop, system-wide):
//    1s  → the item under the finger is highlighted; drag reorders within the
//          box, or moves it to another box (released after 1s inside the box
//          target; the icon follows the finger).
//    3s  → the highlight moves to the box edge; drag moves the box across
//          its pole or another pole (1s over the other pole to snap to it).
//    5s  → opens the box configuration.
// ---------------------------------------------------------------------------

enum _EdgeBoxPhase { none, item, box }

/// Linear coordinate along an edge to reorder boxes/poles.
String _edgeForPosition(Offset p, Size s) {
  final dyTop = p.dy;
  final dyBottom = s.height - p.dy;
  final dxLeft = p.dx;
  final dxRight = s.width - p.dx;
  final min = [dyTop, dyBottom, dxLeft, dxRight].reduce((a, b) => a < b ? a : b);
  if (min == dyTop) return 'top';
  if (min == dyBottom) return 'bottom';
  if (min == dxLeft) return 'left';
  return 'right';
}

class _EdgeBox extends StatefulWidget {
  const _EdgeBox({
    required this.box,
    required this.boxIndex,
    required this.visible,
    required this.onToggle,
    required this.onAddContent,
    required this.onConfig,
    required this.onDragUpdate,
    required this.onDragEnd,
    required this.onItemDropped,
    required this.onItemsReordered,
    required this.onReportRect,
    this.boxRects,
    this.allowBoxDrag = true,
    this.boxSpacing = 1.0,
    this.radius = 14,
  });
  final Map<String, dynamic> box;
  final int boxIndex;
  final bool visible;
  final VoidCallback onToggle;
  final VoidCallback onAddContent;
  final VoidCallback onConfig;
  final ValueChanged<DragEndDetails> onDragEnd;
  final void Function(DragUpdateDetails d, Color accent) onDragUpdate;
  final void Function(int sourceBox, int itemIndex, int targetBox) onItemDropped;
  final void Function(int boxIndex, int from, int to) onItemsReordered;
  final void Function(int boxIndex, Rect rect) onReportRect;
  final Map<int, Rect>? boxRects;
  final bool allowBoxDrag;
  final double boxSpacing;
  final double radius;

  @override
  State<_EdgeBox> createState() => _EdgeBoxState();
}

class _EdgeBoxState extends State<_EdgeBox> {
  _EdgeBoxPhase _phase = _EdgeBoxPhase.none;
  bool _panning = false;
  int? _dragItem;
  int _originalItemIndex = 0;
  List<dynamic>? _previewItems;
  OverlayEntry? _previewOverlay;
  Timer? _stageTimer;
  int _hoverTargetBox = -1;
  DateTime? _hoverStart;
  Rect? _myRect;
  Offset _prevGlobal = Offset.zero;
  DateTime _stillSince = DateTime.now();
  final ValueNotifier<Offset> _previewOffset = ValueNotifier<Offset>(Offset.zero);
  DateTime? _lastDragUpdate;
  static const Duration _kDragThrottle = Duration(milliseconds: 16);
  static const double _kTouchSlop = 8;
  static const double _kPanSlop = 16;
  Offset? _panStart;
  bool _panActive = false;
  static const Duration _kCrossHover = Duration(milliseconds: 1000);
  static const Duration _kItemPhase = Duration(milliseconds: 900);
  static const Duration _kStillToConfig = Duration(seconds: 2);

  bool get _vertical => (widget.box['direction'] as String? ?? 'horizontal') == 'vertical';
  bool get _grid => (widget.box['direction'] as String? ?? 'horizontal') == 'grid';
  bool get _list => (widget.box['direction'] as String? ?? 'horizontal') == 'list';
  bool get _showTitle => (widget.box['showTitle'] as bool? ?? true);

  List<dynamic> get _items =>
      _previewItems ?? (widget.box['items'] is List ? widget.box['items'] as List : const []);

  Color get _accent {
    final raw = widget.box['color'];
    if (raw is! String || raw.isEmpty) return const Color(0xFF66E0FF);
    final c = DynamicWidgetEngine.colorFromHex(raw);
    return c == Colors.transparent ? const Color(0xFF66E0FF) : c;
  }



  @override
  void initState() {
    super.initState();
    _scheduleRectLoop();
  }

  @override
  void didUpdateWidget(covariant _EdgeBox oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.boxIndex != widget.boxIndex) _scheduleRectLoop();
    // If the content changed from outside (config reloaded), abandon the drag.
    if (_phase != _EdgeBoxPhase.none) _resetDrag();
  }

  @override
  void dispose() {
    _stageTimer?.cancel();
    _hidePreview();
    _previewOffset.dispose();
    super.dispose();
  }

  /// Re-reports the box rect every frame while mounted, but
  /// only fires `onReportRect` when the rect actually changes (e.g. after
  /// collapse/expand the box). So the debug overlay follows the size
  /// real without rebuilding the home at rest.
  void _scheduleRectLoop() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) return;
      final rb = context.findRenderObject();
      if (rb is RenderBox && rb.hasSize) {
        final rect = rb.localToGlobal(Offset.zero) & rb.size;
        if (_myRect == null || _myRect != rect) {
          _myRect = rect;
          widget.onReportRect(widget.boxIndex, rect);
        }
      }
      _scheduleRectLoop();
    });
  }

  // ----------------------------------------------------------- gestures

  void _onLongPressStart(LongPressStartDetails d) {
    log('EdgeBox #${widget.boxIndex} longPressStart at ${d.globalPosition}', name: 'OhmDrag');
    _prevGlobal = d.globalPosition;
    _stillSince = DateTime.now();
    final idx = _itemIndexAtLocal(d.localPosition);
    if (idx != null) {
      setState(() {
        _phase = _EdgeBoxPhase.item;
        _dragItem = idx;
        _originalItemIndex = idx;
        _previewItems = null;
      });
      HapticFeedback.selectionClick();
      log('EdgeBox #${widget.boxIndex} -> item phase (idx=$idx)', name: 'OhmDrag');
    }
    _scheduleLadder();
  }

  void _onLongPressMoveUpdate(LongPressMoveUpdateDetails d) {
    if ((d.globalPosition - _prevGlobal).distance > _kTouchSlop) {
      _prevGlobal = d.globalPosition;
      _markMoved();
    }
    if (_phase == _EdgeBoxPhase.item) {
      _handleItemMove(d.globalPosition, d.localPosition);
    } else if (_phase == _EdgeBoxPhase.box) {
      _handleBoxMove(d.globalPosition);
    }
  }

  void _onLongPressEnd(LongPressEndDetails d) {
    log('EdgeBox #${widget.boxIndex} longPressEnd at ${d.globalPosition}, phase=$_phase', name: 'OhmDrag');
    if (_phase == _EdgeBoxPhase.item) {
      if (_hoverTargetBox < 0) {
        // Reorders within the source box.
        final from = _originalItemIndex;
        final to = _dragItem ?? _originalItemIndex;
        if (from != to) {
          widget.onItemsReordered(widget.boxIndex, from, to);
        }
      }
      // If _hoverTargetBox >= 0 it was already moved to the other box (commit on hover).
      _resetDrag();
    }
    // In box phase the end is handled by LongPressGestureRecognizer (widget.onDragEnd).
  }

  void _onLongPressCancel() {
    _resetDrag();
  }

  // Manual pan via Listener to avoid competing in the GestureArena with the
  // The items' InkWell. This way taps always work and the box pan only
  // is activated after exceeding [_kPanSlop] pixels of movement.

  void _onPointerDown(PointerDownEvent e) {
    if (!widget.allowBoxDrag || _phase != _EdgeBoxPhase.none) return;
    _panStart = e.position;
    _panActive = false;
  }

  void _onPointerMove(PointerMoveEvent e) {
    if (!widget.allowBoxDrag || _phase != _EdgeBoxPhase.none || _panStart == null) return;
    if (!_panActive) {
      if ((e.position - _panStart!).distance < _kPanSlop) return;
      _panActive = true;
      _panning = true;
      setState(() {});
    }
    _handleBoxMove(e.position);
  }

  void _onPointerUp(PointerUpEvent e) {
    if (!widget.allowBoxDrag || _phase != _EdgeBoxPhase.none) return;
    if (_panActive) {
      _panActive = false;
      _panning = false;
      setState(() {});
      widget.onDragEnd(DragEndDetails(globalPosition: e.position));
      _hidePreview();
    }
    _panStart = null;
  }

  void _onPointerCancel(PointerCancelEvent e) {
    if (!widget.allowBoxDrag) return;
    if (_panActive) {
      _panActive = false;
      _panning = false;
      setState(() {});
      _hidePreview();
    }
    _panStart = null;
  }

  /// Marks that the finger moved: resets the idle-time accumulator.
  void _markMoved() {
    _stillSince = DateTime.now();
    _scheduleLadder();
  }

  /// Reschedules the idle staircase. Holding the finger still advances from
  /// phase: 1s activates the item (item drag), 3s opens config. With the
  /// with direct pan the box moves immediately; if the finger moves, it resets
  /// the idle accumulator.
  void _scheduleLadder() {
    _stageTimer?.cancel();
    _stageTimer = Timer.periodic(const Duration(milliseconds: 100), (_) {
      if (!mounted) return;
      // While dragging a box (pan) never open the configuration.
      if (_panning || _panActive) return;
      final quiet = DateTime.now().difference(_stillSince);
      // A [_kStillToConfig] hold anywhere on the box opens
      // its config (1s activates the item to drag; 2s -> configuration).
      if (quiet >= _kStillToConfig) {
        _stageTimer?.cancel();
        log('EdgeBox #${widget.boxIndex} -> config after ${quiet.inMilliseconds}ms still', name: 'OhmDrag');
        widget.onConfig();
        _resetDrag();
      }
    });
  }

  void _resetDrag() {
    log('EdgeBox #${widget.boxIndex} resetDrag', name: 'OhmDrag');
    _stageTimer?.cancel();
    _hidePreview();
    _lastDragUpdate = null;
    setState(() {
      _phase = _EdgeBoxPhase.none;
      _dragItem = null;
      _previewItems = null;
      _hoverTargetBox = -1;
      _hoverStart = null;
    });
  }

  // ------------------------------------------------------- item drag

  void _handleItemMove(Offset global, Offset local) {
    final insideSource = _myRect != null && _myRect!.contains(global);
    if (insideSource) {
      _hoverTargetBox = -1;
      _hoverStart = null;
      _hidePreview();
      final idx = _itemIndexAtLocal(local);
      if (idx != null) _reorderTo(idx);
    } else {
      // Out of the box: the icon follows the finger and we look for another box.
      final item = (widget.box['items'] is List ? widget.box['items'] as List : const []);
      final raw = _originalItemIndex < item.length ? item[_originalItemIndex] : null;
      _showPreview(_buildDragFeedback(_buildItem(raw)), global);
      final target = _findBoxAt(global);
      if (target != null) {
        if (_hoverTargetBox != target) {
          _hoverTargetBox = target;
          _hoverStart = DateTime.now();
        } else if (_hoverStart != null &&
            DateTime.now().difference(_hoverStart!) >= _kCrossHover) {
          widget.onItemDropped(widget.boxIndex, _originalItemIndex, target);
          _resetDrag();
        }
      } else {
        _hoverTargetBox = -1;
        _hoverStart = null;
      }
    }
  }

  void _reorderTo(int to) {
    final items = List<dynamic>.from(_previewItems ?? widget.box['items'] as List);
    final from = _dragItem;
    if (from == null || from == to) return;
    final it = items.removeAt(from);
    items.insert(to, it);
    setState(() {
      _previewItems = items;
      _dragItem = to;
    });
  }

  int? _findBoxAt(Offset global) {
    final rects = widget.boxRects;
    if (rects == null) return null;
    for (final entry in rects.entries) {
      if (entry.key == widget.boxIndex) continue;
      if (entry.value.contains(global)) return entry.key;
    }
    return null;
  }

  // ------------------------------------------------------- box drag

  void _handleBoxMove(Offset global) {
    final now = DateTime.now();
    if (_lastDragUpdate != null &&
        now.difference(_lastDragUpdate!) < _kDragThrottle) {
      return;
    }
    _lastDragUpdate = now;
    final size = MediaQuery.sizeOf(context);
    final edge = _edgeForPosition(global, size);
    log('EdgeBox #${widget.boxIndex} boxMove global=$global edge=$edge', name: 'OhmDrag');
    // The target box and pole follow the finger at all times; the target
    // final is decided on release (onDragEnd) according to the pole under the finger.
    widget.onDragUpdate(DragUpdateDetails(globalPosition: global), _accent);
    _showPreview(_buildBoxPreview(edge), global);
  }

  Widget _buildBoxPreview(String edge) {
    // Shows the box in exactly the same state (expanded/collapsed,
    // direction, items, color) than the real one, so the drag is precise.
    return Material(
      color: Colors.transparent,
      child: _EdgeBoxVisual(
        box: widget.box,
        visible: widget.visible,
        accent: _accent,
        opacity: 0.95,
        borderWidth: 3,
        spacing: widget.boxSpacing,
        radius: widget.radius,
      ),
    );
  }

  // ------------------------------------------------------- preview overlay

  void _showPreview(Widget child, Offset global) {
    _previewOffset.value = global;
    final existing = _previewOverlay;
    if (existing != null) {
      return;
    }
    log('EdgeBox #${widget.boxIndex} showPreview at $global', name: 'OhmDrag');
    final overlay = Overlay.of(context, rootOverlay: true);
    _previewOverlay = OverlayEntry(
      builder: (_) => ValueListenableBuilder<Offset>(
        valueListenable: _previewOffset,
        builder: (context, pos, _) => Positioned(
          left: pos.dx,
          top: pos.dy,
          child: FractionalTranslation(
            translation: const Offset(-0.5, -0.5),
            child: IgnorePointer(child: child),
          ),
        ),
      ),
    );
    overlay.insert(_previewOverlay!);
  }

  void _hidePreview() {
    if (_previewOverlay != null) {
      log('EdgeBox #${widget.boxIndex} hidePreview', name: 'OhmDrag');
    }
    _previewOverlay?.remove();
    _previewOverlay = null;
  }

  // ------------------------------------------------------- layout

  int? _itemIndexAtLocal(Offset local) {
    final items = _items;
    if (items.isEmpty) return null;
    final count = items.length;
    // In horizontal/vertical the handle occupies the first cell; we subtract its
    // space to index only the real items. Container padding (8*s) +
    // handle (18 + 24*s).
    final s = widget.boxSpacing;
    final handle = 18.0 + 32.0 * s;
    if (_grid) {
      final w = context.size?.width ?? 0;
      final cols = math.max(1, math.sqrt(count + 1).ceil());
      final cell = w / cols;
      final col = (local.dx / cell).floor();
      final row = (local.dy / cell).floor();
      final idx = row * cols + col;
      return (idx >= 0 && idx < count) ? idx : null;
    }
    final cell = (_list ? 40.0 : 32.0) + 8.0 * s;
    if (_vertical || _list) {
      final idx = ((local.dy - handle) / cell).floor();
      return (idx >= 0 && idx < count) ? idx : null;
    }
    final idx = ((local.dx - handle) / cell).floor();
    return (idx >= 0 && idx < count) ? idx : null;
  }

  // ------------------------------------------------------- drag feedback

  @override
  Widget build(BuildContext context) {
    final accent = _accent;
    // Exact look of the box (expanded/collapsed, direction, color, items).
    // The AnimatedSwitcher keyed animates the reorders; the rest of the animation
    // (opacity/border) lives inside _EdgeBoxVisual.
    final visual = AnimatedSwitcher(
      duration: const Duration(milliseconds: 220),
      switchInCurve: Curves.easeOut,
      switchOutCurve: Curves.easeIn,
      transitionBuilder: (child, anim) => FadeTransition(
        opacity: anim,
        child: ScaleTransition(
          scale: Tween<double>(begin: 0.92, end: 1).animate(anim),
          child: child,
        ),
      ),
      child: _EdgeBoxVisual(
        key: ValueKey(_itemsKey(_items)),
        box: widget.box,
        visible: widget.visible,
        accent: accent,
        opacity: (_phase == _EdgeBoxPhase.box || _panning) ? 0.25 : 1.0,
        borderWidth: _phase == _EdgeBoxPhase.box ? 3.0 : null,
        highlightedIndex: _dragItem,
        itemsOverride: _previewItems,
        onToggle: widget.onToggle,
        onAddContent: widget.onAddContent,
        spacing: widget.boxSpacing,
        radius: widget.radius,
      ),
    );

    // The content zone uses:
    //   * Translucent listener for manual pan (move box, like the bar of
    //     favorites). It does not compete with the items' InkWell.
    //   * RawGestureDetector for long-press (items/config).
    return Listener(
      behavior: HitTestBehavior.translucent,
      onPointerDown: _onPointerDown,
      onPointerMove: _onPointerMove,
      onPointerUp: _onPointerUp,
      onPointerCancel: _onPointerCancel,
      child: RawGestureDetector(
        gestures: <Type, GestureRecognizerFactory>{
          LongPressGestureRecognizer:
              GestureRecognizerFactoryWithHandlers<LongPressGestureRecognizer>(
            () => LongPressGestureRecognizer(
              duration: _kItemPhase,
              postAcceptSlopTolerance: double.infinity,
            ),
            (r) => r
              ..onLongPressStart = _onLongPressStart
              ..onLongPressMoveUpdate = _onLongPressMoveUpdate
              ..onLongPressEnd = _onLongPressEnd
              ..onLongPressCancel = _onLongPressCancel,
          ),
        },
        child: visual,
      ),
    );
  }

  Widget _buildDragFeedback(Widget child) {
    return Material(
      color: Colors.transparent,
      child: Transform.scale(
        scale: 1.15,
        child: Container(
          padding: const EdgeInsets.all(6),
          decoration: BoxDecoration(
            color: const Color(0xE0111620),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: _accent, width: 2),
          ),
          child: child,
        ),
      ),
    );
  }

  Widget _buildItem(Object? raw, {bool highlighted = false}) {
    final item = _buildEdgeBoxItem(raw, _showTitle, _accent, widget.boxSpacing);
    if (!highlighted) return item;
    // Highlight of the item under the finger in the 1s phase (animated).
    return AnimatedContainer(
      duration: const Duration(milliseconds: 180),
      decoration: BoxDecoration(
        color: _accent.withValues(alpha: 0.18),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: _accent, width: 2),
      ),
      child: item,
    );
  }

  /// Stable key of the current content to animate the reorder.
  String _itemsKey(List<dynamic> items) {
    return items.map((it) {
      if (it is! Map) return 'x';
      return '${it['type'] ?? ''}:${it['package'] ?? it['label'] ?? it['widget'] ?? ''}';
    }).join('|');
  }
}

// Static helpers to render the exact look of a box (with or without
// interaction). Used in the _EdgeBox itself, in the drag preview and in
// the target edge phantom box.

// Approximate width of a cell (32px icon + paddings) for grid mode.
const double _kGridCellExtent = 56;

Color _edgeBoxAccent(Map<String, dynamic> box,
    [Color fallback = const Color(0xFF66E0FF)]) {
  final raw = box['color'];
  if (raw is! String || raw.isEmpty) return fallback;
  final c = DynamicWidgetEngine.colorFromHex(raw);
  return c == Colors.transparent ? fallback : c;
}

IconData _edgeBoxExpandIcon(String edge) => switch (edge) {
      'top' => Icons.expand_more,
      'bottom' => Icons.expand_less,
      'left' => Icons.chevron_right,
      _ => Icons.chevron_left,
    };

Widget _buildEdgeBoxItem(Object? raw, bool showTitle, Color accent, double spacing,
    {bool row = false}) {
  final map = raw is Map ? raw : const {};
  final type = map['type'] is String ? map['type'] as String : 'app';
  return Padding(
    padding: EdgeInsets.all(4 * spacing),
    child: switch (type) {
      'app' => _EdgeBoxAppItem(app: map, showTitle: showTitle, row: row),
      'system_widget' => _EdgeBoxGenericItem(
          icon: Icons.widgets_outlined,
          label: map['label'] is String ? map['label'] as String : 'Widget',
          showTitle: showTitle,
          accent: accent,
          row: row,
        ),
      'plugin' => _EdgeBoxGenericItem(
          icon: Icons.extension_outlined,
          label: map['label'] is String ? map['label'] as String : 'Plugin',
          showTitle: showTitle,
          accent: accent,
          row: row,
        ),
      _ => _EdgeBoxGenericItem(
          icon: Icons.help_outline,
          label: 'Elemento',
          showTitle: showTitle,
          accent: accent,
          row: row,
        ),
    },
  );
}

/// Static visual of an edge box. Exactly replicates the look of the
/// the real box (expanded/collapsed, direction, color, items) with no interaction.
/// It is used both inside [_EdgeBox] and in the drag preview and in the
/// the target edge phantom box.
class _EdgeBoxVisual extends StatelessWidget {
  const _EdgeBoxVisual({
    super.key,
    required this.box,
    required this.visible,
    this.accent,
    this.opacity = 1.0,
    this.borderWidth,
    this.highlightedIndex,
    this.itemsOverride,
    this.onToggle,
    this.onAddContent,
    this.spacing = 1.0,
    this.radius = 14,
  });

  final Map<String, dynamic> box;
  final bool visible;
  final Color? accent;
  final double opacity;
  final double? borderWidth;
  final int? highlightedIndex;
  final List<dynamic>? itemsOverride;
  final VoidCallback? onToggle;
  final VoidCallback? onAddContent;
  final double spacing;
  final double radius;

  @override
  Widget build(BuildContext context) {
    final color = accent ?? _edgeBoxAccent(box);
    final accentBorder = Color.alphaBlend(const Color(0x55000000), color);
    final edge = box['edge'] as String? ?? 'bottom';
    final direction = box['direction'] as String? ?? 'horizontal';
    final vertical = direction == 'vertical';
    final grid = direction == 'grid';
    final list = direction == 'list';
    final showTitle = box['showTitle'] as bool? ?? true;
    // In list mode the name is mandatory (icon + name per row).
    final itemShowTitle = showTitle || list;
    final items = itemsOverride ??
        (box['items'] is List ? box['items'] as List : const []);
    final compact = box['compact'] as bool? ?? false;
    final compactIndex = (box['compactItem'] as num?)?.toInt() ?? 0;
    final s = spacing;

    final border = borderWidth != null
        ? Border.all(color: color, width: borderWidth!)
        : Border.all(color: accentBorder);

    final effectiveTap =
        items.isEmpty && onAddContent != null ? onAddContent : onToggle;

    final itemsList = <Widget>[
      for (var i = 0; i < items.length; i++)
        if (i == highlightedIndex)
          AnimatedContainer(
            duration: const Duration(milliseconds: 180),
            decoration: BoxDecoration(
              color: color.withValues(alpha: 0.18),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: color, width: 2),
            ),
            child: _buildEdgeBoxItem(items[i], itemShowTitle, color, s, row: list),
          )
        else
          _buildEdgeBoxItem(items[i], itemShowTitle, color, s, row: list),
    ];

    final handleWidget = InkWell(
      onTap: effectiveTap,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: EdgeInsets.all(12 * s),
        child: Icon(
          items.isEmpty ? Icons.add : _edgeBoxExpandIcon(edge),
          size: 18,
          color: color,
        ),
      ),
    );

    Widget barContent;
    if (grid) {
      final cellCount = itemsList.length + 1;
      final columns = math.max(1, math.sqrt(cellCount).ceil());
      barContent = ConstrainedBox(
        constraints: BoxConstraints(maxWidth: columns * _kGridCellExtent),
        child: Wrap(
          spacing: 4,
          runSpacing: 4,
          children: [...itemsList, handleWidget],
        ),
      );
    } else if (vertical) {
      final maxH = math.max(120.0, MediaQuery.sizeOf(context).height * 0.45);
      barContent = Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          handleWidget,
          if (itemsList.isNotEmpty)
            ConstrainedBox(
              constraints: BoxConstraints(maxHeight: maxH),
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: itemsList,
                ),
              ),
            ),
        ],
      );
    } else if (list) {
      // Vertical list: each item is a row with icon + name.
      const listWidth = 200.0;
      final maxH = math.max(120.0, MediaQuery.sizeOf(context).height * 0.6);
      barContent = Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          handleWidget,
          if (itemsList.isNotEmpty)
            ConstrainedBox(
              constraints: BoxConstraints(maxWidth: listWidth, maxHeight: maxH),
              child: SingleChildScrollView(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: itemsList,
                ),
              ),
            ),
        ],
      );
    } else {
      final maxW = math.max(160.0, MediaQuery.sizeOf(context).width - 112);
      barContent = Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          handleWidget,
          if (itemsList.isNotEmpty)
            ConstrainedBox(
              constraints: BoxConstraints(maxWidth: maxW),
              child: SingleChildScrollView(
                scrollDirection: Axis.horizontal,
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: itemsList,
                ),
              ),
            ),
        ],
      );
    }

    final expanded = Opacity(
      opacity: opacity,
      child: Container(
        margin: EdgeInsets.all(6 * s),
        padding: EdgeInsets.all(8 * s),
        decoration: BoxDecoration(
          color: const Color(0xCC10161C),
          borderRadius: BorderRadius.circular(radius),
          border: border,
        ),
        child: barContent,
      ),
    );

    Widget collapsed = Opacity(
      opacity: opacity,
      child: GestureDetector(
        onTap: effectiveTap,
        behavior: HitTestBehavior.opaque,
        child: Container(
          margin: EdgeInsets.all(6 * s),
          padding: EdgeInsets.all(8 * s),
          decoration: BoxDecoration(
            color: const Color(0xCC10161C),
            borderRadius: BorderRadius.circular(radius),
            border: border,
          ),
          child: Padding(
            padding: EdgeInsets.all(6 * s),
            child: Icon(_edgeBoxExpandIcon(edge), size: 18, color: color),
          ),
        ),
      ),
    );

    if (compact && items.isNotEmpty) {
      final ci = compactIndex.clamp(0, items.length - 1);
      final compactIcon = IgnorePointer(
        child: _buildEdgeBoxItem(items[ci], itemShowTitle, color, s, row: list),
      );
      final expandBtn = Padding(
        padding: EdgeInsets.all(6 * s),
        child: Icon(_edgeBoxExpandIcon(edge), size: 18, color: color),
      );
      collapsed = Opacity(
        opacity: opacity,
        child: GestureDetector(
          onTap: effectiveTap,
          behavior: HitTestBehavior.opaque,
          child: Container(
            margin: EdgeInsets.all(6 * s),
            padding: EdgeInsets.all(8 * s),
            decoration: BoxDecoration(
              color: const Color(0xCC10161C),
              borderRadius: BorderRadius.circular(16),
              border: border,
            ),
            child: (vertical || grid || list)
                ? Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [compactIcon, expandBtn],
                  )
                : Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [compactIcon, expandBtn],
                  ),
          ),
        ),
      );
    }

    return AnimatedCrossFade(
      firstChild: expanded,
      secondChild: collapsed,
      crossFadeState:
          visible ? CrossFadeState.showFirst : CrossFadeState.showSecond,
      duration: const Duration(milliseconds: 250),
    );
  }
}

class _EdgeBoxAppItem extends StatelessWidget {
  const _EdgeBoxAppItem({required this.app, required this.showTitle, this.row = false});

  final Map<dynamic, dynamic> app;
  final bool showTitle;
  final bool row;

  @override
  Widget build(BuildContext context) {
    final label = app['label'] is String ? app['label'] as String : 'App';
    final package = app['package'] is String ? app['package'] as String : '';
    final activity = app['activity'] is String ? app['activity'] as String : '';
    // Finds the real installed app to get the correct activity and icon.
    final realApp = InstalledAppsSnapshot.latest.firstWhere(
      (a) => a.package == package,
      orElse: () => InstalledApp(label: label, package: package, activity: activity),
    );
    final displayApp = realApp.package.isNotEmpty && realApp.activity.isNotEmpty
        ? realApp
        : InstalledApp(label: label, package: package, activity: activity);
    final icon = _LazyAppIcon(app: displayApp, size: 32, padding: 4, radius: 8);
    if (row) {
      return Padding(
        padding: const EdgeInsets.all(4),
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: () => unawaited(OhmPlatform.launchApp(displayApp)),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              icon,
              if (showTitle) ...[
                const SizedBox(width: 10),
                ConstrainedBox(
                  constraints: const BoxConstraints(maxWidth: 150),
                  child: Text(
                    label,
                    style: const TextStyle(fontSize: 11, color: Color(0xFFE8F1F8)),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ],
            ],
          ),
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.all(4),
      child: InkWell(
        borderRadius: BorderRadius.circular(12),
        onTap: () => unawaited(OhmPlatform.launchApp(displayApp)),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            icon,
            if (showTitle) ...[
              const SizedBox(height: 2),
              Text(
                label,
                style: const TextStyle(fontSize: 9, color: Color(0xFFE8F1F8)),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _EdgeBoxGenericItem extends StatelessWidget {
  const _EdgeBoxGenericItem({
    required this.icon,
    required this.label,
    required this.showTitle,
    this.accent = const Color(0xFF66E0FF),
    this.row = false,
  });

  final IconData icon;
  final String label;
  final bool showTitle;
  final Color accent;
  final bool row;

  @override
  Widget build(BuildContext context) {
    if (row) {
      return Padding(
        padding: const EdgeInsets.all(4),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(icon, size: 24, color: accent),
            if (showTitle) ...[
              const SizedBox(width: 10),
              ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 150),
                child: Text(
                  label,
                  style: const TextStyle(fontSize: 11, color: Color(0xFFE8F1F8)),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ],
        ),
      );
    }
    return Padding(
      padding: const EdgeInsets.all(4),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 24, color: accent),
          if (showTitle) ...[
            const SizedBox(height: 2),
            Text(
              label,
              style: const TextStyle(fontSize: 9, color: Color(0xFFE8F1F8)),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ],
      ),
    );
  }
}