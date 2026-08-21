part of 'package:ohm_launcher/main.dart';

// ---------------------------------------------------------------------------
//  Indicador de almacenamiento activo
// ---------------------------------------------------------------------------

class _StorageStatusPill extends StatelessWidget {
  const _StorageStatusPill({
    required this.path,
    required this.publicPath,
    required this.pluginCount,
    required this.serviceCount,
  });

  final String path;
  final bool publicPath;
  final int pluginCount;
  final int serviceCount;

  Future<void> _openAppSettings() async {
    await OhmPlatform.openAppSettings();
  }

  @override
  Widget build(BuildContext context) {
    final text = publicPath
        ? 'Editable · $pluginCount plugin · $serviceCount servicio'
        : 'Sin permisos · carpeta privada · $pluginCount plugin';
    return Material(
      color: const Color(0xDD10161C),
      borderRadius: BorderRadius.circular(999),
      child: InkWell(
        borderRadius: BorderRadius.circular(999),
        onTap: publicPath ? null : _openAppSettings,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                publicPath ? Icons.edit_outlined : Icons.warning_amber_rounded,
                size: 13,
                color: publicPath ? const Color(0xFF7EE787) : const Color(0xFFF0A35E),
              ),
              const SizedBox(width: 6),
              ConstrainedBox(
                constraints: BoxConstraints(
                  maxWidth: MediaQuery.sizeOf(context).width * 0.75,
                ),
                child: Text(
                  text,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 11, color: Color(0xFF7A8A99)),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
