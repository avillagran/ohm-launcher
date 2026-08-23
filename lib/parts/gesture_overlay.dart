part of 'package:ohm_launcher/main.dart';

// ---------------------------------------------------------------------------
//  Custom gesture navigation to replace the buttons forced by MIUI
// ---------------------------------------------------------------------------

/// Captures gestures on the screen edges when MIUI/HyperOS forces
/// button navigation on third-party launchers.
/// - Swipe in from the left/right edge = Back.
/// - Swipe up from the bottom edge = Home (closes drawer/dialogs).
/// - Swipe up from the bottom edge and hold = Recents.
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
        // Back: left edge.
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
        // Back: right edge.
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
        // Visual pill to indicate a gesture zone (only when there are no buttons).
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
    // Pure listener (observer): it is NOT a gesture recognizer, so
    // never participates in the arena and never blocks the edge box taps
    // that it is behind. It only detects real horizontal shifts (swipe)
    // and lets taps through without movement.
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
