part of 'package:ohm_launcher/main.dart';

/// Interprets a UI file by extension (.json = dynamic engine,
/// .qml = QML bridge).
Widget _renderEntryFile(File file) {
  if (file.path.endsWith('.json')) {
    return DynamicWidgetEngine.parse(file.readAsStringSync(), origin: _basename(file.path));
  }
  return QmlInterpreter.interpret(
    source: file.readAsStringSync(),
    originDir: file.parent.path,
    originFile: _basename(file.path),
  ).widget;
}

// ---------------------------------------------------------------------------
//  Screen for overlay-type plugins (full-screen surface)
// ---------------------------------------------------------------------------

class _PluginSurfaceScreen extends StatelessWidget {
  const _PluginSurfaceScreen({required this.plugin, this.kind = 'overlay'});

  final OhmPlugin plugin;
  final String kind;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xF20B0F14),
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Row(
                children: [
                  IconButton(
                    icon: const Icon(Icons.close, size: 20, color: Color(0xFF9AA7B4)),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                  Expanded(
                    child: Text(
                      plugin.manifest?.name ?? plugin.id,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 13, color: Color(0xFF7A8A99)),
                    ),
                  ),
                  Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                    decoration: BoxDecoration(
                      color: const Color(0xFF16202A),
                      borderRadius: BorderRadius.circular(999),
                    ),
                    child: Text('overlay',
                        style: const TextStyle(fontSize: 10, color: Color(0xFF66E0FF))),
                  ),
                ],
              ),
            ),
            Expanded(child: PluginRenderer.renderForKind(plugin, kind)),
          ],
        ),
      ),
    );
  }
}

// ============================================================================
//  6b. MARKETPLACE — exploration and installation screen
// ============================================================================

/// Full screen that lists the plugins from omarchyplugins.com.
class _MarketplaceScreen extends StatelessWidget {
  const _MarketplaceScreen({
    required this.entries,
    required this.registryFailed,
    required this.onRetry,
    required this.onInstalled,
  });

  final List<MarketplaceEntry> entries;
  final bool registryFailed;
  final VoidCallback onRetry;
  final VoidCallback onInstalled;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFF0B0F14),
      body: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
              child: Row(
                children: [
                  IconButton(
                    icon: const Icon(Icons.close, size: 20, color: Color(0xFF9AA7B4)),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                  const Expanded(
                    child: Text(
                      'Marketplace · omarchyplugins.com',
                      style: TextStyle(fontSize: 14, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.refresh, size: 20, color: Color(0xFF66E0FF)),
                    tooltip: 'Actualizar registro',
                    onPressed: onRetry,
                  ),
                ],
              ),
            ),
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 16),
              child: Text(
                'Los plugins se descargan de su repositorio de GitHub. Los entry '
                'points .json se interpretan al instante; los .qml (Quickshell) '
                'se respetan en el contrato pero no se renderizan en Android.',
                style: TextStyle(fontSize: 11, color: Color(0xFF5A6B7A)),
              ),
            ),
            const SizedBox(height: 8),
            Expanded(child: _buildBody(context)),
          ],
        ),
      ),
    );
  }

  Widget _buildBody(BuildContext context) {
    if (registryFailed && entries.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.cloud_off, size: 32, color: Color(0xFF5A6B7A)),
            const SizedBox(height: 12),
            const Text('No se pudo cargar el registro del marketplace.',
                style: TextStyle(fontSize: 12, color: Color(0xFF9AA7B4))),
            const SizedBox(height: 12),
            TextButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh, size: 16),
              label: const Text('Reintentar'),
            ),
          ],
        ),
      );
    }
    if (entries.isEmpty) {
      return const Center(
        child: Text('El registro no contiene plugins publicados.',
            style: TextStyle(fontSize: 12, color: Color(0xFF5A6B7A))),
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(12, 4, 12, 16),
      itemCount: entries.length,
      itemBuilder: (context, i) {
        final entry = entries[i];
        return _MarketplaceCard(
          entry: entry,
          onTap: () => showDialog<void>(
            context: context,
            builder: (_) => _MarketplaceInstallDialog(
              entry: entry,
              onInstalled: onInstalled,
            ),
          ),
        );
      },
    );
  }
}

class _MarketplaceCard extends StatelessWidget {
  const _MarketplaceCard({required this.entry, required this.onTap});

  final MarketplaceEntry entry;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 10),
      color: const Color(0xFF10161C),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: const BorderSide(color: Color(0x1FFFFFFF)),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Row(
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: entry.isSuite ? const Color(0x22F5DE6A) : const Color(0x2266E0FF),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  entry.isSuite ? Icons.widgets_outlined : Icons.extension_outlined,
                  size: 22,
                  color: entry.isSuite ? const Color(0xFFF5DE6A) : const Color(0xFF66E0FF),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(entry.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600)),
                    const SizedBox(height: 2),
                    Text(entry.description,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(fontSize: 11, color: Color(0xFF9AA7B4))),
                    const SizedBox(height: 4),
                    Wrap(
                      spacing: 6,
                      runSpacing: 2,
                      children: [
                        _Tag(text: entry.category),
                        if (entry.isSuite) const _Tag(text: 'suite'),
                        ...entry.tags.take(3).map((t) => _Tag(text: t)),
                      ],
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

class _Tag extends StatelessWidget {
  const _Tag({required this.text});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: const Color(0xFF16202A),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(text,
          style: const TextStyle(fontSize: 10, color: Color(0xFF7A8A99))),
    );
  }
}

/// Plugin detail + installation dialog from the marketplace.
class _MarketplaceInstallDialog extends StatefulWidget {
  const _MarketplaceInstallDialog({required this.entry, required this.onInstalled});

  final MarketplaceEntry entry;
  final VoidCallback onInstalled;

  @override
  State<_MarketplaceInstallDialog> createState() => _MarketplaceInstallDialogState();
}

class _MarketplaceInstallDialogState extends State<_MarketplaceInstallDialog> {
  bool _installing = false;
  String? _status;
  bool _statusIsError = false;

  Future<void> _install() async {
    setState(() {
      _installing = true;
      _status = null;
    });
    final result = await PluginInstaller.installFromGitHub(
      widget.entry.repoUrl,
      expectedId: widget.entry.id,
    );
    if (!mounted) return;
    setState(() {
      _installing = false;
      _status = result.describe();
      _statusIsError = !result.success;
    });
    if (result.success) {
      widget.onInstalled();
    }
  }

  Future<void> _copyRepo(BuildContext context) async {
    await Clipboard.setData(ClipboardData(text: widget.entry.repoUrl));
    if (!context.mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        backgroundColor: Color(0xFF12202B),
        content: Text('URL del repositorio copiada'),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final entry = widget.entry;
    return AlertDialog(
      backgroundColor: const Color(0xFF10161C),
      title: Text(entry.name, style: const TextStyle(fontSize: 16, color: Color(0xFFE8F1F8))),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(entry.description, style: const TextStyle(fontSize: 12, color: Color(0xFF9AA7B4))),
            const SizedBox(height: 12),
            Text('Categoría: ${entry.category}', style: const TextStyle(fontSize: 12, color: Color(0xFF7A8A99))),
            if (entry.tags.isNotEmpty)
              Text('Etiquetas: ${entry.tags.join(', ')}',
                  style: const TextStyle(fontSize: 12, color: Color(0xFF7A8A99))),
            if (entry.repoUrl.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: SelectableText(entry.repoUrl,
                    style: const TextStyle(fontSize: 11, color: Color(0xFF66E0FF))),
              ),
            if (entry.installNote.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text('Nota de instalación: ${entry.installNote}',
                  style: const TextStyle(fontSize: 11, color: Color(0xFFF0A35E))),
            ],
            if (entry.isSuite) ...[
              const SizedBox(height: 8),
              const Text('Las suites requieren su instalador oficial (git clone). '
                  'En Android se instalan plugins individuales con entry points JSON.',
                  style: TextStyle(fontSize: 11, color: Color(0xFF5A6B7A))),
            ],
            if (_status != null) ...[
              const SizedBox(height: 12),
              Container(
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(
                  color: _statusIsError ? const Color(0xFF2A1216) : const Color(0xFF12202B),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: _statusIsError ? const Color(0xFF6B2A33) : const Color(0xFF1F3B4D)),
                ),
                child: Text(
                  _status!,
                  style: TextStyle(
                    fontSize: 11,
                    color: _statusIsError ? const Color(0xFFFFB3BB) : const Color(0xFF7EE787),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
      actions: [
        if (entry.repoUrl.isNotEmpty)
          TextButton(
            onPressed: () => _copyRepo(context),
            child: const Text('Copiar repo'),
          ),
        if (!entry.isSuite && entry.repoUrl.isNotEmpty)
          TextButton(
            onPressed: _installing ? null : _install,
            child: _installing
                ? const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Text('Instalar'),
          ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cerrar'),
        ),
      ],
    );
  }
}
