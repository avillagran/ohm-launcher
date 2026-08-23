part of 'package:ohm_launcher/main.dart';

class _OhmBottomBar extends StatefulWidget {
  const _OhmBottomBar({
    required this.plugins,
    required this.apps,
    required this.marketplace,
    required this.registryFailed,
    required this.onRetryRegistry,
    required this.onPluginsInstalled,
    required this.favorites,
    required this.onToggleFavorite,
    required this.visible,
    required this.onToggle,
    required this.position,
    required this.onPositionChange,
    required this.publicPath,
    required this.pluginCount,
    required this.serviceCount,
    required this.onOpenSettings,
    this.orientation = 'horizontal',
    this.radius = 18,
    this.disabledPluginIds = const [],
    this.onTogglePluginEnabled,
    this.onDeletePlugin,
    this.onAddPluginWidget,
  });

  final List<OhmPlugin> plugins;
  final List<InstalledApp> apps;
  final List<MarketplaceEntry> marketplace;
  final bool registryFailed;
  final VoidCallback onRetryRegistry;
  final VoidCallback onPluginsInstalled;
  final List<String> favorites;
  final ValueChanged<InstalledApp> onToggleFavorite;
  final bool visible;
  final VoidCallback onToggle;
  final VoidCallback onOpenSettings;
  final String position;
  final ValueChanged<String> onPositionChange;
  final bool publicPath;
  final int pluginCount;
  final int serviceCount;
  final String orientation;
  final double radius;
  final List<String> disabledPluginIds;
  final void Function(String id)? onTogglePluginEnabled;
  final void Function(String id)? onDeletePlugin;
  final void Function(OhmPlugin plugin)? onAddPluginWidget;

  @override
  State<_OhmBottomBar> createState() => _OhmBottomBarState();
}

class _OhmBottomBarState extends State<_OhmBottomBar> {
  final TextEditingController _controller = TextEditingController();
  String _query = '';

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final handle = GestureDetector(
      onTap: widget.onToggle,
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        padding: const EdgeInsets.all(8),
        decoration: BoxDecoration(
          color: const Color(0xCC0F151B),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: const Color(0x2BFFFFFF)),
        ),
        child: Icon(
          switch (widget.position) {
            'top' => Icons.expand_more,
            'bottom' => Icons.expand_less,
            'left' => Icons.chevron_left,
            _ => Icons.chevron_right,
          },
          size: 18,
          color: const Color(0xFF66E0FF),
        ),
      ),
    );

    final bar = Padding(
      padding: widget.orientation == 'vertical'
          ? const EdgeInsets.all(8)
          : const EdgeInsets.fromLTRB(12, 8, 12, 14),
      child: ConstrainedBox(
        // In horizontal orientation (top/bottom edges) the bar must have a
        // constrained width: the inner TextField and ListView have no fixed width
        // own and, inside the group's Row, collapsed to 0 and the bar
        // "disappeared". A finite maxWidth renders it correctly.
        constraints: widget.orientation == 'vertical'
            ? const BoxConstraints()
            : BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width - 24),
        child: Material(
        color: const Color(0xEE0F151B),
        borderRadius: BorderRadius.circular(widget.radius),
        elevation: 16,
        child: Container(
          padding: const EdgeInsets.all(10),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(widget.radius),
            border: const Border(
              top: BorderSide(color: Color(0x2BFFFFFF)),
              left: BorderSide(color: Color(0x2BFFFFFF)),
              right: BorderSide(color: Color(0x2BFFFFFF)),
              bottom: BorderSide(color: Color(0x2BFFFFFF)),
            ),
          ),
          child: widget.orientation == 'vertical'
              ? SizedBox(
                  width: 112,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.drag_handle, size: 16, color: Color(0xFF3A4654)),
                        GestureDetector(
                          onTap: widget.onToggle,
                          child: const Padding(
                            padding: EdgeInsets.all(2),
                            child: Icon(Icons.keyboard_arrow_down, size: 16, color: Color(0xFF66E0FF)),
                          ),
                        ),
                        GestureDetector(
                          onTap: widget.onOpenSettings,
                          child: const Padding(
                            padding: EdgeInsets.all(2),
                            child: Icon(Icons.settings_outlined, size: 16, color: Color(0xFF66E0FF)),
                          ),
                        ),
                      ],
                      ),
                      ..._verticalBarContent(context),
                    ],
                  ),
                )
              : Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        const Icon(Icons.drag_handle, size: 18, color: Color(0xFF3A4654)),
                        const SizedBox(width: 8),
                        const Text(
                          'Mantén y arrastra para mover',
                          style: TextStyle(fontSize: 10, color: Color(0xFF5A6B7A)),
                        ),
                        const SizedBox(width: 8),
                        GestureDetector(
                          onTap: widget.onToggle,
                          child: const Padding(
                            padding: EdgeInsets.all(4),
                            child: Icon(Icons.keyboard_arrow_down, size: 18, color: Color(0xFF66E0FF)),
                          ),
                        ),
                        GestureDetector(
                          onTap: widget.onOpenSettings,
                          child: const Padding(
                            padding: EdgeInsets.all(4),
                            child: Icon(Icons.settings_outlined, size: 18, color: Color(0xFF66E0FF)),
                          ),
                        ),
                      ],
                    ),
                    ..._horizontalBarContent(context),
                  ],
                ),
        ),
      ),
      ),
    );
    return AnimatedCrossFade(
      firstChild: bar,
      secondChild: handle,
      crossFadeState: widget.visible ? CrossFadeState.showFirst : CrossFadeState.showSecond,
      duration: const Duration(milliseconds: 250),
    );
  }

  List<Widget> _barContent(BuildContext context) {
    final barPlugins = widget.plugins
        .where((p) => p.isValid && (p.kinds.contains('bar-widget') || p.kinds.contains('bar')))
        .toList();
    return <Widget>[
      if (!_vertical) ...[
        if (barPlugins.isNotEmpty)
          SizedBox(
            height: 52,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              itemCount: barPlugins.length,
              separatorBuilder: (_, _) => const SizedBox(width: 8),
              itemBuilder: (context, i) => _BarWidgetTile(
                plugin: barPlugins[i],
                onAdd: widget.onAddPluginWidget == null
                    ? null
                    : () => widget.onAddPluginWidget!.call(barPlugins[i]),
              ),
            ),
          ),
        if (barPlugins.isNotEmpty)
          const Padding(
            padding: EdgeInsets.only(bottom: 10),
            child: Divider(height: 1, color: Color(0x1AFFFFFF)),
          ),
      ],
              TextField(
                controller: _controller,
                onChanged: (v) => setState(() => _query = v),
                onSubmitted: (_) {
                  final entries = _buildResultEntries(context);
                  if (entries.length == 1) {
                    entries.first.onTap();
                  } else {
                    FocusScope.of(context).unfocus();
                  }
                },
                style: const TextStyle(fontSize: 14, color: Color(0xFFE8F1F8)),
                decoration: InputDecoration(
                  hintText: _vertical ? 'Buscar…' : 'Lanzador de Comandos — apps, plugins, marketplace…',
                  hintStyle: const TextStyle(fontSize: 13, color: Color(0xFF5A6B7A)),
                  prefixIcon: const Icon(Icons.terminal, size: 18, color: Color(0xFF5A6B7A)),
                  suffixIcon: _query.isEmpty
                      ? IconButton(
                          icon: const Icon(Icons.extension_outlined, size: 18, color: Color(0xFF5A6B7A)),
                          tooltip: 'Plugins instalados',
                          onPressed: () => _showPluginSheet(context),
                        )
                      : IconButton(
                          icon: const Icon(Icons.clear, size: 18, color: Color(0xFF5A6B7A)),
                          onPressed: () {
                            _controller.clear();
                            setState(() => _query = '');
                          },
                        ),
                  border: InputBorder.none,
                  isDense: true,
                ),
              ),
      if (_query.trim().isNotEmpty) ..._buildResults(context),
      const SizedBox(height: 6),
      _StorageStatusPill(
        path: '',
        publicPath: widget.publicPath,
        pluginCount: widget.pluginCount,
        serviceCount: widget.serviceCount,
      ),
    ];
  }

  List<Widget> _horizontalBarContent(BuildContext context) => _barContent(context);

  List<Widget> _verticalBarContent(BuildContext context) => _barContent(context);

  bool get _vertical => widget.orientation == 'vertical';

  // ------------------------------------------------------- results

  List<_SearchEntry> _buildResultEntries(BuildContext context) {
    final q = _query.trim().toLowerCase();
    if (q.isEmpty) return const [];

    final entries = <_SearchEntry>[];

    for (final app in widget.apps) {
      if (!app.label.toLowerCase().contains(q) && !app.package.toLowerCase().contains(q)) {
        continue;
      }
      final isFav = widget.favorites.contains(app.key);
      entries.add(_SearchEntry(
        label: app.label,
        subtitle: app.package,
        icon: Icons.android,
        iconColor: const Color(0xFF66E0FF),
        app: app,
        kind: 'app',
        onTap: () => _launchApp(context, app),
        trailing: IconButton(
          icon: Icon(
            isFav ? Icons.star : Icons.star_border,
            size: 20,
            color: isFav ? const Color(0xFFF5DE6A) : const Color(0xFF5A6B7A),
          ),
          onPressed: () => widget.onToggleFavorite(app),
        ),
      ));
    }

    for (final plugin in widget.plugins) {
      if (!plugin.isValid) continue;
      final label = plugin.manifest?.name ?? plugin.id;
      if (!label.toLowerCase().contains(q) && !plugin.id.toLowerCase().contains(q)) continue;
      entries.add(_SearchEntry(
        label: label,
        subtitle: 'plugin · ${plugin.id} · ${plugin.kinds.join('/')}',
        icon: Icons.extension_outlined,
        iconColor: const Color(0xFFB48AFF),
        kind: 'plugin',
        onTap: () => _summonPlugin(context, plugin),
      ));
    }

    for (final entry in widget.marketplace) {
      if (!entry.id.toLowerCase().contains(q) &&
          !entry.name.toLowerCase().contains(q) &&
          !entry.category.toLowerCase().contains(q)) {
        continue;
      }
      entries.add(_SearchEntry(
        label: entry.name,
        subtitle: 'marketplace · ${entry.category} · ${entry.isSuite ? 'suite' : 'plugin'}',
        icon: entry.isSuite ? Icons.widgets_outlined : Icons.cloud_outlined,
        iconColor: entry.isSuite ? const Color(0xFFF5DE6A) : const Color(0xFF7EE787),
        kind: 'marketplace',
        onTap: () => _showMarketplaceDialog(context, entry),
      ));
    }

    return entries;
  }

  List<Widget> _buildResults(BuildContext context) {
    final entries = _buildResultEntries(context);

    if (entries.isEmpty) {
      return const [
        Padding(
          padding: EdgeInsets.fromLTRB(12, 10, 12, 4),
          child: Text(
            'Sin coincidencias. Prueba otra palabra.',
            style: TextStyle(fontSize: 12, color: Color(0xFF5A6B7A)),
          ),
        ),
      ];
    }

    return [
      const Divider(height: 12, color: Color(0x1AFFFFFF)),
      ...entries.take(8).map((e) => e.toListTile()),
      if (entries.length > 8)
        const Padding(
          padding: EdgeInsets.all(6),
          child: Text('… y más resultados', style: TextStyle(fontSize: 11, color: Color(0xFF5A6B7A))),
        ),
      const SizedBox(height: 4),
    ];
  }

  void _clear() {
    _controller.clear();
    setState(() => _query = '');
  }

  /// Launches an installed app via the native channel.
  void _launchApp(BuildContext context, InstalledApp app) {
    _clear();
    unawaited(_doLaunchApp(context, app));
  }

  Future<void> _doLaunchApp(BuildContext context, InstalledApp app) async {
    final ok = await OhmPlatform.launchApp(app);
    if (!ok && context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          backgroundColor: const Color(0xFF2A1216),
          content: Text('No se pudo abrir ${app.label}'),
        ),
      );
    }
  }

  // ------------------------------------------------------- actions

  /// Opens the panel/overlay/menu/bar-widget associated with a plugin, with a button
  /// to add it to the desktop.
  void _summonPlugin(BuildContext context, OhmPlugin plugin) {
    final kinds = plugin.kinds;
    _clear();
    Widget? sheetChild;
    String? kindLabel;
    if (kinds.contains('overlay')) {
      Navigator.of(context).push(
        MaterialPageRoute(
          fullscreenDialog: true,
          builder: (_) => _PluginSurfaceScreen(plugin: plugin, kind: 'overlay'),
        ),
      );
      return;
    } else if (kinds.contains('panel')) {
      sheetChild = PluginRenderer.renderForKind(plugin, 'panel');
      kindLabel = 'panel';
    } else if (kinds.contains('menu')) {
      sheetChild = PluginRenderer.renderForKind(plugin, 'menu');
      kindLabel = 'menu';
    } else {
      // bar-widget / bar: rendered the same and can be added to the desktop.
      sheetChild = PluginRenderer.renderForKind(plugin, 'bar-widget');
      kindLabel = 'bar-widget';
    }
    showModalBottomSheet(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      isScrollControlled: true,
      builder: (_) => _PluginSheet(
        title: plugin.manifest?.name ?? plugin.id,
        subtitle: '${plugin.id} · $kindLabel · bridge QML',
        child: sheetChild!,
        onAdd: widget.onAddPluginWidget == null
            ? null
            : () => widget.onAddPluginWidget!.call(plugin),
        addLabel: 'Agregar widget al escritorio',
      ),
    );
  }

  /// Opens the panel of a bar-widget (Panel.json if present).
  void _showPluginSheet(BuildContext context) {
    showModalBottomSheet(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      builder: (sheetContext) {
        return SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Padding(
                padding: EdgeInsets.fromLTRB(20, 16, 20, 8),
                child: Text(
                  'Plugins instalados (contrato de plugins v1)',
                  style: TextStyle(fontSize: 13, color: Color(0xFF7A8A99), fontWeight: FontWeight.w600),
                ),
              ),
              if (widget.plugins.isEmpty)
                const Padding(
                  padding: EdgeInsets.all(20),
                  child: Text(
                    'Sin plugins. Crea una carpeta en plugins/<id>/ con su manifest.json.',
                    style: TextStyle(fontSize: 12, color: Color(0xFF5A6B7A)),
                  ),
                )
              else
                Flexible(
                  child: ListView(
                    shrinkWrap: true,
                    children: widget.plugins.map((p) {
                      final id = p.manifest?.id ?? p.id;
                      final enabled = !widget.disabledPluginIds.contains(id);
                      return ListTile(
                        dense: true,
                        leading: IconButton(
                          icon: Icon(
                            enabled ? Icons.check_circle : Icons.check_circle_outline,
                            color: enabled ? const Color(0xFF7EE787) : const Color(0xFF5A6B7A),
                          ),
                          tooltip: enabled ? 'Desactivar' : 'Activar',
                          onPressed: () {
                            widget.onTogglePluginEnabled?.call(id);
                            // Rebuilds the sheet to reflect the new state.
                            (context as Element).markNeedsBuild();
                          },
                        ),
                        title: Text(p.manifest?.name ?? p.id,
                            style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8))),
                        subtitle: Text(
                          '${p.id} · ${p.kinds.join('/')} · ${enabled ? p.statusLabel : 'desactivado'}',
                          style: const TextStyle(fontSize: 11, color: Color(0xFF7A8A99)),
                        ),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (p.isValid &&
                                (p.kinds.contains('panel') ||
                                    p.kinds.contains('overlay') ||
                                    p.kinds.contains('menu')))
                              IconButton(
                                icon: const Icon(Icons.launch, size: 18),
                                color: const Color(0xFF66E0FF),
                                onPressed: () {
                                  Navigator.of(sheetContext).pop();
                                  _summonPlugin(context, p);
                                },
                              ),
                            IconButton(
                              icon: const Icon(Icons.delete_outline, size: 18),
                              color: const Color(0xFFFF6B7A),
                              tooltip: 'Eliminar',
                              onPressed: () async {
                                final confirm = await showDialog<bool>(
                                  context: sheetContext,
                                  builder: (_) => AlertDialog(
                                    backgroundColor: const Color(0xFF10161C),
                                    title: const Text('Eliminar plugin',
                                        style: TextStyle(color: Color(0xFFE8F1F8))),
                                    content: Text(
                                      '¿Eliminar "${p.manifest?.name ?? p.id}"? '
                                      'Se borrará su carpeta y no se podrá recuperar.',
                                      style: const TextStyle(color: Color(0xFF9AA7B4)),
                                    ),
                                    actions: [
                                      TextButton(
                                        onPressed: () => Navigator.of(sheetContext).pop(false),
                                        child: const Text('Cancelar'),
                                      ),
                                      TextButton(
                                        onPressed: () => Navigator.of(sheetContext).pop(true),
                                        child: const Text('Eliminar',
                                            style: TextStyle(color: Color(0xFFFF6B7A))),
                                      ),
                                    ],
                                  ),
                                );
                                if (confirm == true) {
                                  widget.onDeletePlugin?.call(id);
                                  (context as Element).markNeedsBuild();
                                }
                              },
                            ),
                          ],
                        ),
                      );
                    }).toList(),
                  ),
                ),
              if (widget.registryFailed)
                ListTile(
                  dense: true,
                  title: const Text('No se pudo cargar el marketplace',
                      style: TextStyle(fontSize: 12, color: Color(0xFFF0A35E))),
                  trailing: TextButton(
                    onPressed: () {
                      Navigator.of(sheetContext).pop();
                      widget.onRetryRegistry();
                    },
                    child: const Text('Reintentar'),
                  ),
                ),
              const Divider(height: 16, color: Color(0x1AFFFFFF)),
              ListTile(
                dense: true,
                leading: const Icon(Icons.cloud_outlined, size: 18, color: Color(0xFF7EE787)),
                title: const Text('Explorar marketplace (omarchyplugins.com)',
                    style: TextStyle(fontSize: 12, color: Color(0xFFE8F1F8))),
                subtitle: const Text('Busca e instala plugins de la comunidad',
                    style: TextStyle(fontSize: 10, color: Color(0xFF7A8A99))),
                trailing: const Icon(Icons.chevron_right, size: 18, color: Color(0xFF5A6B7A)),
                onTap: () {
                  Navigator.of(sheetContext).pop();
                  _showMarketplaceScreen(context);
                },
              ),
              const SizedBox(height: 8),
            ],
          ),
        );
      },
    );
  }

  /// Opens the full marketplace exploration screen.
  void _showMarketplaceScreen(BuildContext context) {
    Navigator.of(context).push(
      MaterialPageRoute(
        fullscreenDialog: true,
        builder: (_) => _MarketplaceScreen(
          entries: widget.marketplace,
          registryFailed: widget.registryFailed,
          onRetry: widget.onRetryRegistry,
          onInstalled: widget.onPluginsInstalled,
        ),
      ),
    );
  }

  /// Plugin detail and installation from the marketplace.
  void _showMarketplaceDialog(BuildContext context, MarketplaceEntry entry) {
    showDialog<void>(
      context: context,
      builder: (_) => _MarketplaceInstallDialog(
        entry: entry,
        onInstalled: widget.onPluginsInstalled,
      ),
    );
  }
}

/// Command launcher search result.
class _SearchEntry {
  const _SearchEntry({
    required this.label,
    required this.subtitle,
    required this.icon,
    required this.iconColor,
    required this.kind,
    required this.onTap,
    this.app,
    this.trailing,
  });

  final String label;
  final String subtitle;
  final IconData icon;
  final Color iconColor;
  final String kind;
  final VoidCallback onTap;
  final InstalledApp? app;

  /// Optional widget on the right (favorites star, etc.).
  final Widget? trailing;

  Widget toListTile() {
    return ListTile(
      dense: true,
      leading: app != null
          ? SizedBox(
              width: 20,
              height: 20,
              child: _LazyAppIcon(app: app!, size: 20, padding: 0, radius: 4),
            )
          : Icon(icon, size: 18, color: iconColor),
      title: Text(label, style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8))),
      subtitle: Text(subtitle, style: const TextStyle(fontSize: 10, color: Color(0xFF7A8A99))),
      trailing: trailing,
      onTap: onTap,
    );
  }
}

// ---------------------------------------------------------------------------
//  bar-widget tile in the dock
// ---------------------------------------------------------------------------

class _BarWidgetTile extends StatelessWidget {
  const _BarWidgetTile({required this.plugin, this.onAdd});

  final OhmPlugin plugin;
  final VoidCallback? onAdd;

  @override
  Widget build(BuildContext context) {
    final isBar = plugin.kinds.contains('bar');
    final kind = isBar ? 'bar' : 'bar-widget';
    final entryKind = PluginRenderer.entryKindFor(plugin, kind);
    final label = plugin.manifest?.barWidget?['displayName'] is String
        ? plugin.manifest!.barWidget!['displayName'] as String
        : plugin.manifest?.name ?? plugin.id;

    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: () => _openPanel(context),
      child: Container(
        height: 52,
        padding: EdgeInsets.symmetric(horizontal: isBar ? 20 : 12),
        decoration: BoxDecoration(
          color: const Color(0xFF16202A),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: const Color(0x1FFFFFFF)),
        ),
        child: Center(child: _buildContent(entryKind, label)),
      ),
    );
  }

  /// Safely renders the bar-widget content whatever its
  /// type: JSON and QML are interpreted (QML via bridge) and an absent entry
  /// shows a notice (never overflows or crashes).
  Widget _buildContent(EntryKind kind, String label) {
    Widget? rendered;
    switch (kind) {
      case EntryKind.json:
        rendered = PluginRenderer.renderForKind(plugin, plugin.kinds.contains('bar') ? 'bar' : 'bar-widget');
        break;
      case EntryKind.qml:
        rendered = _renderQmlEntry();
        break;
      case EntryKind.missing:
        return Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.warning_amber_rounded, size: 14, color: Color(0xFFF0A35E)),
            const SizedBox(width: 6),
            Flexible(
              child: Text(
                '$label · sin entry point',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 11, color: Color(0xFF7A8A99)),
              ),
            ),
          ],
        );
    }
    if (rendered == null) return const SizedBox.shrink();
    return SizedBox(
      height: 40,
      child: ClipRect(
        child: FittedBox(
          fit: BoxFit.scaleDown,
          child: rendered,
        ),
      ),
    );
  }

  Widget? _renderQmlEntry() {
    final kind = plugin.kinds.contains('bar') ? 'bar' : 'bar-widget';
    final file = plugin.entryFileForKind(kind);
    if (file == null || !file.existsSync()) return null;
    final result = QmlInterpreter.interpret(
      source: file.readAsStringSync(),
      originDir: plugin.folder.path,
      originFile: _basename(file.path),
      compact: true,
    );
    return result.error == null ? result.widget : null;
  }

  void _openPanel(BuildContext context) {
    final panel = plugin.panelFile();
    showModalBottomSheet(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      isScrollControlled: true,
      builder: (_) => _PluginSheet(
        title: plugin.manifest?.name ?? plugin.id,
        subtitle: '${plugin.id} · bar-widget · bridge QML',
        child: panel != null
            ? _renderEntryFile(panel)
            : PluginRenderer.renderForKind(plugin, 'bar-widget'),
        onAdd: onAdd,
      ),
    );
  }
}

/// Bottom sheet with always-visible header, so opening a plugin never
/// "do nothing": you see the name, the id and its interpreted content.
class _PluginSheet extends StatelessWidget {
  const _PluginSheet({
    required this.title,
    required this.subtitle,
    required this.child,
    this.onAdd,
    this.addLabel = 'Agregar widget al escritorio',
  });

  final String title;
  final String subtitle;
  final Widget child;
  final VoidCallback? onAdd;
  final String addLabel;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(title,
                          style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600)),
                      Text(subtitle,
                          style: const TextStyle(fontSize: 10, color: Color(0xFF7A8A99))),
                    ],
                  ),
                ),
                if (onAdd != null)
                  TextButton.icon(
                    style: TextButton.styleFrom(
                      foregroundColor: const Color(0xFF66E0FF),
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                    ),
                    icon: const Icon(Icons.add, size: 16),
                    label: const Text('Agregar', style: TextStyle(fontSize: 13, fontWeight: FontWeight.w600)),
                    onPressed: () {
                      Navigator.of(context).pop();
                      onAdd!();
                    },
                  ),
                IconButton(
                  icon: const Icon(Icons.close, size: 18, color: Color(0xFF9AA7B4)),
                  onPressed: () => Navigator.of(context).pop(),
                ),
              ],
            ),
            const Divider(height: 16, color: Color(0x1AFFFFFF)),
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 480),
              child: SingleChildScrollView(child: child),
            ),
          ],
        ),
      ),
    );
  }
}
