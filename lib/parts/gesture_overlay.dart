part of 'package:ohm_launcher/main.dart';

// ---------------------------------------------------------------------------
//  Navegación por gestos propia para reemplazar los botones forzados por MIUI
// ---------------------------------------------------------------------------

/// Captura gestos en los bordes de la pantalla cuando MIUI/HyperOS fuerza
/// la navegación por botones en launchers de terceros.
/// - Deslizar desde el borde izquierdo/derecho hacia adentro = Atrás.
/// - Deslizar desde el borde inferior hacia arriba = Inicio (cierra cajón/dialogs).
/// - Deslizar desde el borde inferior hacia arriba y mantener = Recientes.
class _GestureNavigationOverlay extends StatelessWidget {
  const _GestureNavigationOverlay({
    required this.enabled,
  });

  final bool enabled;

  void _goBack(BuildContext context) {
    HapticFeedback.lightImpact();
    Navigator.of(context).maybePop();
  }

  @override
  Widget build(BuildContext context) {
    if (!enabled) return const SizedBox.shrink();
    const edgeWidth = 32.0;

    return Stack(
      children: [
        // Atrás: borde izquierdo.
        Positioned(
          left: 0,
          top: 0,
          bottom: 0,
          width: edgeWidth,
          child: _EdgeBackDetector(
            direction: AxisDirection.right,
            onBack: () => _goBack(context),
          ),
        ),
        // Atrás: borde derecho.
        Positioned(
          right: 0,
          top: 0,
          bottom: 0,
          width: edgeWidth,
          child: _EdgeBackDetector(
            direction: AxisDirection.left,
            onBack: () => _goBack(context),
          ),
        ),
        // Píldora visual para indicar zona de gestos (solo cuando no hay botones).
        IgnorePointer(
          child: Align(
            alignment: Alignment.bottomCenter,
            child: SafeArea(
              child: Padding(
                padding: const EdgeInsets.only(bottom: 8),
                child: Container(
                  width: 120,
                  height: 5,
                  decoration: BoxDecoration(
                    color: const Color(0x44FFFFFF),
                    borderRadius: BorderRadius.circular(3),
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _EdgeBackDetector extends StatefulWidget {
  const _EdgeBackDetector({required this.direction, required this.onBack});

  final AxisDirection direction;
  final VoidCallback onBack;

  @override
  State<_EdgeBackDetector> createState() => _EdgeBackDetectorState();
}

class _EdgeBackDetectorState extends State<_EdgeBackDetector> {
  Offset? _start;
  bool _fired = false;

  void _down(PointerDownEvent e) {
    _start = e.position;
    _fired = false;
  }

  void _move(PointerMoveEvent e) {
    if (_start == null || _fired) return;
    final dx = e.position.dx - _start!.dx;
    final inward = widget.direction == AxisDirection.right ? dx > 0 : dx < 0;
    if (inward && dx.abs() > 24) {
      _fired = true;
      widget.onBack();
    }
  }

  void _up(PointerUpEvent e) => _start = null;
  void _cancel(PointerCancelEvent e) => _start = null;

  @override
  Widget build(BuildContext context) {
    // Listener puro (observador): NO es un reconocedor de gestos, por lo que
    // nunca participa en la arena y nunca bloquea los taps de la caja de borde
    // que esté detrás. Solo detecta desplazamientos horizontales reales (swipe)
    // y deja pasar los taps sin movimiento.
    return Listener(
      behavior: HitTestBehavior.translucent,
      onPointerDown: _down,
      onPointerMove: _move,
      onPointerUp: _up,
      onPointerCancel: _cancel,
      child: Container(color: Colors.transparent),
    );
  }
}
