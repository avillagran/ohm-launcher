part of 'package:ohm_launcher/main.dart';

// ============================================================================
//  3. MOTOR DE RENDERIZADO DINÁMICO (Custom Dynamic Interpreter)
//  ============================================================================
//  Convierte un String JSON en un Widget de Flutter sin dependencias de
//  terceros. Nodos soportados:
//
//    * container       -> Container (color, padding, borderRadius, children)
//    * text            -> Text (value, fontSize, color, fontWeight, letterSpacing)
//    * clock           -> Text con hora en vivo (format, fontSize, color...)
//                         style: "text" | "particles" (arena) | "ticker"
//    * tiling_layout   -> Row/Column según "orientation" (spacing, flex, ejes)
//    * spacer          -> SizedBox flexible para separar bloques del mosaico
//
//  Un JSON mal formado NUNCA crashea la app: se muestra un widget de error.
// ============================================================================

class DynamicWidgetEngine {
  DynamicWidgetEngine._();

  /// Tipografías activas del escritorio actual (seteadas por _DesktopPage).
  static String baseFont = '';
  static String titleFont = '';

  /// Callback global para cuando una caja pide añadir contenido.
  /// Se asigna desde _OhmHomeScreenState y se limpia al salir.
  static void Function(int boxIndex)? onBoxAddContent;

  static String _fontFor(Map<String, dynamic> node) {
    final w = _asString(node['fontWeight'], '').toLowerCase();
    final isTitle = node['role'] == 'title' || w == 'w600' || w == 'w700' || w == 'bold';
    return isTitle ? titleFont : baseFont;
  }

  static String? _resolvedFamily(String f) {
    if (f.isEmpty || f == 'Predeterminada') return null;
    if (GoogleFonts.asMap().containsKey(f)) return GoogleFonts.getFont(f).fontFamily;
    return f;
  }

  /// Parsea un String JSON y lo convierte en el Widget raíz de la UI.
  static Widget parse(String source, {String origin = 'widgets_config.json'}) {
    try {
      final decoded = jsonDecode(source);
      final node = _asMap(decoded);
      if (node == null) {
        throw const FormatException('la raíz del JSON debe ser un objeto');
      }
      return buildNode(node, origin: origin);
    } catch (e) {
      return _ConfigErrorCard(
        title: 'Config no válida',
        message: '$e',
        origin: origin,
      );
    }
  }

  /// Mapea un nodo JSON a un Widget concreto de Flutter.
  static Widget buildNode(Map<String, dynamic> node, {String origin = ''}) {
    final type = _asString(node['type'], 'container');
    switch (type) {
      case 'container':
        return _buildContainer(node, origin);
      case 'text':
        return _buildText(node);
      case 'clock':
        return _buildClock(node);
      case 'tiling_layout':
        return _buildTilingLayout(node, origin);
      case 'spacer':
        return _buildSpacer(node);
      case 'apps_grid':
        return _buildAppsGrid(node);
      case 'battery':
        return _buildBattery(node);
      case 'plugin_widget':
        return _buildPluginWidget(node, origin);
      case 'system_widget':
        return _buildSystemWidget(node);
      case 'box':
        return _buildBox(node);
      default:
        return _ConfigErrorCard(
          title: 'Nodo desconocido',
          message: 'El tipo "$type" no lo soporta el motor de renderizado.',
          origin: origin,
        );
    }
  }

  /// Parsea la config del escritorio: si tiene `desktops`, devuelve un
  /// PageView con un escritorio por página; si no, el nodo único de siempre.
  static Widget parseDesktop(
    String source, {
    String origin = 'widgets_config.json',
    ValueChanged<int>? onPageChanged,
    ValueChanged<int>? onLongPressDesktop,
    int? editingWidget,
    ValueChanged<int>? onWidgetLongPress,
    ValueChanged<int>? onWidgetSelected,
    void Function(int index, int delta)? onWidgetMove,
    void Function(int index, int delta)? onWidgetResize,
    ValueChanged<int>? onWidgetDelete,
    void Function(int from, int to)? onWidgetDrop,
    void Function(int index, int x, int y, int w, int h)? onWidgetGeometry,
  }) {
    try {
      final decoded = jsonDecode(source);
      final root = _asMap(decoded);
      if (root == null) throw const FormatException('la raíz debe ser un objeto');
      final desktops = root['desktops'];
      if (desktops is List && desktops.isNotEmpty) {
        final wallpaper = _asString(root['wallpaper'], '');
        return _DesktopPager(
          desktops: desktops,
          wallpaper: wallpaper.isEmpty ? null : wallpaper,
          onPageChanged: onPageChanged,
          onLongPressDesktop: onLongPressDesktop,
          editingWidget: editingWidget,
          onWidgetLongPress: onWidgetLongPress,
          onWidgetSelected: onWidgetSelected,
          onWidgetMove: onWidgetMove,
          onWidgetResize: onWidgetResize,
          onWidgetDelete: onWidgetDelete,
          onWidgetDrop: onWidgetDrop,
          onWidgetGeometry: onWidgetGeometry,
        );
      }
      return buildNode(root, origin: origin);
    } catch (e) {
      return _ConfigErrorCard(title: 'Config no válida', message: '$e', origin: origin);
    }
  }

  /// Número de escritorios (1 si la config es de escritorio único).
  static int desktopCount(String source) {
    try {
      final root = _asMap(jsonDecode(source));
      final d = root?['desktops'];
      return d is List && d.isNotEmpty ? d.length : 1;
    } catch (_) {
      return 1;
    }
  }

  /// Nombre de un escritorio (por índice) para la UI.
  static String desktopName(String source, int index) {
    try {
      final root = _asMap(jsonDecode(source));
      final d = root?['desktops'];
      if (d is! List || d.isEmpty) return 'Escritorio';
      final item = d[index.clamp(0, d.length - 1)];
      if (item is Map && item['name'] is String) return item['name'] as String;
      return 'Escritorio ${index + 1}';
    } catch (_) {
      return 'Escritorio';
    }
  }

  // ------------------------------------------------------------------ nodos

  static Widget _buildContainer(Map<String, dynamic> node, String origin) {
    final rawChildren = _asList(node['children']);
    final children = <Widget>[];
    for (final c in rawChildren) {
      final map = _asMap(c);
      children.add(
        map != null ? buildNode(map, origin: origin) : Text('$c'),
      );
    }

    Widget? child;
    if (children.length == 1) {
      child = children.first;
    } else if (children.length > 1) {
      child = Column(
        mainAxisSize: MainAxisSize.min,
        mainAxisAlignment: _asMainAxisAlignment(node['mainAxisAlignment'], MainAxisAlignment.center),
        crossAxisAlignment: _asCrossAxisAlignment(node['crossAxisAlignment'], CrossAxisAlignment.center),
        children: children,
      );
    }

    final radius = _asNullableDouble(node['borderRadius']);
    return Container(
      width: _asNullableDouble(node['width']),
      height: _asNullableDouble(node['height']),
      alignment: node.containsKey('alignment') ? _asAlignment(node['alignment']) : null,
      padding: _asEdgeInsets(node['padding'], EdgeInsets.zero),
      decoration: BoxDecoration(
        color: _parseColor(node['color'], Colors.transparent),
        borderRadius: radius != null ? BorderRadius.circular(radius) : null,
      ),
      child: child,
    );
  }

  static Widget _buildText(Map<String, dynamic> node) {
    final value = _asString(node['value'] ?? node['content'], '');
    return Text(
      value,
      maxLines: node.containsKey('maxLines') ? _asInt(node['maxLines'], 1) : null,
      textAlign: _asTextAlign(node['textAlign'], TextAlign.left),
      style: TextStyle(
        fontSize: _asDouble(node['fontSize'], 14),
        color: _parseColor(node['color'], const Color(0xFFE8F1F8)),
        fontWeight: _asFontWeight(node['fontWeight'], FontWeight.w400),
        letterSpacing: _asDouble(node['letterSpacing'], 0),
        height: node.containsKey('lineHeight') ? _asDouble(node['lineHeight'], 1.2) : null,
        fontFamily: _resolvedFamily(_fontFor(node)),
      ),
    );
  }

  static Widget _buildClock(Map<String, dynamic> node) {
    final format = _asString(node['format'], 'HH:mm');
    final style = TextStyle(
      fontSize: _asDouble(node['fontSize'], 14),
      color: _parseColor(node['color'], const Color(0xFFE8F1F8)),
      fontWeight: _asFontWeight(node['fontWeight'], FontWeight.w400),
      letterSpacing: _asDouble(node['letterSpacing'], 0),
      fontFamily: _resolvedFamily(_fontFor(node)),
    );
    switch (_asString(node['style'], 'text')) {
      case 'particles':
        return ParticleClock(
          format: format,
          style: style,
          density: _asDouble(node['density'], 3),
          particleSize: _asDouble(node['particleSize'], 1.6),
          wobble: _asDouble(node['wobble'], 1),
          shake: _asBool(node['shake'], true),
        );
      case 'ticker':
        return TickerClock(
          format: format,
          style: style,
          direction: _asString(node['direction'], 'up'),
          digitWidth: _asDouble(node['digitWidth'], 0),
        );
      default:
        return ClockText(
          format: format,
          style: style,
          textAlign: _asTextAlign(node['textAlign'], TextAlign.left),
        );
    }
  }

  static Widget _buildTilingLayout(Map<String, dynamic> node, String origin) {
    final horizontal = _asString(node['orientation'], 'column').toLowerCase() == 'row';
    final spacing = _asDouble(node['spacing'], 0);

    final tiles = <Widget>[];
    for (final c in _asList(node['children'])) {
      final map = _asMap(c);
      if (map == null) {
        tiles.add(Text('$c'));
        continue;
      }
      final tile = buildNode(map, origin: origin);
      final flex = _asInt(map['flex'], 0);
      tiles.add(flex > 0 ? Expanded(flex: flex, child: tile) : tile);
    }

    final spaced = <Widget>[];
    for (var i = 0; i < tiles.length; i++) {
      if (i > 0) {
        spaced.add(horizontal ? SizedBox(width: spacing) : SizedBox(height: spacing));
      }
      spaced.add(tiles[i]);
    }

    final mainAxis = _asMainAxisAlignment(node['mainAxisAlignment'], MainAxisAlignment.start);
    final crossAxis = _asCrossAxisAlignment(node['crossAxisAlignment'], CrossAxisAlignment.center);

    return horizontal
        ? Row(mainAxisAlignment: mainAxis, crossAxisAlignment: crossAxis, children: spaced)
        : Column(mainAxisAlignment: mainAxis, crossAxisAlignment: crossAxis, children: spaced);
  }

  static Widget _buildSpacer(Map<String, dynamic> node) {
    final horizontal = _asString(node['orientation'], 'column') == 'row';
    final size = _asDouble(node['size'], 16);
    return SizedBox(width: horizontal ? size : 0, height: horizontal ? 0 : size);
  }

  /// Rejilla de aplicaciones instaladas (widget del escritorio).
  static Widget _buildAppsGrid(Map<String, dynamic> node) {
    return GridView.count(
      crossAxisCount: _asInt(node['columns'], 4),
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      padding: _asEdgeInsets(node['padding'], EdgeInsets.zero),
      mainAxisSpacing: 14,
      crossAxisSpacing: 14,
      children: [for (final app in InstalledAppsSnapshot.latest) _DesktopAppTile(app: app)],
    );
  }

  /// Widget de batería (canal nativo).
  static Widget _buildBattery(Map<String, dynamic> node) {
    return BatteryWidget(
      style: TextStyle(
        fontSize: _asDouble(node['fontSize'], 14),
        color: _parseColor(node['color'], const Color(0xFF7EE787)),
        fontWeight: _asFontWeight(node['fontWeight'], FontWeight.w500),
      ),
      showIcon: node['showIcon'] != false,
    );
  }

  /// Widget de un plugin Omarchy instalado (renderizado por el bridge).
  static Widget _buildPluginWidget(Map<String, dynamic> node, String origin) {
    final id = _asString(node['pluginId'], '');
    final kind = _asString(node['kind'], 'bar-widget');
    OhmPlugin? plugin;
    for (final p in PluginSnapshot.latest) {
      if (p.isValid && p.manifest?.id == id) {
        plugin = p;
        break;
      }
    }
    if (plugin == null) {
      return _ConfigErrorCard(
        title: 'Plugin no encontrado',
        message: 'El plugin "$id" no está instalado o no es válido.',
        origin: origin,
      );
    }
    return ClipRect(child: PluginRenderer.renderForKind(plugin, kind));
  }

  /// Placeholder de un AppWidget del sistema. El puente real (AppWidgetHost)
  /// está pendiente; mostramos el nombre y el proveedor por ahora.
  static Widget _buildSystemWidget(Map<String, dynamic> node) {
    final provider = _asString(node['provider'], '');
    final label = _asString(node['label'], 'Widget del sistema');
    return _SystemAppWidget(provider: provider, fallbackLabel: label);
  }

  /// Caja configurable: contiene apps, widgets del sistema o plugins.
  static Widget _buildBox(Map<String, dynamic> node) {
    final items = _asList(node['items']);
    final direction = _asString(node['direction'], 'horizontal');
    final boxIndex = _asInt(node['index'], -1);
    return _BoxWidget(
      items: items,
      direction: direction,
      boxIndex: boxIndex,
      onAddContent: DynamicWidgetEngine.onBoxAddContent,
    );
  }

  /// Expone el parseo de un valor JSON a Map (usado por el escritorio).
  static Map<String, dynamic>? asMapPublic(Object? v) => _asMap(v);

  /// Expone la lectura de un int con fallback (usado por el editor).
  static int asIntPublic(Object? v, int fallback) => _asInt(v, fallback);

  // ------------------------------------------------------- parseo de valores

  static Map<String, dynamic>? _asMap(Object? v) =>
      v is Map<String, dynamic> ? v : (v is Map ? v.cast<String, dynamic>() : null);

  static List<Object?> _asList(Object? v) => v is List ? v : const [];

  static String _asString(Object? v, String fallback) => v is String ? v : fallback;

  static int _asInt(Object? v, int fallback) => v is num ? v.round() : fallback;

  static double _asDouble(Object? v, double fallback) => v is num ? v.toDouble() : fallback;

  static bool _asBool(Object? v, bool fallback) => v is bool ? v : fallback;

  static double? _asNullableDouble(Object? v) => v is num ? v.toDouble() : null;

  /// Convierte "#RGB", "#RRGGBB" o "#AARRGGBB" en un Color.
  static Color _parseColor(Object? raw, Color fallback) {
    if (raw is! String) return fallback;
    var hex = raw.trim();
    if (hex.isEmpty) return fallback;
    if (hex.startsWith('#')) hex = hex.substring(1);
    if (hex.length == 3) hex = hex.split('').map((c) => '$c$c').join();
    if (hex.length == 6) hex = 'FF$hex';
    if (hex.length != 8) return fallback;
    final value = int.tryParse(hex, radix: 16);
    return value == null ? fallback : Color(value);
  }

  /// Parsea un color hexadecimal para uso fuera del motor (config, ajustes).
  static Color colorFromHex(String hex) => _parseColor(hex, Colors.transparent);

  /// Acepta un número, "1 2 3 4", "1 2" o {left, top, right, bottom}.
  static EdgeInsets _asEdgeInsets(Object? raw, EdgeInsets fallback) {
    if (raw is num) return EdgeInsets.all(raw.toDouble());
    if (raw is String) {
      final parts = raw
          .split(RegExp(r'[,\s]+'))
          .where((p) => p.isNotEmpty)
          .map(double.tryParse)
          .toList();
      if (parts.any((p) => p == null)) return fallback;
      final nums = parts.cast<double>();
      if (nums.length == 1) return EdgeInsets.all(nums[0]);
      if (nums.length == 2) return EdgeInsets.symmetric(vertical: nums[0], horizontal: nums[1]);
      if (nums.length == 4) return EdgeInsets.fromLTRB(nums[3], nums[0], nums[1], nums[2]);
      return fallback;
    }
    final map = _asMap(raw);
    if (map != null) {
      return EdgeInsets.fromLTRB(
        _asDouble(map['left'], 0),
        _asDouble(map['top'], 0),
        _asDouble(map['right'], 0),
        _asDouble(map['bottom'], 0),
      );
    }
    return fallback;
  }

  static MainAxisAlignment _asMainAxisAlignment(Object? raw, MainAxisAlignment fallback) {
    switch (_asString(raw, '').toLowerCase()) {
      case 'start':
        return MainAxisAlignment.start;
      case 'center':
        return MainAxisAlignment.center;
      case 'end':
        return MainAxisAlignment.end;
      case 'spacebetween':
        return MainAxisAlignment.spaceBetween;
      case 'spacearound':
        return MainAxisAlignment.spaceAround;
      case 'spaceevenly':
        return MainAxisAlignment.spaceEvenly;
    }
    return fallback;
  }

  static CrossAxisAlignment _asCrossAxisAlignment(Object? raw, CrossAxisAlignment fallback) {
    switch (_asString(raw, '').toLowerCase()) {
      case 'start':
        return CrossAxisAlignment.start;
      case 'center':
        return CrossAxisAlignment.center;
      case 'end':
        return CrossAxisAlignment.end;
      case 'stretch':
        return CrossAxisAlignment.stretch;
      case 'baseline':
        return CrossAxisAlignment.baseline;
    }
    return fallback;
  }

  static TextAlign _asTextAlign(Object? raw, TextAlign fallback) {
    switch (_asString(raw, '').toLowerCase()) {
      case 'left':
        return TextAlign.left;
      case 'center':
        return TextAlign.center;
      case 'right':
        return TextAlign.right;
      case 'justify':
        return TextAlign.justify;
    }
    return fallback;
  }

  static FontWeight _asFontWeight(Object? raw, FontWeight fallback) {
    switch (_asString(raw, '').toLowerCase()) {
      case 'w100':
      case 'thin':
        return FontWeight.w100;
      case 'w200':
        return FontWeight.w200;
      case 'w300':
      case 'light':
        return FontWeight.w300;
      case 'w400':
      case 'normal':
        return FontWeight.w400;
      case 'w500':
      case 'medium':
        return FontWeight.w500;
      case 'w600':
      case 'semibold':
        return FontWeight.w600;
      case 'w700':
      case 'bold':
        return FontWeight.w700;
      case 'w800':
        return FontWeight.w800;
      case 'w900':
        return FontWeight.w900;
    }
    return fallback;
  }

  static Alignment? _asAlignment(Object? raw) {
    switch (_asString(raw, '').toLowerCase()) {
      case 'topleft':
        return Alignment.topLeft;
      case 'topcenter':
        return Alignment.topCenter;
      case 'topright':
        return Alignment.topRight;
      case 'centerleft':
        return Alignment.centerLeft;
      case 'center':
        return Alignment.center;
      case 'centerright':
        return Alignment.centerRight;
      case 'bottomleft':
        return Alignment.bottomLeft;
      case 'bottomcenter':
        return Alignment.bottomCenter;
      case 'bottomright':
        return Alignment.bottomRight;
    }
    return null;
  }
}
