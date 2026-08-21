part of 'package:ohm_launcher/main.dart';

// ---------------------------------------------------------------------------
//  Barra de favoritos: reutiliza la lógica de arrastre de [_EdgeBox].
//  * Tap corto en un icono: lanza la app.
//  * Long-press 1s en un icono y arrastrar dentro de la barra: reordena.
//  * Arrastrar fuera y soltar: por ahora vuelve a su lugar.
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
  });

  final List<InstalledApp> apps;
  final bool visible;
  final VoidCallback onToggle;
  final String position;
  final ValueChanged<String> onPositionChange;
  final ValueChanged<List<String>> onReordered;
  final String orientation;
  final String? mode;

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
      onAddContent: onToggle,
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
    );
  }
}

// ---------------------------------------------------------------------------
//  Indicador de escritorios
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
