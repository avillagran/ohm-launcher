part of 'package:ohm_launcher/main.dart';

// ---------------------------------------------------------------------------
//  Desktop radial menu (long-press on the background)
// ---------------------------------------------------------------------------

class _RadialDesktopMenu extends StatelessWidget {
  const _RadialDesktopMenu({
    required this.desktopName,
    required this.onAddLeft,
    required this.onAddRight,
    required this.onAddWidget,
    required this.onAddBox,
    required this.onEditWidgets,
    required this.onSettings,
    required this.onLauncherSettings,
    required this.onDeleteDesktop,
    required this.onRestart,
    required this.onConnectOmarchy,
  });

  final String desktopName;
  final VoidCallback onAddLeft;
  final VoidCallback onAddRight;
  final VoidCallback onAddWidget;
  final VoidCallback onAddBox;
  final VoidCallback onEditWidgets;
  final VoidCallback onSettings;
  final VoidCallback onLauncherSettings;
  final VoidCallback onDeleteDesktop;
  final VoidCallback onRestart;
  final VoidCallback onConnectOmarchy;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context)!;
    // Radial menu: left, widget, box, edit, desktop/launcher config and right.
    final items = [
      _RadialItem(icon: Icons.arrow_back, label: l10n.addLeft, onTap: onAddLeft),
      _RadialItem(icon: Icons.add_box_outlined, label: l10n.addWidget, onTap: onAddWidget),
      _RadialItem(icon: Icons.inventory_2_outlined, label: l10n.addBox, onTap: onAddBox),
      _RadialItem(icon: Icons.drag_indicator, label: l10n.editWidgets, onTap: onEditWidgets),
      _RadialItem(icon: Icons.settings_outlined, label: l10n.desktopSettings, onTap: onSettings),
      _RadialItem(icon: Icons.tune, label: l10n.launcherSettings, onTap: onLauncherSettings),
      _RadialItem(icon: Icons.link_outlined, label: l10n.connectOmarchy, onTap: onConnectOmarchy),
      _RadialItem(icon: Icons.restart_alt, label: l10n.restart, onTap: onRestart),
      _RadialItem(icon: Icons.arrow_forward, label: l10n.addRight, onTap: onAddRight),
    ];
    return Dialog(
      backgroundColor: Colors.transparent,
      insetPadding: EdgeInsets.zero,
      child: SizedBox(
        width: double.infinity,
        height: double.infinity,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: () => Navigator.of(context).pop(),
          child: Stack(
            children: [
              Positioned.fill(
                child: GestureDetector(
                  onTap: () => Navigator.of(context).pop(),
                  child: const SizedBox.expand(),
                ),
              ),
              Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      desktopName,
                      style: const TextStyle(fontSize: 13, color: Color(0xFF9AA7B4), letterSpacing: 1),
                    ),
                    const SizedBox(height: 28),
                    Wrap(
                      alignment: WrapAlignment.center,
                      spacing: 24,
                      runSpacing: 16,
                      children: [
                        for (var i = 0; i < items.length; i++)
                          items[i].build(context),
                      ],
                    ),
                    const SizedBox(height: 28),
                    // Trash: remove desktop.
                    Tooltip(
                      message: 'Eliminar escritorio',
                      child: InkWell(
                        borderRadius: BorderRadius.circular(24),
                        onTap: onDeleteDesktop,
                        child: Column(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            Container(
                              width: 48,
                              height: 48,
                              decoration: BoxDecoration(
                                color: const Color(0xEE2A1620),
                                shape: BoxShape.circle,
                                border: Border.all(color: const Color(0xFFFF6B7A), width: 1),
                              ),
                              child: const Icon(Icons.delete_outline, color: Color(0xFFFF6B7A), size: 22),
                            ),
                            const SizedBox(height: 6),
                            const Text(
                              'Eliminar\nescritorio',
                              textAlign: TextAlign.center,
                              style: TextStyle(fontSize: 10, color: Color(0xFFFF9BA8)),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _RadialItem {
  const _RadialItem({required this.icon, required this.label, required this.onTap});

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  Widget build(BuildContext context) {
    return Tooltip(
      message: label.replaceAll('\n', ' '),
      child: InkWell(
        borderRadius: BorderRadius.circular(24),
        onTap: onTap,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                color: const Color(0xEE16202A),
                shape: BoxShape.circle,
                border: Border.all(color: const Color(0xFF66E0FF), width: 1),
              ),
              child: Icon(icon, color: const Color(0xFF66E0FF), size: 22),
            ),
            const SizedBox(height: 6),
            Text(
              label,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 10, color: Color(0xFF9AA7B4)),
            ),
          ],
        ),
      ),
    );
  }
}
