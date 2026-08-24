// ============================================================================
//  OMARCHY CONTROL TILE — launcher-side control widget for the OhmLauncher
//  <-> Omarchy integration. Looks like an edge box; in expanded mode it
//  reveals the action controls (connect, screen share, clipboard, files,
//  photos, themes) wired to the peer PC.
// ============================================================================

part of 'package:ohm_launcher/main.dart';

class _OmarchyControlTile extends StatefulWidget {
  const _OmarchyControlTile({
    required this.peer,
    required this.screenSharing,
    required this.onShowQr,
    required this.onToggleScreen,
    required this.onCopyToPhone,
    required this.onCopyFromPhone,
    required this.onFiles,
    required this.onPhotos,
    required this.onThemes,
  });

  final ({String ip, int port, String id})? peer;
  final bool screenSharing;
  final VoidCallback onShowQr;
  final VoidCallback onToggleScreen;
  final VoidCallback onCopyToPhone;
  final VoidCallback onCopyFromPhone;
  final VoidCallback onFiles;
  final VoidCallback onPhotos;
  final VoidCallback onThemes;

  @override
  State<_OmarchyControlTile> createState() => _OmarchyControlTileState();
}

class _OmarchyControlTileState extends State<_OmarchyControlTile> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final connected = widget.peer != null;
    final accent = connected ? Colors.greenAccent : Colors.orangeAccent;

    final header = GestureDetector(
      onLongPress: () => setState(() => _expanded = !_expanded),
      onTap: () => setState(() => _expanded = !_expanded),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        decoration: BoxDecoration(
          border: Border.all(color: accent.withValues(alpha: 0.7), width: 1.5),
          borderRadius: BorderRadius.circular(12),
          color: Colors.black.withValues(alpha: 0.55),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.link, color: accent, size: 18),
            const SizedBox(width: 8),
            Text(
              connected
                  ? 'Omarchy: ${widget.peer!.ip}'
                  : 'Omarchy: sin conexión',
              style: TextStyle(color: accent, fontSize: 13),
            ),
            const SizedBox(width: 6),
            Icon(_expanded ? Icons.expand_less : Icons.expand_more,
                color: accent, size: 18),
          ],
        ),
      ),
    );

    if (!_expanded) return header;

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
    ];

    final grid = Container(
      margin: const EdgeInsets.only(top: 8),
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        border: Border.all(color: accent.withValues(alpha: 0.7), width: 1.5),
        borderRadius: BorderRadius.circular(12),
        color: Colors.black.withValues(alpha: 0.7),
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Wrap(
            spacing: 10,
            runSpacing: 10,
            children: controls
                .map((c) => SizedBox(width: 92, height: 76, child: c))
                .toList(),
          ),
        ],
      ),
    );

    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [header, grid],
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
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(10),
        child: Container(
          decoration: BoxDecoration(
            border: Border.all(color: Colors.white24, width: 1),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(icon, color: Colors.white70, size: 26),
              const SizedBox(height: 4),
              Text(label,
                  style: const TextStyle(color: Colors.white70, fontSize: 11),
                  textAlign: TextAlign.center),
            ],
          ),
        ),
      ),
    );
  }
}
