part of 'package:ohm_launcher/main.dart';

// ============================================================================
//  7. WIDGETS DE ERROR / INFORMATIVOS (nunca crashean la app)
// ============================================================================

class _ConfigErrorCard extends StatelessWidget {
  const _ConfigErrorCard({required this.title, required this.message, this.origin = ''});

  final String title;
  final String message;
  final String origin;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        margin: const EdgeInsets.all(24),
        padding: const EdgeInsets.all(20),
        decoration: BoxDecoration(
          color: const Color(0xFF2A1216),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: const Color(0xFF6B2A33)),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, color: Color(0xFFFF6B7A), size: 28),
            const SizedBox(height: 10),
            Text(title, style: const TextStyle(fontSize: 15, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600)),
            if (origin.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(origin, style: const TextStyle(fontSize: 11, color: Color(0xFF7A8A99))),
              ),
            const SizedBox(height: 10),
            SelectableText(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 12, color: Color(0xFFFFB3BB), fontFamily: 'monospace'),
            ),
            const SizedBox(height: 14),
            const Text(
              'Corrige el archivo en tu editor y guarda: se recargará automáticamente.',
              textAlign: TextAlign.center,
              style: TextStyle(fontSize: 11, color: Color(0xFF7A8A99)),
            ),
          ],
        ),
      ),
    );
  }
}