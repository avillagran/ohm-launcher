part of 'package:ohm_launcher/main.dart';

// ---------------------------------------------------------------------------
//  Cajón de apps: aparece al deslizar desde la parte inferior.
// ---------------------------------------------------------------------------
class _AppDrawer extends StatefulWidget {
  const _AppDrawer({super.key, required this.apps, required this.bottomOffset});

  final List<InstalledApp> apps;
  final double bottomOffset;

  @override
  State<_AppDrawer> createState() => _AppDrawerState();
}

class _AppDrawerState extends State<_AppDrawer>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller;
  double _dragStartValue = 0;
  String _query = '';
  final TextEditingController _queryController = TextEditingController();

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 200),
    );
  }

  @override
  void dispose() {
    _controller.dispose();
    _queryController.dispose();
    super.dispose();
  }

  double get _drawerHeight =>
      MediaQuery.sizeOf(context).height * 0.75;

  double get _closedOffset => _drawerHeight + widget.bottomOffset;

  void onVerticalDragStart(DragStartDetails details) {
    _dragStartValue = _controller.value;
  }

  void onVerticalDragUpdate(DragUpdateDetails details) {
    final h = _drawerHeight;
    if (h <= 0) return;
    final delta = -details.delta.dy / h;
    _controller.value = (_dragStartValue + delta).clamp(0, 1);
  }

  /// Deslizar para abrir (overlay/barra) con mayor sensibilidad para que
  /// un swipe normal desde el centro del escritorio lo abra cómodamente.
  void trackOpenGesture(double deltaDy) {
    final h = _drawerHeight;
    if (h <= 0) return;
    const sensitivity = 2.5;
    _controller.value = (_controller.value - (deltaDy / h) * sensitivity).clamp(0, 1);
  }

  void open() {
    _controller.animateTo(1);
  }

  void close() {
    _controller.animateTo(0);
  }

  void onVerticalDragEnd(DragEndDetails details) {
    final v = details.primaryVelocity ?? 0;
    if (v < -150 || _controller.value > 0.15) {
      _controller.animateTo(1);
    } else if (v > 150 || _controller.value < 0.85) {
      _controller.animateTo(0);
    } else {
      _controller.animateTo(_controller.value > 0.5 ? 1 : 0);
    }
  }

  @override
  Widget build(BuildContext context) {
    final height = _drawerHeight;
    final q = _query.trim().toLowerCase();
    final filteredApps = q.isEmpty
        ? widget.apps
        : widget.apps
            .where((a) =>
                a.label.toLowerCase().contains(q) ||
                a.package.toLowerCase().contains(q))
            .toList();
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, child) {
        final open = _controller.value > 0.01;
        final offset = _closedOffset * (1 - _controller.value);
        return SizedBox.expand(
          child: Stack(
            children: [
              if (open)
                Positioned.fill(
                  child: GestureDetector(
                    behavior: HitTestBehavior.translucent,
                    onTap: () => _controller.animateTo(0),
                    child: Container(
                      color: Colors.black.withValues(alpha: _controller.value * 0.5),
                    ),
                  ),
                ),
              Positioned(
                left: 0,
                right: 0,
                bottom: widget.bottomOffset,
                height: height,
                child: Transform.translate(
                  offset: Offset(0, offset),
                  child: child,
                ),
              ),
            ],
          ),
        );
      },
      child: Material(
        color: const Color(0xFF10161C),
        borderRadius: const BorderRadius.vertical(top: Radius.circular(24)),
        elevation: 16,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            GestureDetector(
              onVerticalDragStart: onVerticalDragStart,
              onVerticalDragUpdate: onVerticalDragUpdate,
              onVerticalDragEnd: onVerticalDragEnd,
              behavior: HitTestBehavior.opaque,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    width: 40,
                    height: 4,
                    margin: const EdgeInsets.symmetric(vertical: 12),
                    decoration: BoxDecoration(
                      color: const Color(0xFF3A4654),
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 20, vertical: 4),
                    child: Text(
                      'Aplicaciones',
                      style: TextStyle(
                        fontSize: 14,
                        color: Color(0xFFE8F1F8),
                        fontWeight: FontWeight.w600,
                      ),
                    ),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
              child: TextField(
                controller: _queryController,
                onChanged: (v) => setState(() => _query = v),
                onSubmitted: (_) {
                  if (filteredApps.length == 1) {
                    _controller.animateTo(0);
                    unawaited(OhmPlatform.launchApp(filteredApps.first));
                  } else {
                    FocusScope.of(context).unfocus();
                  }
                },
                style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8)),
                decoration: InputDecoration(
                  hintText: 'Buscar app…',
                  hintStyle: const TextStyle(fontSize: 12, color: Color(0xFF5A6B7A)),
                  prefixIcon: const Icon(Icons.search, size: 18, color: Color(0xFF5A6B7A)),
                  suffixIcon: _query.isEmpty
                      ? null
                      : IconButton(
                          icon: const Icon(Icons.clear, size: 18, color: Color(0xFF5A6B7A)),
                          onPressed: () {
                            _queryController.clear();
                            setState(() => _query = '');
                          },
                        ),
                  isDense: true,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: const BorderSide(color: Color(0x2BFFFFFF)),
                  ),
                ),
              ),
            ),
            Expanded(
              child: NotificationListener<ScrollUpdateNotification>(
                onNotification: (n) {
                  if (n.metrics.pixels < -40) {
                    _controller.animateTo(0);
                    return true;
                  }
                  return false;
                },
                child: GridView.builder(
                  padding: const EdgeInsets.all(16),
                  physics: const BouncingScrollPhysics(),
                  gridDelegate:
                      const SliverGridDelegateWithFixedCrossAxisCount(
                    crossAxisCount: 4,
                    mainAxisSpacing: 16,
                    crossAxisSpacing: 16,
                  ),
                  itemCount: filteredApps.length,
                  itemBuilder: (context, i) => _DesktopAppTile(
                    app: filteredApps[i],
                  ),
                ),
              ),
            ),
              SizedBox(height: MediaQuery.paddingOf(context).bottom),
          ],
        ),
      ),
    );
  }
}
