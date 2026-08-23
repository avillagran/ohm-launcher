// ============================================================================
//  QML BRIDGE — intérprete de UI
//  ============================================================================
//  Convierte el AST QML en Widgets de Flutter. Mapea los componentes de
//  QtQuick/Quickshell que usan los plugins de Omarchy:
//
//    Text, Row, Column, Rectangle, Item, Image, SystemClock, Loader,
//    BarWidget, Panel, KeyboardPanel, PanelKeyCatcher, Popup, WidgetButton,
//    MouseArea.
//
//  Principio: cualquier tipo o propiedad no soportada se degrada a un valor
//  seguro (nunca lanza). Un QML no parseable devuelve una tarjeta de error
//  explicativa, jamás un crash.
// ============================================================================

import 'dart:io';

import 'package:flutter/material.dart';

import '../clock_widget.dart';
import 'qml_parser.dart';
import 'qml_runtime.dart';

/// Resultado de interpretar un entry point QML.
class QmlInterpretResult {
  const QmlInterpretResult({required this.widget, this.error});

  final Widget widget;

  /// null si se interpretó sin problemas; si no, texto del error degradado.
  final String? error;
}

/// Entry point: interprets [source] and returns the root Widget.
class QmlInterpreter {
  QmlInterpreter._();

  static QmlInterpretResult interpret({
    required String source,
    required String originDir,
    String originFile = 'entry.qml',
    bool compact = false,
  }) {
    final doc = QmlParser(source).parse();
    if (doc.error != null || doc.elements.isEmpty) {
      return QmlInterpretResult(
        error: doc.error ?? 'Sin elementos renderizables.',
        widget: QmlErrorCard(
          title: 'QML no interpretable',
          message: doc.error ?? 'El archivo no contiene elementos QML.',
          origin: originFile,
        ),
      );
    }
    try {
      final root = doc.elements.first;
      final scope = QmlScope();

      // Pass 1: register ids and the root element to resolve properties
      // (root.icon, button.text, clock.date...).
      scope.values['root'] = QmlElementValue(root, scope);
      void register(QmlElement el) {
        if (el.id != null) {
          scope.values[el.id!] = el.baseType == 'SystemClock'
              ? QmlClockValue.now()
              : QmlElementValue(el, scope);
        }
        for (final c in el.children) {
          register(c);
        }
      }

      register(root);

      final ctx = QmlBuildContext(scope: scope, originDir: originDir, compact: compact);
      Widget widget = _Builder(ctx).build(root);
      final fillExpr = root.properties['anchors.fill'];
      if (fillExpr != null && qmlEval(fillExpr, scope) == true) {
        widget = SizedBox.expand(child: widget);
      }
      return QmlInterpretResult(widget: widget);
    } catch (e) {
      return QmlInterpretResult(
        error: 'Error interpretando QML: $e',
        widget: QmlErrorCard(
          title: 'QML parcial',
          message: '$e',
          origin: originFile,
        ),
      );
    }
  }
}

/// Contexto compartido durante el build.
class QmlBuildContext {
  QmlBuildContext({
    required this.scope,
    required this.originDir,
    this.depth = 0,
    this.compact = false,
  });

  final QmlScope scope;
  final String originDir;
  final int depth;

  /// Modo compacto (dock de bar-widgets): muestra solo el botón, oculta los
  /// popups/paneles y evita que el contenido completo se encoja hasta
  /// desaparecer dentro del tile de 52px.
  final bool compact;
}

// ---------------------------------------------------------------------------
//  Constructor de widgets
// ---------------------------------------------------------------------------

class _Builder {
  _Builder(this.ctx);

  final QmlBuildContext ctx;

  static const Set<String> _ignored = {
    'Behavior', 'Timer', 'Connections', 'Transition', 'PropertyAnimation',
    'SequentialAnimation', 'ParallelAnimation', 'AnchorChanges',
    'SpringAnimation', 'NumberAnimation', 'ColorAnimation', 'OpacityAnimator',
    'RotationAnimator', 'ScaleAnimator', 'State', 'States', 'PropertyChanges',
    'Binding', 'Shortcut', 'Keys', 'FocusScope', 'ItemDelegate',
    'ListModel', 'WheelHandler', 'Component', 'Layout', 'Flow', 'Grid',
    'Positioner',
    // Plumbing de Quickshell (servicios/IPC): no aportan UI en Android.
    'Process', 'IpcHandler', 'FileView', 'PanelWindow', 'OmarchyMenuScan',
    'CursorSurface', 'PointerMoveGate', 'ScreenMoveRemap',
  };

  Widget build(QmlElement el) {
    switch (el.baseType) {
      case 'Text':
        return _buildText(el);
      case 'Row':
        return _buildFlex(el, horizontal: true);
      case 'Column':
      case 'Flickable':
      case 'ScrollView':
        return _buildFlex(el, horizontal: false);
      case 'Rectangle':
        return _buildRectangle(el);
      case 'Item':
        return _buildItem(el);
      case 'BarWidget':
      case 'Panel':
      case 'KeyboardPanel':
      case 'PanelKeyCatcher':
        return _buildSurface(el);
      case 'Popup':
      case 'PopupCard':
        return _buildPopup(el);
      case 'WidgetButton':
      case 'MouseArea':
      case 'BarIconButton':
        return _buildButton(el);
      case 'SystemClock':
        return const SizedBox.shrink();
      case 'Loader':
        return _buildLoader(el);
      case 'Image':
        return _buildImage(el);
      // ---- qs.Ui: superficie de componentes usados por los plugins ----
      case 'Repeater':
      case 'ListView':
      case 'GridView':
        return _buildRepeater(el);
      case 'PanelActionButton':
      case 'PanelHero':
      case 'Button':
      case 'PanelToolTip':
        return _buildActionButton(el);
      case 'TextField':
      case 'NumberField':
        return _buildInput(el);
      case 'PanelSectionHeader':
        return _buildSectionHeader(el);
      case 'PanelSeparator':
        return _buildSeparator(el);
      case 'BorderSurface':
        return _buildBorderSurface(el);
      case 'PanelSlider':
        return _buildSlider(el);
      case 'Toggle':
      case 'ToggleSwitch':
        return _buildToggle(el);
      case 'BarIndicator':
      case 'OpticalGlyph':
        return _buildIndicator(el);
      case 'Dropdown':
      case 'SearchableDropdown':
      case 'MultiSelect':
        return _buildDropdown(el);
      default:
        if (_ignored.contains(el.baseType)) return const SizedBox.shrink();
        return _buildCustomOrHidden(el);
    }
  }

  // ------------------------------------------------------------ textos

  Widget _buildText(QmlElement el) {
    final style = TextStyle(
      fontSize: _numProp(el, const ['font', 'pixelSize'], 14) ?? 14,
      color: _colorProp(el, const ['color']) ?? const Color(0xFFE8F1F8),
      fontWeight: _boolProp(el, const ['font', 'bold'], false)
          ? FontWeight.w700
          : FontWeight.w400,
      letterSpacing: _numProp(el, const ['font', 'letterSpacing'], 0) ?? 0,
    );

    final valueExpr = el.properties['text'];
    if (valueExpr is QmlCall) {
      final name = valueExpr.name;
      if (name == 'Qt.formatTime' || name == 'Qt.formatDateTime' || name == 'Qt.formatDate') {
        final fmt = valueExpr.args.length > 1 && valueExpr.args.last is QmlLiteral
            ? (valueExpr.args.last as QmlLiteral).value as String?
            : null;
        if (fmt != null && _referencesClockDate(valueExpr.args.first)) {
          return ClockText(format: fmt, style: style, textAlign: _textAlign(el));
        }
      }
    }

    final raw = qmlEval(valueExpr ?? const QmlLiteral(''), ctx.scope);
    final text = raw == null ? '' : '$raw';
    return _glyphOrText(text, style, textAlign: _textAlign(el));
  }

  bool _referencesClockDate(QmlValueExpr expr) =>
      expr is QmlRef && expr.path.length == 2 && expr.path.last == 'date';

  TextAlign _textAlign(QmlElement el) {
    final h = _strProp(el, 'horizontalAlignment') ?? '';
    if (h.contains('AlignHCenter') || h.contains('center')) return TextAlign.center;
    if (h.contains('AlignRight')) return TextAlign.right;
    return TextAlign.left;
  }

  // ------------------------------------------------------------ layout

  Widget _buildFlex(QmlElement el, {required bool horizontal}) {
    final spacing = _numProp(el, const ['spacing'], 0) ?? 0;
    final children = el.children.map(build).toList();

    final spaced = <Widget>[];
    for (var i = 0; i < children.length; i++) {
      if (i > 0) {
        spaced.add(horizontal ? SizedBox(width: spacing) : SizedBox(height: spacing));
      }
      spaced.add(children[i]);
    }

    return horizontal
        ? Row(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.center, children: spaced)
        : Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: spaced,
          );
  }

  Widget _buildRectangle(QmlElement el) {
    final radius = _numProp(el, const ['radius'], 0) ??
        _numProp(el, const ['border', 'radius'], 0) ??
        0;
    final borderColor = _colorProp(el, const ['border', 'color']);
    final borderWidth = _numProp(el, const ['border', 'width'], 1) ?? 1;

    return Container(
      width: _sizeProp(el, 'implicitWidth') ?? _sizeProp(el, 'width'),
      height: _sizeProp(el, 'implicitHeight') ?? _sizeProp(el, 'height'),
      padding: _padProp(el),
      decoration: BoxDecoration(
        color: _colorProp(el, const ['color']) ?? Colors.transparent,
        borderRadius: radius > 0 ? BorderRadius.circular(radius) : null,
        border: borderColor != null
            ? Border.all(color: borderColor, width: borderWidth)
            : null,
      ),
      child: _buildChildrenBox(el),
    );
  }

  Widget _buildItem(QmlElement el) {
    final w = _sizeProp(el, 'implicitWidth') ?? _sizeProp(el, 'width');
    final h = _sizeProp(el, 'implicitHeight') ?? _sizeProp(el, 'height');
    final child = _buildChildrenBox(el);
    if (w == null && h == null) return child ?? const SizedBox.shrink();
    return SizedBox(width: w, height: h, child: child);
  }

  // ------------------------------------------------------------ superficies

  Widget _buildSurface(QmlElement el) {
    final children = el.children.map(build).toList();
    if (children.isEmpty) return const SizedBox.shrink();

    // In compact mode (dock), a bar-widget shows only its button, whether the
    // root is a BarWidget or a Panel (convention used by many plugins).
    if (ctx.compact && (el.baseType == 'BarWidget' || el.baseType == 'Panel')) {
      final buttons = el.children
          .where((c) => _isButtonType(c.baseType))
          .toList();
      if (buttons.isNotEmpty) return build(buttons.first);
      return children.last;
    }

    if (children.length == 1) return children.first;
    return Row(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.center,
      children: children,
    );
  }

  bool _isButtonType(String base) =>
      base == 'WidgetButton' || base == 'BarIconButton' || base == 'MouseArea';

  // ------------------------------------------------------------ interactivos

  Widget _buildButton(QmlElement el) {
    Widget inner;
    final valueExpr = el.properties['text'];
    if (valueExpr != null) {
      if (valueExpr is QmlCall &&
          (valueExpr.name == 'Qt.formatTime' ||
              valueExpr.name == 'Qt.formatDateTime' ||
              valueExpr.name == 'Qt.formatDate')) {
        final fmt = valueExpr.args.length > 1 && valueExpr.args.last is QmlLiteral
            ? (valueExpr.args.last as QmlLiteral).value as String?
            : null;
        if (fmt != null && _referencesClockDate(valueExpr.args.first)) {
          inner = ClockText(
            format: fmt,
            style: const TextStyle(fontSize: 14, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600),
          );
        } else {
          inner = const Text('');
        }
      } else {
        final raw = qmlEval(valueExpr, ctx.scope);
        inner = _glyphOrText(
          raw == null ? '' : '$raw',
          const TextStyle(fontSize: 15, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600),
        );
      }
    } else {
      final children = el.children.map(build).toList();
      inner = children.length == 1 ? children.first : _buildChildrenBox(el) ?? const SizedBox.shrink();
    }

    // Sin InkWell: el tile del dock (o el panel) gestiona el tap. Un InkWell
    // interno con onTap vacío "se traga" el clic en el icono e impide que
    // llegue al contenedor exterior.
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: inner,
    );
  }

  // ------------------------------------------------------------ específicos

  /// Popup/overlay: only shown when `open` is true (in the dock it
  /// oculta; el panel se abre con un clic en el tile).
  Widget _buildPopup(QmlElement el) {
    if (ctx.compact) return const SizedBox.shrink();
    final open = _boolProp(el, const ['open'], false);
    if (!open) return const SizedBox.shrink();
    return _buildSurface(el);
  }

  Widget _buildLoader(QmlElement el) {
    final visible = _boolProp(el, const ['visible'], true);
    final active = _boolProp(el, const ['active'], true);
    if (!visible || !active || ctx.depth > 4) return const SizedBox.shrink();

    final src = _strProp(el, 'source');
    if (src == null || src.isEmpty) return const SizedBox.shrink();

    final path = qmlUrlToPath(src);
    final file = File(path.contains('/') ? path : '${ctx.originDir}/$path');
    if (!file.existsSync()) return const SizedBox.shrink();
    if (!file.path.endsWith('.qml')) return const SizedBox.shrink();

    final nested = QmlInterpreter.interpret(compact: ctx.compact,
      source: file.readAsStringSync(),
      originDir: file.parent.path,
      originFile: file.path.split('/').last,
    );
    return nested.widget;
  }

  Widget _buildImage(QmlElement el) {
    final w = _sizeProp(el, 'implicitWidth') ?? _sizeProp(el, 'width');
    final h = _sizeProp(el, 'implicitHeight') ?? _sizeProp(el, 'height');
    return Container(
      width: w,
      height: h,
      alignment: Alignment.center,
      child: Icon(Icons.image_outlined, size: 24, color: const Color(0xFF5A6B7A)),
    );
  }

  /// Tipos personalizados o componentes internos de Quickshell:
  ///  - Si existe `Tipo.qml` junto al plugin, se interpreta (resolución de
  ///    tipos del mismo repositorio).
  ///  - Si no, el componente es plumbing interno del shell (FileView,
  ///    IpcHandler...) y se oculta sin romper el layout.
  Widget _buildCustomOrHidden(QmlElement el) {
    if (ctx.depth > 4) return const SizedBox.shrink();
    final file = File('${ctx.originDir}/${el.baseType}.qml');
    if (file.existsSync()) {
      return QmlInterpreter.interpret(compact: ctx.compact,
        source: file.readAsStringSync(),
        originDir: file.parent.path,
        originFile: file.path.split('/').last,
      ).widget;
    }
    return const SizedBox.shrink();
  }

  // ------------------------------------------------------------ qs.Ui

  /// Repetidor/ListView: no se puede evaluar el modelo (JS), pero se dibuja
  /// el delegate como plantilla (hasta 8 copias) para dar feedback visual.
  Widget _buildRepeater(QmlElement el) {
    final delegate = el.properties['delegate'];
    if (delegate is! QmlInlineElement) {
      return _buildChildrenBox(el) ?? const SizedBox.shrink();
    }
    final model = _evalProp(el, const ['model']);
    var count = 1;
    if (model is num) {
      count = model.toInt().clamp(0, 8);
    } else if (model is List) {
      count = model.length.clamp(0, 8);
    }
    if (count == 0) return const SizedBox.shrink();
    final nested = _Builder(QmlBuildContext(scope: ctx.scope, originDir: ctx.originDir, depth: ctx.depth + 1, compact: ctx.compact));
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.start,
      children: List.generate(count, (_) => nested.build(delegate.element)),
    );
  }

  /// Botón de acción de panel: fila con icono y texto, tappable.
  Widget _buildActionButton(QmlElement el) {
    final label = _strProp(el, 'text') ?? _strProp(el, 'label') ?? '';
    final iconName = _strProp(el, 'icon') ?? _strProp(el, 'iconSource');
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: () {},
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.play_arrow_rounded, size: 18, color: _colorProp(el, const ['color']) ?? const Color(0xFF66E0FF)),
            if (iconName != null) const SizedBox(width: 4),
            const SizedBox(width: 8),
            Flexible(
              child: Text(
                label.isEmpty ? 'Acción' : label,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(fontSize: 13, color: Color(0xFFE8F1F8)),
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Campo de texto estático que muestra el placeholder (los campos de QML
  /// no capturan teclado en el bridge).
  Widget _buildInput(QmlElement el) {
    final placeholder = _strProp(el, 'placeholderText') ?? _strProp(el, 'placeholder') ?? '';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: const Color(0xFF16202A),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: const Color(0x2BFFFFFF)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.search, size: 16, color: Color(0xFF5A6B7A)),
          const SizedBox(width: 8),
          Flexible(
            child: Text(
              placeholder.isEmpty ? 'Buscar…' : placeholder,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontSize: 12, color: Color(0xFF5A6B7A)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildSectionHeader(QmlElement el) {
    final text = _strProp(el, 'text') ?? _strProp(el, 'label') ?? 'Sección';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Text(
        text.toUpperCase(),
        style: const TextStyle(fontSize: 10, color: Color(0xFF5A6B7A), letterSpacing: 4, fontWeight: FontWeight.w600),
      ),
    );
  }

  Widget _buildSeparator(QmlElement el) {
    return const Divider(height: 12, color: Color(0x1AFFFFFF));
  }

  Widget _buildBorderSurface(QmlElement el) {
    final radius = _numProp(el, const ['radius'], 12) ?? 12;
    return Container(
      width: _sizeProp(el, 'implicitWidth') ?? _sizeProp(el, 'width'),
      height: _sizeProp(el, 'implicitHeight') ?? _sizeProp(el, 'height'),
      padding: _padProp(el),
      decoration: BoxDecoration(
        color: _colorProp(el, const ['color']) ?? const Color(0xFF10161C),
        borderRadius: BorderRadius.circular(radius),
        border: Border.all(color: const Color(0x2BFFFFFF)),
      ),
      child: _buildChildrenBox(el),
    );
  }

  Widget _buildSlider(QmlElement el) {
    final value = _numProp(el, const ['value'], 0.5) ?? 0.5;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Expanded(
            child: ClipRRect(
              borderRadius: BorderRadius.circular(4),
              child: LinearProgressIndicator(
                value: value.clamp(0, 1),
                minHeight: 6,
                backgroundColor: const Color(0xFF16202A),
                valueColor: const AlwaysStoppedAnimation<Color>(Color(0xFF66E0FF)),
              ),
            ),
          ),
          const SizedBox(width: 8),
          Text('${(value * 100).round()}%', style: const TextStyle(fontSize: 10, color: Color(0xFF7A8A99))),
        ],
      ),
    );
  }

  Widget _buildToggle(QmlElement el) {
    final checked = _boolProp(el, const ['checked'], false);
    final label = _strProp(el, 'text') ?? _strProp(el, 'label') ?? '';
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Switch(value: checked, onChanged: (_) {}, materialTapTargetSize: MaterialTapTargetSize.shrinkWrap),
          const SizedBox(width: 8),
          if (label.isNotEmpty)
            Flexible(child: Text(label, style: const TextStyle(fontSize: 12, color: Color(0xFFE8F1F8)))),
        ],
      ),
    );
  }

  Widget _buildIndicator(QmlElement el) {
    return Container(
      width: _sizeProp(el, 'width') ?? 10,
      height: _sizeProp(el, 'height') ?? 10,
      decoration: BoxDecoration(
        color: _colorProp(el, const ['color']) ?? const Color(0xFF66E0FF),
        shape: BoxShape.circle,
      ),
    );
  }

  Widget _buildDropdown(QmlElement el) {
    final text = _strProp(el, 'text') ?? _strProp(el, 'placeholderText') ?? 'Seleccionar…';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: const Color(0xFF16202A),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: const Color(0x2BFFFFFF)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(child: Text(text, maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12, color: Color(0xFFE8F1F8)))),
          const SizedBox(width: 8),
          const Icon(Icons.expand_more, size: 16, color: Color(0xFF5A6B7A)),
        ],
      ),
    );
  }

  // ------------------------------------------------------------ helpers

  Widget? _buildChildrenBox(QmlElement el) {
    final children = el.children.map(build).toList();
    if (children.isEmpty) return null;
    if (children.length == 1) return children.first;
    return Column(mainAxisSize: MainAxisSize.min, children: children);
  }

  Object? _evalProp(QmlElement el, List<String> path) {
    final expr = el.properties[path.join('.')];
    if (expr == null) return null;
    try {
      return qmlEval(expr, ctx.scope);
    } catch (_) {
      return null;
    }
  }

  String? _strProp(QmlElement el, String key) {
    final v = _evalProp(el, [key]);
    return v == null ? null : '$v';
  }

  double? _numProp(QmlElement el, List<String> path, double fallback) {
    final v = _evalProp(el, path);
    if (v is num) return v.toDouble();
    if (v is String) return double.tryParse(v);
    return fallback;
  }

  double? _sizeProp(QmlElement el, String key) {
    final v = _numProp(el, [key], -1);
    if (v == null || v < 0) return null;
    return v;
  }

  bool _boolProp(QmlElement el, List<String> path, bool fallback) {
    final v = _evalProp(el, path);
    if (v is bool) return v;
    if (v is num) return v != 0;
    if (v is String) return v == 'true';
    return fallback;
  }

  EdgeInsets? _padProp(QmlElement el) {
    final top = _numProp(el, const ['padding', 'top'], 0);
    final left = _numProp(el, const ['padding', 'left'], 0);
    final right = _numProp(el, const ['padding', 'right'], 0);
    final bottom = _numProp(el, const ['padding', 'bottom'], 0);
    final all = _numProp(el, const ['padding'], 0);
    if (top != 0 || left != 0 || right != 0 || bottom != 0) {
      return EdgeInsets.fromLTRB(left ?? 0, top ?? 0, right ?? 0, bottom ?? 0);
    }
    if ((all ?? 0) != 0) return EdgeInsets.all(all!);
    return null;
  }

  Color? _colorProp(QmlElement el, List<String> path) => qmlToColor(_evalProp(el, path));
}

/// Renderiza texto, mapeando glifos Nerd Font (PUA 0xF000-0xF8FF) a iconos
/// Material reales, ya que la fuente no existe en Android.
Widget _glyphOrText(String text, TextStyle style, {TextAlign textAlign = TextAlign.left}) {
  if (text.runes.length == 1) {
    final cp = text.runes.first;
    if (cp >= 0xF000 && cp <= 0xF8FF) {
      final icon = Icon(_iconForGlyph(cp), size: (style.fontSize ?? 14) + 6, color: style.color);
      if (textAlign == TextAlign.center) return Center(child: icon);
      return icon;
    }
  }
  return Text(text, style: style, textAlign: textAlign);
}

IconData _iconForGlyph(int cp) {
  switch (cp) {
    case 0xF073:
      return Icons.calendar_month;
    case 0xF030:
      return Icons.photo_camera_outlined;
    case 0xF2D0:
      return Icons.desktop_windows_outlined;
    case 0xF2D2:
      return Icons.web_asset_outlined;
    case 0xF0C8:
      return Icons.crop_square;
    case 0xEFCC:
    case 0xFECC:
      return Icons.palette_outlined;
    case 0xF2C9:
      return Icons.battery_full;
    case 0xF2CE:
      return Icons.wifi;
    case 0xF0EB:
      return Icons.bolt;
    case 0xF002:
      return Icons.search;
    case 0xF013:
      return Icons.settings;
    case 0xF001:
      return Icons.music_note;
    case 0xF008:
      return Icons.play_arrow;
    case 0xF108:
      return Icons.folder_open;
    case 0xF0A0:
      return Icons.mail_outline;
    default:
      return Icons.widgets_outlined;
  }
}

/// Convierte el valor evaluado de una propiedad color a un Color Flutter.
Color? qmlToColor(Object? v) {
  if (v is String) {
    final s = v.trim();
    final named = _namedColors[s.toLowerCase()];
    if (named != null) return named;
    if (s.startsWith('#')) return _parseHex(s);
  }
  return null;
}

Color _parseHex(String hex) {
  var h = hex.substring(1);
  if (h.length == 3) h = h.split('').map((c) => '$c$c').join();
  if (h.length == 6) h = 'FF$h';
  if (h.length != 8) return const Color(0xFFE8F1F8);
  final value = int.tryParse(h, radix: 16);
  return value == null ? const Color(0xFFE8F1F8) : Color(value);
}

const Map<String, Color> _namedColors = {
  'black': Color(0xFF000000),
  'white': Color(0xFFFFFFFF),
  'red': Color(0xFFF44336),
  'green': Color(0xFF4CAF50),
  'blue': Color(0xFF2196F3),
  'yellow': Color(0xFFFFEB3B),
  'orange': Color(0xFFFF9800),
  'purple': Color(0xFF9C27B0),
  'gray': Color(0xFF9E9E9E),
  'grey': Color(0xFF9E9E9E),
  'transparent': Color(0x00000000),
};

/// Tarjeta de error compacta para QML no interpretable (nunca crashea).
class QmlErrorCard extends StatelessWidget {
  const QmlErrorCard({super.key, required this.title, required this.message, this.origin = ''});

  final String title;
  final String message;
  final String origin;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        margin: const EdgeInsets.all(20),
        padding: const EdgeInsets.all(18),
        decoration: BoxDecoration(
          color: const Color(0xFF1A1F26),
          borderRadius: BorderRadius.circular(16),
          border: Border.all(color: const Color(0xFF3A4654)),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.code, color: Color(0xFF66E0FF), size: 26),
            const SizedBox(height: 10),
            Text(title,
                style: const TextStyle(fontSize: 14, color: Color(0xFFE8F1F8), fontWeight: FontWeight.w600)),
            if (origin.isNotEmpty)
              Padding(
                padding: const EdgeInsets.only(top: 2),
                child: Text(origin, style: const TextStyle(fontSize: 11, color: Color(0xFF7A8A99))),
              ),
            const SizedBox(height: 8),
            SelectableText(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 11, color: Color(0xFF9AA7B4)),
            ),
          ],
        ),
      ),
    );
  }
}