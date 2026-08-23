part of 'package:ohm_launcher/main.dart';

// ---------------------------------------------------------------------------
//  Favorites bar: reuses the drag logic from [_EdgeBox].
//  * Short tap on an icon: launches the app.
//  * Long-press 1s on an icon and drag within the bar: reorders.
//  * Drag out and drop: for now it returns to its place.
// ---------------------------------------------------------------------------

class _FavoritesBar extends StatelessWidget {
  const _FavoritesBar({
    required this.apps,
    required this.visible,
    required this.onToggle,
    required this.position,
    required this.onPositionChange,
    required this.onReordered,
    this.orientation = 'horizontal',
    this.mode,
    this.radius = 14,
  });

  final List<InstalledApp> apps;
  final bool visible;
  final VoidCallback onToggle;
  final String position;
  final ValueChanged<String> onPositionChange;
  final ValueChanged<List<String>> onReordered;
  final String orientation;
  final String? mode;
  final double radius;

  Map<String, dynamic> get _box => {
        'edge': position,
        'direction': mode ?? orientation,
        'color': '#66E0FF',
        'showTitle': false,
        'compact': false,
        'items': apps
            .map((a) => {
                  'type': 'app',
                  'package': a.package,
                  'activity': a.activity,
                  'label': a.label,
                })
            .toList(),
      };

  @override
  Widget build(BuildContext context) {
    return _EdgeBox(
      box: _box,
      boxIndex: -1,
      visible: visible,
      onToggle: onToggle,
      onAddContent: () {}, // avoids hiding the favorites bar when touching "+"
      onConfig: () {},
      onDragUpdate: (d, accent) {},
      onDragEnd: (d) {},
      onItemDropped: (box, index, target) {},
      onItemsReordered: (_, from, to) {
        final order = apps.map((a) => a.key).toList();
        final it = order.removeAt(from);
        order.insert(to, it);
        onReordered(order);
      },
      onReportRect: (box, rect) {},
      boxRects: const {},
      allowBoxDrag: false,
      radius: radius,
    );
  }
}

// ---------------------------------------------------------------------------
//  Desktop indicator
// ---------------------------------------------------------------------------

class _DesktopDots extends StatelessWidget {
  const _DesktopDots({required this.count, required this.current});

  final int count;
  final int current;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        for (var i = 0; i < count; i++)
          AnimatedContainer(
            duration: const Duration(milliseconds: 200),
            margin: const EdgeInsets.symmetric(horizontal: 3),
            width: i == current ? 18 : 6,
            height: 6,
            decoration: BoxDecoration(
              color: i == current ? const Color(0xFF66E0FF) : const Color(0xFF3A4654),
              borderRadius: BorderRadius.circular(3),
            ),
          ),
      ],
    );
  }
}
