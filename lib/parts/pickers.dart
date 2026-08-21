part of 'package:ohm_launcher/main.dart';

// ---------------------------------------------------------------------------
//  Selector de widgets para añadir a un escritorio
// ---------------------------------------------------------------------------

class _WidgetPickerSheet extends StatefulWidget {
  const _WidgetPickerSheet();

  @override
  State<_WidgetPickerSheet> createState() => _WidgetPickerSheetState();
}

class _WidgetPickerSheetState extends State<_WidgetPickerSheet> {
  List<SystemWidgetInfo> _systemWidgets = const [];
  bool _loadingSystem = true;
  String _query = '';

  static const List<Map<String, dynamic>> _catalog = [
    {
      'name': 'Reloj',
      'desc': 'Hora en vivo',
      'icon': Icons.access_time,
      'node': {
        'type': 'clock',
        'format': 'HH:mm',
        'fontSize': 48,
        'color': '#66E0FF',
        'fontWeight': 'w300',
      },
    },
    {
      'name': 'Reloj arena',
      'desc': 'Partículas que se recomponen',
      'icon': Icons.grain,
      'node': {
        'type': 'clock',
        'style': 'particles',
        'format': 'HH:mm:ss',
        'fontSize': 56,
        'color': '#66E0FF',
        'fontWeight': 'w300',
        'density': 3,
        'particleSize': 1.6,
      },
    },
    {
      'name': 'Reloj ticker',
      'desc': 'Dígitos rodantes',
      'icon': Icons.swap_vert,
      'node': {
        'type': 'clock',
        'style': 'ticker',
        'format': 'HH:mm:ss',
        'fontSize': 44,
        'color': '#E8F1F8',
        'fontWeight': 'w600',
      },
    },
    {
      'name': 'Texto',
      'desc': 'Etiqueta editable',
      'icon': Icons.text_fields,
      'node': {'type': 'text', 'value': 'Nuevo texto', 'fontSize': 16, 'color': '#E8F1F8'},
    },
    {
      'name': 'Aplicaciones',
      'desc': 'Rejilla de apps instaladas',
      'icon': Icons.apps,
      'node': {'type': 'apps_grid', 'columns': 4},
    },
    {
      'name': 'Batería',
      'desc': 'Nivel de batería',
      'icon': Icons.battery_full,
      'node': {'type': 'battery', 'fontSize': 16},
    },
    {
      'name': 'Separador',
      'desc': 'Espacio flexible',
      'icon': Icons.space_bar,
      'node': {'type': 'spacer', 'size': 24},
    },
  ];

  @override
  void initState() {
    super.initState();
    _loadSystemWidgets();
  }

  Future<void> _loadSystemWidgets() async {
    try {
      final widgets = await OhmPlatform.getInstalledAppWidgets();
      if (mounted) setState(() => _systemWidgets = widgets);
    } catch (_) {
      // Sin puente nativo (tests) -> no hay widgets del sistema.
    } finally {
      if (mounted) setState(() => _loadingSystem = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final q = _query.trim().toLowerCase();
    final launcher = _catalog
        .where((w) =>
            (w['name'] as String).toLowerCase().contains(q) ||
            (w['desc'] as String).toLowerCase().contains(q))
        .toList();
    final system = _systemWidgets
        .where((s) => s.label.toLowerCase().contains(q) || s.package.toLowerCase().contains(q))
        .toList();
    final plugins = PluginSnapshot.latest
        .where((p) =>
            p.isValid &&
            p.kinds.contains('bar-widget') &&
            ((p.manifest?.name.toLowerCase().contains(q) ?? false) ||
                p.id.toLowerCase().contains(q)))
        .toList();

    return SafeArea(
      child: DefaultTabController(
        length: 3,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 16, 20, 8),
              child: Text(
                'Añadir widget al escritorio',
                style: TextStyle(fontSize: 13, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600),
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
              child: TextField(
                onChanged: (v) => setState(() => _query = v),
                style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8)),
                decoration: InputDecoration(
                  hintText: 'Buscar widget…',
                  hintStyle: const TextStyle(fontSize: 12, color: Color(0xFF5A6B7A)),
                  prefixIcon: const Icon(Icons.search, size: 18, color: Color(0xFF5A6B7A)),
                  isDense: true,
                  border: OutlineInputBorder(
                    borderRadius: BorderRadius.circular(12),
                    borderSide: const BorderSide(color: Color(0x2BFFFFFF)),
                  ),
                ),
              ),
            ),
            const TabBar(
              indicatorColor: Color(0xFF66E0FF),
              labelColor: Color(0xFF66E0FF),
              unselectedLabelColor: Color(0xFF7A8A99),
              labelStyle: TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
              tabs: [
                Tab(text: 'Launcher'),
                Tab(text: 'Sistema'),
                Tab(text: 'Plugins'),
              ],
            ),
            Flexible(
              child: SizedBox(
                height: MediaQuery.sizeOf(context).height * 0.42,
                child: TabBarView(
                  children: [
                    _launcherTab(launcher),
                    _systemTab(system),
                    _pluginsTab(plugins),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    );
  }

  Widget _launcherTab(List<Map<String, dynamic>> items) {
    if (items.isEmpty) return _empty('Sin resultados de launcher.');
    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      children: [
        for (final w in items)
          ListTile(
            dense: true,
            leading: _MiniWidgetPreview(node: w['node'] as Map<String, dynamic>),
            title: Text(w['name'] as String, style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8))),
            subtitle: Text(w['desc'] as String, style: const TextStyle(fontSize: 11, color: Color(0xFF7A8A99))),
            onTap: () => Navigator.of(context).pop(w['node'] as Map<String, dynamic>),
          ),
      ],
    );
  }

  Widget _systemTab(List<SystemWidgetInfo> items) {
    if (_loadingSystem) {
      return const Center(
        child: Text('Cargando widgets del sistema…', style: TextStyle(fontSize: 12, color: Color(0xFF7A8A99))),
      );
    }
    if (items.isEmpty) {
      return _empty(_query.isEmpty ? 'No hay widgets del sistema disponibles.' : 'Sin resultados de sistema.');
    }
    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      children: [
        for (final sw in items)
          ListTile(
            dense: true,
            leading: _AppIconLeading(app: InstalledApp(package: sw.package, activity: '', label: sw.label)),
            title: Text(sw.label, style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8))),
            subtitle: Text(sw.package, style: const TextStyle(fontSize: 11, color: Color(0xFF7A8A99))),
            onTap: () => Navigator.of(context).pop(<String, dynamic>{
              'type': 'system_widget',
              'label': sw.label,
              'provider': sw.provider,
              'package': sw.package,
              'minWidth': sw.minWidth,
              'minHeight': sw.minHeight,
            }),
          ),
      ],
    );
  }

  Widget _pluginsTab(List<OhmPlugin> items) {
    if (items.isEmpty) {
      return _empty(_query.isEmpty ? 'Sin plugins bar-widget instalados. Explóralos en el marketplace.' : 'Sin resultados de plugins.');
    }
    return ListView(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      children: [
        for (final p in items)
          ListTile(
            dense: true,
            leading: const Icon(Icons.extension_outlined, color: Color(0xFFB48AFF), size: 22),
            title: Text(p.manifest?.name ?? p.id, style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8))),
            subtitle: Text(p.id, style: const TextStyle(fontSize: 11, color: Color(0xFF7A8A99))),
            onTap: () => Navigator.of(context).pop(<String, dynamic>{
              'type': 'plugin_widget',
              'pluginId': p.manifest?.id ?? p.id,
              'kind': 'bar-widget',
            }),
          ),
      ],
    );
  }

  Widget _empty(String msg) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Text(msg, style: const TextStyle(fontSize: 12, color: Color(0xFF5A6B7A))),
      ),
    );
  }
}

/// Vista previa en miniatura de un widget del launcher.
class _MiniWidgetPreview extends StatelessWidget {
  const _MiniWidgetPreview({required this.node});

  final Map<String, dynamic> node;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 38,
      height: 38,
      padding: const EdgeInsets.all(2),
      decoration: BoxDecoration(
        color: const Color(0xFF16202A),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: const Color(0x2BFFFFFF)),
      ),
      child: ClipRect(
        child: Center(
          child: FittedBox(
            fit: BoxFit.scaleDown,
            child: SizedBox(
              width: 120,
              child: DynamicWidgetEngine.buildNode(node, origin: 'preview'),
            ),
          ),
        ),
      ),
    );
  }
}

/// Icono de la app proveedora de un AppWidget del sistema.
class _AppIconLeading extends StatelessWidget {
  const _AppIconLeading({required this.app});

  final InstalledApp app;

  @override
  Widget build(BuildContext context) {
    return _LazyAppIcon(app: app, size: 38, padding: 2, radius: 8);
  }
}

/// Renderiza un AppWidget del sistema de verdad mediante AppWidgetHost
/// (PlatformView de Android) embebido en Flutter.
class _BoxAppPickerSheet extends StatefulWidget {
  const _BoxAppPickerSheet({required this.apps, required this.favorites});

  final List<InstalledApp> apps;
  final List<String> favorites;

  @override
  State<_BoxAppPickerSheet> createState() => _BoxAppPickerSheetState();
}

class _BoxAppPickerSheetState extends State<_BoxAppPickerSheet> {
  final Set<String> _selected = {};
  String _query = '';
  final TextEditingController _queryController = TextEditingController();

  @override
  void dispose() {
    _queryController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final q = _query.trim().toLowerCase();
    final apps = widget.apps
        .where((a) => a.label.toLowerCase().contains(q) || a.package.toLowerCase().contains(q))
        .toList();
    return SafeArea(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
            child: Row(
              children: [
                const Expanded(
                  child: Text(
                    'Elige aplicaciones',
                    style: TextStyle(fontSize: 14, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600),
                  ),
                ),
                TextButton(
                  onPressed: _selected.isEmpty
                      ? null
                      : () {
                          final chosen = widget.apps
                              .where((a) => _selected.contains(a.key))
                              .toList();
                          Navigator.of(context).pop(chosen);
                        },
                  child: Text(
                    'Añadir (${_selected.length})',
                    style: TextStyle(
                      color: _selected.isEmpty ? const Color(0xFF5A6B7A) : const Color(0xFF66E0FF),
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: TextField(
              controller: _queryController,
              onChanged: (v) => setState(() => _query = v),
              onSubmitted: (_) {
                if (apps.length == 1) {
                  final chosen = apps.first;
                  if (_selected.contains(chosen.key)) {
                    _selected.remove(chosen.key);
                  } else {
                    _selected.add(chosen.key);
                  }
                  Navigator.of(context).pop(
                    widget.apps.where((a) => _selected.contains(a.key)).toList(),
                  );
                } else {
                  FocusScope.of(context).unfocus();
                }
              },
              style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8)),
              decoration: InputDecoration(
                hintText: 'Buscar app…',
                hintStyle: const TextStyle(fontSize: 12, color: Color(0xFF5A6B7A)),
                prefixIcon: const Icon(Icons.search, size: 18, color: Color(0xFF5A6B7A)),
                suffixIcon: _query.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.clear, size: 18, color: Color(0xFF5A6B7A)),
                        onPressed: () {
                          _queryController.clear();
                          setState(() => _query = '');
                        },
                      ),
                isDense: true,
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                  borderSide: const BorderSide(color: Color(0x2BFFFFFF)),
                ),
              ),
            ),
          ),
          Flexible(
            child: ConstrainedBox(
              constraints: BoxConstraints(
                maxHeight: MediaQuery.sizeOf(context).height * 0.5,
              ),
              child: ListView.builder(
                itemCount: apps.length,
                itemBuilder: (context, i) {
                  final app = apps[i];
                  final selected = _selected.contains(app.key);
                  final isFav = widget.favorites.contains(app.key);
                  return ListTile(
                    dense: true,
                    leading: _AppIconLeading(app: app),
                    title: Text(app.label, style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8))),
                    subtitle: Text(app.package, style: const TextStyle(fontSize: 11, color: Color(0xFF7A8A99))),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          isFav ? Icons.star : Icons.star_border,
                          size: 18,
                          color: isFav ? const Color(0xFFF5DE6A) : const Color(0xFF5A6B7A),
                        ),
                        const SizedBox(width: 8),
                        Icon(
                          selected ? Icons.check_circle : Icons.radio_button_unchecked,
                          size: 20,
                          color: selected ? const Color(0xFF66E0FF) : const Color(0xFF5A6B7A),
                        ),
                      ],
                    ),
                    onTap: () {
                      setState(() {
                        if (selected) {
                          _selected.remove(app.key);
                        } else {
                          _selected.add(app.key);
                        }
                      });
                    },
                  );
                },
              ),
            ),
          ),
          const SizedBox(height: 8),
        ],
      ),
    );
  }
}

class _BoxWidget extends StatelessWidget {
  const _BoxWidget({
    required this.items,
    required this.direction,
    required this.boxIndex,
    this.onAddContent,
  });

  final List<Object?> items;
  final String direction;
  final int boxIndex;
  final void Function(int boxIndex)? onAddContent;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: const Color(0xFF16202A),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0x2BFFFFFF)),
      ),
      padding: const EdgeInsets.all(6),
      child: items.isEmpty
          ? _addButton()
          : _content(),
    );
  }

  Widget _addButton() {
    return Center(
      child: IconButton(
        icon: const Icon(Icons.add, size: 24, color: Color(0xFF66E0FF)),
        onPressed: () => onAddContent?.call(boxIndex),
        tooltip: 'Añadir contenido',
      ),
    );
  }

  Widget _content() {
    final children = <Widget>[
      for (final item in items) _item(item),
      if (onAddContent != null) _miniAddButton(),
    ];
    return switch (direction) {
      'vertical' => Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: children,
        ),
      'grid' => GridView.count(
          crossAxisCount: 2,
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          childAspectRatio: 1.2,
          children: children,
        ),
      _ => SingleChildScrollView(
          scrollDirection: Axis.horizontal,
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: children,
          ),
        ),
    };
  }

  Widget _miniAddButton() {
    return Padding(
      padding: const EdgeInsets.all(4),
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: () => onAddContent?.call(boxIndex),
        child: const Padding(
          padding: EdgeInsets.all(4),
          child: Icon(Icons.add, size: 16, color: Color(0xFF66E0FF)),
        ),
      ),
    );
  }

  Widget _item(Object? raw) {
    final map = raw is Map ? raw : const {};
    final type = map['type'] is String ? map['type'] as String : 'app';
    return switch (type) {
      'app' => _BoxAppItem(app: map),
      'system_widget' => _BoxGenericItem(
          icon: Icons.widgets_outlined,
          label: map['label'] is String ? map['label'] as String : 'Widget',
        ),
      'plugin' => _BoxGenericItem(
          icon: Icons.extension_outlined,
          label: map['pluginId'] is String ? map['pluginId'] as String : 'Plugin',
        ),
      _ => _BoxGenericItem(
          icon: Icons.help_outline,
          label: 'Elemento',
        ),
    };
  }
}

class _BoxAppItem extends StatelessWidget {
  const _BoxAppItem({required this.app});

  final Map<dynamic, dynamic> app;

  @override
  Widget build(BuildContext context) {
    final label = app['label'] is String ? app['label'] as String : 'App';
    final package = app['package'] is String ? app['package'] as String : '';
    final activity = app['activity'] is String ? app['activity'] as String : '';
    return Padding(
      padding: const EdgeInsets.all(4),
      child: InkWell(
        borderRadius: BorderRadius.circular(8),
        onTap: () => OhmPlatform.launchApp(InstalledApp(
          label: label,
          package: package,
          activity: activity,
        )),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.android, size: 24, color: Color(0xFF66E0FF)),
            const SizedBox(height: 2),
            Text(
              label,
              style: const TextStyle(fontSize: 9, color: Color(0xFFE8F1F8)),
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ],
        ),
      ),
    );
  }
}

class _BoxGenericItem extends StatelessWidget {
  const _BoxGenericItem({required this.icon, required this.label});

  final IconData icon;
  final String label;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(4),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 24, color: const Color(0xFF66E0FF)),
          const SizedBox(height: 2),
          Text(
            label,
            style: const TextStyle(fontSize: 9, color: Color(0xFFE8F1F8)),
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
          ),
        ],
      ),
    );
  }
}

class _SystemAppWidget extends StatefulWidget {
  const _SystemAppWidget({required this.provider, required this.fallbackLabel});

  final String provider;
  final String fallbackLabel;

  @override
  State<_SystemAppWidget> createState() => _SystemAppWidgetState();
}

class _SystemAppWidgetState extends State<_SystemAppWidget> {
  int? _id;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _bind();
  }

  Future<void> _bind() async {
    try {
      final result = await OhmPlatform.bindAppWidget(widget.provider);
      if (!mounted) return;
      if (result['needsBind'] == true) {
        // Espera la autorización del sistema; el resultado llega por callback.
        OhmPlatform.onWidgetBound = (id, provider) {
          if (!mounted) return;
          setState(() {
            _id = id;
            _loading = false;
          });
        };
        OhmPlatform.onWidgetBindFailed = (id, provider) {
          if (!mounted) return;
          setState(() {
            _error = 'El usuario canceló la autorización del widget';
            _loading = false;
          });
        };
        setState(() => _loading = true);
      } else {
        setState(() {
          _id = result['id'] as int;
          _loading = false;
        });
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '$e';
        _loading = false;
      });
    }
  }

  @override
  void dispose() {
    final id = _id;
    if (id != null) unawaited(OhmPlatform.unbindAppWidget(id));
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return Container(
        decoration: BoxDecoration(
          color: const Color(0xFF16202A),
          borderRadius: BorderRadius.circular(12),
        ),
        child: Center(
          child: Text(
            widget.fallbackLabel,
            style: const TextStyle(fontSize: 12, color: Color(0xFF7A8A99)),
          ),
        ),
      );
    }
    if (_error != null) {
      return Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: const Color(0xFF16202A),
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: const Color(0x2BFFFFFF)),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.info_outline, size: 16, color: Color(0xFFF0A35E)),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(widget.fallbackLabel,
                    style: const TextStyle(fontSize: 11, color: Color(0xFFE8F1F8))),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Text(_error!, maxLines: 3, overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 9, color: Color(0xFF7A8A99))),
          ],
        ),
      );
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(10),
      child: Container(
        color: const Color(0xFF16202A),
        child: AndroidView(
          viewType: 'com.ohm/appwidget',
          creationParams: <String, dynamic>{'id': _id},
          creationParamsCodec: const StandardMessageCodec(),
        ),
      ),
    );
  }
}
