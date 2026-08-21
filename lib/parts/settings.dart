part of 'package:ohm_launcher/main.dart';

/// Hoja de configuración del launcher: tipografía, escala, fondo y launcher por defecto.
class _DesktopSettingsSheet extends StatefulWidget {
  const _DesktopSettingsSheet({
    required this.currentFontFamily,
    required this.currentTitleFont,
    required this.currentGridCols,
    required this.currentGridRows,
    required this.onFontFamily,
    required this.onTitleFont,
    required this.onBackground,
    required this.onBackgroundImage,
    required this.onGridCols,
    required this.onGridRows,
  });

  final String currentFontFamily;
  final String currentTitleFont;
  final int currentGridCols;
  final int currentGridRows;
  final ValueChanged<String> onFontFamily;
  final ValueChanged<String> onTitleFont;
  final ValueChanged<String> onBackground;
  final ValueChanged<String> onBackgroundImage;
  final ValueChanged<int> onGridCols;
  final ValueChanged<int> onGridRows;

  @override
  State<_DesktopSettingsSheet> createState() => _DesktopSettingsSheetState();
}

class _DesktopSettingsSheetState extends State<_DesktopSettingsSheet> {
  late String _font = widget.currentFontFamily;
  late String _titleFont = widget.currentTitleFont;
  late int _gridCols = widget.currentGridCols;
  late int _gridRows = widget.currentGridRows;

  static const _fonts = <String>[
    'Predeterminada',
    'monospace',
    'serif',
  ];

  static const _googleFonts = <String>[
    'Abril Fatface',
    'Bebas Neue',
    'Fira Sans',
    'Inter',
    'Josefin Sans',
    'Lato',
    'Lobster',
    'Merriweather',
    'Montserrat',
    'Nunito',
    'Open Sans',
    'Oswald',
    'Playfair Display',
    'Poppins',
    'PT Sans',
    'Raleway',
    'Roboto',
    'Source Sans Pro',
    'Ubuntu',
    'Work Sans',
  ];

  static const _backgrounds = <String>[
    '#0B0E11',
    '#10161C',
    '#0A1A12',
    '#101A2A',
    '#1A0B0B',
  ];

  Future<String?> _pickGoogleFont(BuildContext context, String current) async {
    final chosen = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (context) {
        return SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Text(
                  'Fuentes de Google Fonts',
                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: Color(0xFFE8F1F8)),
                ),
                const SizedBox(height: 12),
                Expanded(
                  child: ListView.builder(
                    itemCount: _googleFonts.length,
                    itemBuilder: (context, i) {
                      final name = _googleFonts[i];
                      final style = GoogleFonts.asMap().containsKey(name)
                          ? GoogleFonts.getFont(name)
                          : const TextStyle();
                      return ListTile(
                        dense: true,
                        title: Text(
                          name,
                          style: style.copyWith(fontSize: 16, color: const Color(0xFFE8F1F8)),
                        ),
                        trailing: current == name
                            ? const Icon(Icons.check, color: Color(0xFF66E0FF), size: 18)
                            : null,
                        onTap: () => Navigator.of(context).pop(name),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        );
      },
    );
    return chosen;
  }

  Widget _buildFontChips(String current, ValueChanged<String> onChange) {
    final extras = <String>[
      if (current != 'Predeterminada' && !_fonts.contains(current)) current,
    ];
    return Wrap(
      spacing: 8,
      children: [
        for (final f in _fonts)
          ChoiceChip(
            label: Text(f, style: const TextStyle(fontSize: 12)),
            selected: current == f,
            onSelected: (_) {
              onChange(f);
              setState(() {});
            },
          ),
        for (final f in extras)
          ChoiceChip(
            label: Text(f, style: const TextStyle(fontSize: 12)),
            selected: true,
            onSelected: (_) {},
          ),
      ],
    );
  }

  Future<void> _pickBackgroundImage(BuildContext context) async {
    const dirs = <String>[
      '/sdcard/OhmLauncher/wallpapers',
      '/sdcard/Pictures',
      '/sdcard/Download',
      '/sdcard/DCIM/Camera',
    ];
    final images = <String>[];
    for (final d in dirs) {
      final dir = Directory(d);
      if (!await dir.exists()) continue;
      try {
        final files = dir.listSync().whereType<File>().where((f) {
          final n = f.path.toLowerCase();
          return n.endsWith('.jpg') || n.endsWith('.jpeg') || n.endsWith('.png') || n.endsWith('.webp');
        }).toList()
          ..sort((a, b) => a.path.compareTo(b.path));
        images.addAll(files.map((f) => f.path));
      } catch (_) {}
    }
    if (!context.mounted) return;
    if (images.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('No se encontraron imágenes en el dispositivo')),
      );
      return;
    }
    final chosen = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: const Color(0xFF10161C),
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
      ),
      builder: (context) {
        return SafeArea(
          child: GridView.count(
            crossAxisCount: 3,
            padding: const EdgeInsets.all(12),
            mainAxisSpacing: 8,
            crossAxisSpacing: 8,
            children: [
              for (final path in images)
                GestureDetector(
                  onTap: () => Navigator.of(context).pop(path),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(8),
                    child: Image.file(File(path), fit: BoxFit.cover),
                  ),
                ),
            ],
          ),
        );
      },
    );
    if (chosen != null) widget.onBackgroundImage(chosen);
  }

  Widget _gridStepper(String label, int value, ValueChanged<int> onChange) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(label, style: const TextStyle(fontSize: 10, color: Color(0xFF5A6B7A))),
        const SizedBox(height: 4),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            IconButton(
              visualDensity: VisualDensity.compact,
              icon: const Icon(Icons.remove_circle_outline, size: 18, color: Color(0xFF66E0FF)),
              onPressed: () => onChange((value - 1).clamp(4, 24)),
            ),
            Text('$value', style: const TextStyle(fontSize: 15, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600)),
            IconButton(
              visualDensity: VisualDensity.compact,
              icon: const Icon(Icons.add_circle_outline, size: 18, color: Color(0xFF66E0FF)),
              onPressed: () => onChange((value + 1).clamp(4, 24)),
            ),
          ],
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
            const Text(
              'Configuración del escritorio',
              style: TextStyle(fontSize: 13, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 16),
            const Text('TIPOGRAFÍA PRINCIPAL', style: TextStyle(fontSize: 10, color: Color(0xFF5A6B7A), letterSpacing: 3)),
            const SizedBox(height: 8),
            _buildFontChips(_font, (f) => widget.onFontFamily(f)),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                onPressed: () async {
                  final chosen = await _pickGoogleFont(context, _font);
                  if (chosen != null && mounted) {
                    setState(() => _font = chosen);
                    widget.onFontFamily(chosen);
                  }
                },
                icon: const Icon(Icons.font_download_outlined, size: 18, color: Color(0xFF66E0FF)),
                label: const Text('Más fuentes de Google…', style: TextStyle(color: Color(0xFF66E0FF))),
              ),
            ),
            const SizedBox(height: 16),
            const Text('TIPOGRAFÍA DE TÍTULOS', style: TextStyle(fontSize: 10, color: Color(0xFF5A6B7A), letterSpacing: 3)),
            const SizedBox(height: 8),
            _buildFontChips(_titleFont, (f) => widget.onTitleFont(f)),
            const SizedBox(height: 8),
            Align(
              alignment: Alignment.centerLeft,
              child: TextButton.icon(
                onPressed: () async {
                  final chosen = await _pickGoogleFont(context, _titleFont);
                  if (chosen != null && mounted) {
                    setState(() => _titleFont = chosen);
                    widget.onTitleFont(chosen);
                  }
                },
                icon: const Icon(Icons.title, size: 18, color: Color(0xFF66E0FF)),
                label: const Text('Fuente de títulos de Google…', style: TextStyle(color: Color(0xFF66E0FF))),
              ),
            ),
            const SizedBox(height: 16),
            const Text('GRILLA DEL ESCRITORIO', style: TextStyle(fontSize: 10, color: Color(0xFF5A6B7A), letterSpacing: 3)),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                _gridStepper('Columnas', _gridCols, (v) {
                  setState(() => _gridCols = v);
                  widget.onGridCols(v);
                }),
                _gridStepper('Filas', _gridRows, (v) {
                  setState(() => _gridRows = v);
                  widget.onGridRows(v);
                }),
              ],
            ),
            const SizedBox(height: 8),
            const Text('FONDO DEL ESCRITORIO', style: TextStyle(fontSize: 10, color: Color(0xFF5A6B7A), letterSpacing: 3)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 10,
              children: [
                for (final hex in _backgrounds)
                  GestureDetector(
                    onTap: () => widget.onBackground(hex),
                    child: Container(
                      width: 40,
                      height: 40,
                      decoration: BoxDecoration(
                        color: DynamicWidgetEngine.colorFromHex(hex),
                        shape: BoxShape.circle,
                        border: Border.all(color: const Color(0xFF3A4654), width: 1),
                      ),
                    ),
                  ),
              ],
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () => _pickBackgroundImage(context),
                    icon: const Icon(Icons.image_outlined, size: 18, color: Color(0xFF66E0FF)),
                    label: const Text('Fondo con imagen…', style: TextStyle(color: Color(0xFF66E0FF))),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton(
                  tooltip: 'Quitar imagen de fondo',
                  onPressed: () => widget.onBackgroundImage(''),
                  icon: const Icon(Icons.no_photography_outlined, size: 18, color: Color(0xFF5A6B7A)),
                ),
              ],
            ),
          ],
        ),
      ),
    ),
  );
}
}

// ---------------------------------------------------------------------------
//  Configuración del launcher (global): tamaño de texto, navegación, gestos.
// ---------------------------------------------------------------------------
class _LauncherSettingsSheet extends StatefulWidget {
  const _LauncherSettingsSheet({
    required this.currentTextScale,
    required this.currentBoxSpacing,
    required this.currentLanguage,
    required this.gestureNavigationEnabled,
    required this.showTapBoxes,
    required this.onTextScale,
    required this.onBoxSpacing,
    required this.onLanguage,
    required this.onGestureNavigationEnabled,
    required this.onShowTapBoxes,
    required this.onFavoritesBarMode,
    this.currentFavoritesBarMode,
    this.onGesturesForced,
    this.apiServerEnabled = true,
    this.apiServerPort = 8753,
    required this.onApiServerEnabled,
    required this.onApiServerPort,
    this.shellPreferTermux = false,
    required this.onShellPreferTermux,
    this.quakeTerminal = true,
    required this.onQuakeTerminal,
    this.aiBaseUrl = '',
    this.aiApiKey = '',
    this.aiModel = '',
    this.aiSystemPrompt = '',
    required this.onAiBaseUrl,
    required this.onAiApiKey,
    required this.onAiModel,
    required this.onAiSystemPrompt,
  });

  final double currentTextScale;
  final double currentBoxSpacing;
  final String currentLanguage;
  final bool gestureNavigationEnabled;
  final bool showTapBoxes;
  final String? currentFavoritesBarMode;
  final ValueChanged<String> onFavoritesBarMode;
  final ValueChanged<double> onTextScale;
  final ValueChanged<double> onBoxSpacing;
  final ValueChanged<String> onLanguage;
  final ValueChanged<bool> onGestureNavigationEnabled;
  final ValueChanged<bool> onShowTapBoxes;
  final VoidCallback? onGesturesForced;
  final bool apiServerEnabled;
  final int apiServerPort;
  final ValueChanged<bool> onApiServerEnabled;
  final ValueChanged<int> onApiServerPort;
  final bool shellPreferTermux;
  final ValueChanged<bool> onShellPreferTermux;
  final bool quakeTerminal;
  final ValueChanged<bool> onQuakeTerminal;
  final String aiBaseUrl;
  final String aiApiKey;
  final String aiModel;
  final String aiSystemPrompt;
  final ValueChanged<String> onAiBaseUrl;
  final ValueChanged<String> onAiApiKey;
  final ValueChanged<String> onAiModel;
  final ValueChanged<String> onAiSystemPrompt;

  @override
  State<_LauncherSettingsSheet> createState() => _LauncherSettingsSheetState();
}

class _LauncherSettingsSheetState extends State<_LauncherSettingsSheet> {
  late double _scale = widget.currentTextScale;
  late double _boxSpacing = widget.currentBoxSpacing;
  late String _language = widget.currentLanguage;
  late bool _gestureNavEnabled = widget.gestureNavigationEnabled;
  late bool _showTapBoxes = widget.showTapBoxes;
  late String _favBarMode = widget.currentFavoritesBarMode ?? 'auto';
  late bool _apiServerEnabled = widget.apiServerEnabled;
  late int _apiServerPort = widget.apiServerPort;
  late bool _shellPreferTermux = widget.shellPreferTermux;
  late bool _quakeTerminal = widget.quakeTerminal;
  late String _aiBaseUrl = widget.aiBaseUrl;
  late String _aiApiKey = widget.aiApiKey;
  late String _aiModel = widget.aiModel;
  late String _aiSystemPrompt = widget.aiSystemPrompt;
  int _tab = 0;
  bool _checkingDefault = false;
  Timer? _textScaleDebounce;
  Timer? _boxSpacingDebounce;

  @override
  void dispose() {
    _textScaleDebounce?.cancel();
    _boxSpacingDebounce?.cancel();
    super.dispose();
  }

  void _debounceTextScale(double v) {
    _textScaleDebounce?.cancel();
    _textScaleDebounce = Timer(const Duration(milliseconds: 120), () {
      widget.onTextScale(v);
    });
  }

  void _debounceBoxSpacing(double v) {
    _boxSpacingDebounce?.cancel();
    _boxSpacingDebounce = Timer(const Duration(milliseconds: 120), () {
      widget.onBoxSpacing(v);
    });
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Configuración del launcher',
              style: TextStyle(fontSize: 13, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600),
            ),
            const SizedBox(height: 12),
            // Tabs
            Container(
              padding: const EdgeInsets.all(4),
              decoration: BoxDecoration(
                color: const Color(0xFF1A2330),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Row(
                children: [
                  _tabButton('Visual', 0),
                  _tabButton('Gestos', 1),
                  _tabButton('API / IA', 2),
                ],
              ),
            ),
            const SizedBox(height: 16),
            Flexible(
              child: SingleChildScrollView(
                child: _tab == 0
                    ? _buildVisualTab()
                    : _tab == 1
                        ? _buildGesturesTab()
                        : _buildApiTab(),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _tabButton(String label, int index) {
    final selected = _tab == index;
    return Expanded(
      child: GestureDetector(
        onTap: () => setState(() => _tab = index),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          curve: Curves.easeOut,
          padding: const EdgeInsets.symmetric(vertical: 10),
          decoration: BoxDecoration(
            color: selected ? const Color(0xFF66E0FF).withValues(alpha: 0.18) : Colors.transparent,
            borderRadius: BorderRadius.circular(10),
          ),
          child: Text(
            label,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 12,
              fontWeight: selected ? FontWeight.w600 : FontWeight.normal,
              color: selected ? const Color(0xFF66E0FF) : const Color(0xFF9AA7B4),
            ),
          ),
        ),
      ),
    );
  }

  Widget _sectionLabel(String label) => Padding(
        padding: const EdgeInsets.only(bottom: 8),
        child: Text(
          label,
          style: const TextStyle(fontSize: 10, color: Color(0xFF5A6B7A), letterSpacing: 3),
        ),
      );

  Widget _caption(String text) => Padding(
        padding: const EdgeInsets.only(top: 4, bottom: 12),
        child: Text(
          text,
          style: const TextStyle(fontSize: 10, color: Color(0xFF5A6B7A)),
        ),
      );

  Widget _buildVisualTab() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _sectionLabel('TIPOGRAFÍA'),
        Slider(
          value: _scale,
          min: 0.8,
          max: 1.4,
          divisions: 6,
          label: '${(_scale * 100).round()}%',
          onChanged: (v) {
            setState(() => _scale = v);
            _debounceTextScale(v);
          },
          onChangeEnd: (v) {
            _textScaleDebounce?.cancel();
            widget.onTextScale(v);
          },
        ),
        _caption('Tamaño de texto global del launcher.'),
        _sectionLabel('ESPACIADO EN CAJAS'),
        Slider(
          value: _boxSpacing,
          min: 0.0,
          max: 2.0,
          divisions: 8,
          label: '${(_boxSpacing * 100).round()}%',
          onChanged: (v) {
            setState(() => _boxSpacing = v);
            _debounceBoxSpacing(v);
          },
          onChangeEnd: (v) {
            _boxSpacingDebounce?.cancel();
            widget.onBoxSpacing(v);
          },
        ),
        _caption('Escala del espaciado interno de las cajas de borde.'),
        _sectionLabel('BARRA DE FAVORITOS'),
        Wrap(
          spacing: 8,
          children: [
            for (final m in const ['auto', 'horizontal', 'vertical', 'grid', 'list'])
              ChoiceChip(
                label: Text(m == 'auto'
                    ? 'Automático'
                    : m == 'horizontal'
                        ? 'Horizontal'
                        : m == 'vertical'
                            ? 'Vertical'
                            : m == 'grid'
                                ? 'Grilla'
                                : 'Lista'),
                selected: _favBarMode == m,
                materialTapTargetSize: MaterialTapTargetSize.padded,
                onSelected: (_) {
                  setState(() => _favBarMode = m);
                  widget.onFavoritesBarMode(m);
                },
              ),
          ],
        ),
        _caption('Disposición de la barra de apps favoritas. "Automático" la adapta al borde (horizontal arriba/abajo, vertical a los lados). "Lista" muestra cada app como fila con icono y nombre.'),
        _sectionLabel('IDIOMA'),
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12),
          decoration: BoxDecoration(
            color: const Color(0xFF1A2330),
            borderRadius: BorderRadius.circular(12),
          ),
          child: DropdownButtonHideUnderline(
            child: DropdownButton<String>(
              value: _language,
              isExpanded: true,
              dropdownColor: const Color(0xFF1A2330),
              style: const TextStyle(fontSize: 12, color: Color(0xFFE8F1F8)),
              icon: const Icon(Icons.language, size: 18, color: Color(0xFF66E0FF)),
              items: const [
                DropdownMenuItem(value: 'auto', child: Text('Automático')),
                DropdownMenuItem(value: 'default', child: Text('Por defecto del sistema')),
              ],
              onChanged: (v) {
                if (v == null) return;
                setState(() => _language = v);
                widget.onLanguage(v);
              },
            ),
          ),
        ),
        _caption('Idioma de la interfaz. "Automático" detecta el idioma del sistema; "Por defecto" usa el configurado en el escritorio.'),
        _sectionLabel('DEPURACIÓN'),
        Row(
          children: [
            const Expanded(
              child: Text(
                'Mostrar áreas de toque',
                style: TextStyle(fontSize: 12, color: Color(0xFF9AA7B4)),
              ),
            ),
            Switch(
              value: _showTapBoxes,
              onChanged: (v) {
                setState(() => _showTapBoxes = v);
                widget.onShowTapBoxes(v);
              },
            ),
          ],
        ),
        _caption('Dibuja contornos sobre las zonas que capturan toques (cajas de borde y detectores de gestos de los bordes) para ver qué está interceptando los taps.'),
      ],
    );
  }

  Widget _buildGesturesTab() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _sectionLabel('NAVEGACIÓN'),
        Row(
          children: [
            Expanded(
              child: OutlinedButton.icon(
                onPressed: () async {
                  final messenger = ScaffoldMessenger.of(context);
                  final ok = await OhmPlatform.restoreGestureNavigation();
                  if (!mounted) return;
                  if (ok) widget.onGesturesForced?.call();
                  messenger.showSnackBar(
                    SnackBar(
                      content: Text(
                        ok
                            ? 'Navegación por gestos restaurada ✓'
                            : 'No se pudo restaurar gestos. Concede permiso por ADB: adb shell pm grant cl.villagranquiroz.ohm_launcher android.permission.WRITE_SECURE_SETTINGS',
                      ),
                    ),
                  );
                },
                icon: const Icon(Icons.gesture, size: 18, color: Color(0xFF66E0FF)),
                label: const Text('Forzar gestos', style: TextStyle(color: Color(0xFF66E0FF))),
              ),
            ),
            const SizedBox(width: 8),
            Expanded(
              child: OutlinedButton.icon(
                onPressed: () => OhmPlatform.openNavigationSettings(),
                icon: const Icon(Icons.settings, size: 18, color: Color(0xFF66E0FF)),
                label: const Text('Ajustes', style: TextStyle(color: Color(0xFF66E0FF))),
              ),
            ),
          ],
        ),
        _caption('Por defecto se usan los botones de Android. Si prefieres gestos, actívalos aquí.'),
        Row(
          children: [
            const Text('Gestos internos (fallback)', style: TextStyle(fontSize: 12, color: Color(0xFF9AA7B4))),
            const Spacer(),
            Switch(
              value: _gestureNavEnabled,
              onChanged: (v) {
                setState(() => _gestureNavEnabled = v);
                widget.onGestureNavigationEnabled(v);
              },
            ),
          ],
        ),
        _caption('Si Xiaomi bloquea los gestos del sistema, Ohm captura bordes de pantalla para Atrás/Inicio/Recientes dentro del launcher.'),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          onPressed: () => OhmPlatform.openAccessibilitySettings(),
          icon: const Icon(Icons.accessibility_new, size: 18, color: Color(0xFF66E0FF)),
          label: const Text('Activar gestos globales (accesibilidad)', style: TextStyle(color: Color(0xFF66E0FF))),
        ),
        _caption('Para gestos en cualquier app, activa el servicio de accesibilidad de Ohm Launcher. Requiere aprobación manual del sistema.'),
        const SizedBox(height: 20),
        OutlinedButton.icon(
          onPressed: _checkingDefault
              ? null
              : () async {
                  setState(() => _checkingDefault = true);
                  final messenger = ScaffoldMessenger.of(context);
                  try {
                    final isDefault = await OhmPlatform.isDefaultLauncher();
                    if (!mounted) return;
                    if (isDefault) {
                      messenger.showSnackBar(
                        const SnackBar(content: Text('Ohm Launcher ya es el launcher por defecto ✓')),
                      );
                    } else {
                      messenger.showSnackBar(
                        const SnackBar(content: Text('Elige "Ohm Launcher" como launcher por defecto…')),
                      );
                      await OhmPlatform.requestDefaultLauncher();
                    }
                  } finally {
                    if (mounted) setState(() => _checkingDefault = false);
                  }
                },
          icon: const Icon(Icons.home_outlined, size: 18),
          label: Text(_checkingDefault ? 'Comprobando…' : 'Establecer como launcher por defecto'),
        ),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          onPressed: () => OhmPlatform.openNavigationSettings(),
          icon: const Icon(Icons.swipe, size: 18, color: Color(0xFF66E0FF)),
          label: const Text(
            'Abrir Ajustes de navegación (gestos)',
            style: TextStyle(color: Color(0xFF66E0FF)),
          ),
        ),
        _caption('En Xiaomi/MIUI cambiar de launcher puede desactivar los gestos; actívalos aquí.'),
      ],
    );
  }

  Widget _apiTextField({
    required String label,
    required String value,
    required ValueChanged<String> onChanged,
    String? hint,
    bool obscure = false,
    int maxLines = 1,
  }) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.only(bottom: 6, top: 10),
          child: Text(label, style: const TextStyle(fontSize: 11, color: Color(0xFF9AA7B4))),
        ),
        TextField(
          controller: TextEditingController(text: value),
          obscureText: obscure,
          maxLines: maxLines,
          onChanged: onChanged,
          style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8)),
          decoration: InputDecoration(
            hintText: hint,
            hintStyle: const TextStyle(fontSize: 12, color: Color(0xFF5A6B7A)),
            filled: true,
            fillColor: const Color(0xFF16202A),
            border: OutlineInputBorder(borderRadius: BorderRadius.circular(12), borderSide: BorderSide.none),
            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          ),
        ),
      ],
    );
  }

  Widget _buildApiTab() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _sectionLabel('SERVIDOR API LOCAL'),
        Row(
          children: [
            const Expanded(
              child: Text('Servidor en localhost',
                  style: TextStyle(fontSize: 12, color: Color(0xFF9AA7B4))),
            ),
            Switch(
              value: _apiServerEnabled,
              onChanged: (v) {
                setState(() => _apiServerEnabled = v);
                widget.onApiServerEnabled(v);
              },
            ),
          ],
        ),
        _caption('Expones un control remoto del launcher en 127.0.0.1 sin abrir Termux. '
            'Endpoints: GET /health, POST /command {command,args?}, POST /widget {source,format?}, POST /ai {prompt}.'),
        _apiTextField(
          label: 'Puerto',
          value: '$_apiServerPort',
          hint: '8753',
          onChanged: (v) {
            final p = int.tryParse(v) ?? 8753;
            setState(() => _apiServerPort = p);
            widget.onApiServerPort(p);
          },
        ),
        _sectionLabel('EJECUCIÓN DE COMANDOS'),
        Row(
          children: [
            const Expanded(
              child: Text('Usar Termux si está disponible (opcional)',
                  style: TextStyle(fontSize: 12, color: Color(0xFF9AA7B4))),
            ),
            Switch(
              value: _shellPreferTermux,
              onChanged: (v) {
                setState(() => _shellPreferTermux = v);
                widget.onShellPreferTermux(v);
              },
            ),
          ],
        ),
        _caption('Por defecto los comandos corren EMBEBIDOS en el propio launcher '
            '(shell del sistema, sin apps de terceros). Activa esto solo si quieres '
            'reutilizar el entorno de paquetes de Termux y tienes Termux:API instalado.'),
        _sectionLabel('TERMINAL QUAKE'),
        Row(
          children: [
            const Expanded(
              child: Text('Terminal desplegable (swipe-down en la mitad superior)',
                  style: TextStyle(fontSize: 12, color: Color(0xFF9AA7B4))),
            ),
            Switch(
              value: _quakeTerminal,
              onChanged: (v) {
                setState(() => _quakeTerminal = v);
                widget.onQuakeTerminal(v);
              },
            ),
          ],
        ),
        _caption('Despliega una terminal real (PTY) con un swipe hacia abajo desde la '
            'mitad superior del escritorio. Desde ahí controlas el sistema y puedes '
            'usar bun/opencode/claude/kimi o tmux.'),
        _sectionLabel('ASISTENTE IA'),
        _caption('Cualquier endpoint compatible con /v1/chat/completions '
            '(OpenAI, Ollama, LM Studio, OpenRouter, Claude por proxy, Kimi, Codex…).'),
        _apiTextField(
          label: 'URL base',
          value: _aiBaseUrl,
          hint: 'https://api.openai.com/v1  ·  http://localhost:11434/v1',
          onChanged: (v) {
            setState(() => _aiBaseUrl = v);
            widget.onAiBaseUrl(v.trim());
          },
        ),
        _apiTextField(
          label: 'Modelo',
          value: _aiModel,
          hint: 'gpt-4o-mini · llama3.1 · claude-3-5-sonnet',
          onChanged: (v) {
            setState(() => _aiModel = v);
            widget.onAiModel(v.trim());
          },
        ),
        _apiTextField(
          label: 'Clave API',
          value: _aiApiKey,
          hint: 'opcional para localhost',
          obscure: true,
          onChanged: (v) {
            setState(() => _aiApiKey = v);
            widget.onAiApiKey(v.trim());
          },
        ),
        _apiTextField(
          label: 'System prompt (opcional)',
          value: _aiSystemPrompt,
          hint: 'Instrucciones para que la IA genere componentes JSON/QML',
          maxLines: 3,
          onChanged: (v) {
            setState(() => _aiSystemPrompt = v);
            widget.onAiSystemPrompt(v.trim());
          },
        ),
        _caption('El asistente (botón ⚡ abajo a la derecha) inyecta en caliente el '
            'componente que devuelva la IA en un bloque ```json o ```qml.'),
      ],
    );
  }
}
