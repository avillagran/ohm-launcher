// ============================================================================
//  CLOCK STYLES — estilos especiales del reloj (arena/partículas y ticker)
//  ============================================================================
//  Widgets autónomos que reutilizan formatClock() de clock_widget.dart.
//  Se seleccionan con el campo "style" del nodo "clock" en widgets_config.json:
//
//    { "type": "clock", "style": "particles", "format": "HH:mm:ss", ... }
//    { "type": "clock", "style": "ticker",    "format": "HH:mm:ss", ... }
//
//  Parámetros extra del nodo:
//    * particles: density (paso de muestreo 1.5–8; menor = más granos),
//      particleSize (radio base del grano), wobble (deriva al viajar 0–3).
//    * ticker: direction ("up" | "down"), digitWidth (ancho fijo por dígito;
//      0 = automático según el ancho del glifo "0").
// ============================================================================

import 'dart:async';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:sensors_plus/sensors_plus.dart';

import 'clock_widget.dart';

// ---------------------------------------------------------------------------
//  Reloj de arena / partículas (estilo "Arrival")
// ---------------------------------------------------------------------------
//  Rasteriza el texto de la hora CARÁCTER A CARÁCTER: cada carácter tiene su
//  propio grupo de granos, de tamaño fijo, muestreado de su glifo. Al cambiar
//  la hora solo se reorganizan los grupos de los caracteres distintos: nunca
//  se crean ni destruyen granos (salvo el calentamiento inicial); los que
//  sobran se apilan sobre los mismos puntos, dando más densidad de tinta.
// ---------------------------------------------------------------------------

class ParticleClock extends StatefulWidget {
  const ParticleClock({
    super.key,
    required this.format,
    required this.style,
    this.density = 3,
    this.particleSize = 1.6,
    this.wobble = 1,
    this.shake = true,
  });

  final String format;
  final TextStyle style;

  /// Paso de muestreo en píxeles lógicos (menor = más denso).
  final double density;

  /// Radio base de cada grano en píxeles lógicos.
  final double particleSize;

  /// Intensidad de la deriva orgánica mientras los granos viajan (0 = los
  /// granos van en línea recta a su destino). En reposo no hay movimiento.
  final double wobble;

  /// If true, "shaking" the phone 3 times in a row (detected via the
  /// accelerometer while the widget is visible) scatters the sand across the
  /// screen and it reforms on its own. The sensor is ONLY registered while
  /// this clock is mounted and visible: no extra battery cost when the clock
  /// el reloj no se ve.
  final bool shake;

  @override
  State<ParticleClock> createState() => _ParticleClockState();
}

class _Grain {
  Offset pos = Offset.zero;
  Offset target = Offset.zero;
  Offset jitter = Offset.zero; // desplazamiento fijo por grano (estabilidad)
  double radius = 1.4;
  double opacity = 1;
  double phase = 0;
  double speed = 1;
}

/// Grupo de granos asociado a un carácter del texto.
class _CharPool {
  String ch = '';
  final List<_Grain> grains = [];
}

/// Muestras de píxeles (coordenadas lógicas) del glifo de un carácter.
typedef _CharSample = ({String ch, List<Offset> points, double x});

class _ParticleClockState extends State<ParticleClock>
    with SingleTickerProviderStateMixin {
  /// Tope de granos por carácter (rendimiento).
  static const int _maxGrainsPerChar = 200;

  /// Límite de ampliación del texto al encajar en el área (más allá los
  /// granos quedarían demasiado dispersos).
  static const double _maxUpscale = 3;

  final List<_CharPool> _pools = [];
  final math.Random _rng = math.Random();
  final Stopwatch _elapsed = Stopwatch()..start();

  late final AnimationController _frames;
  Timer? _secondTimer;
  StreamSubscription<AccelerometerEvent>? _accelSub;
  Size _area = Size.zero;
  String _lastText = '';
  bool _forceAll = false; // true tras redimensionar: remuestrea todo
  bool _sampling = false;

  // Detección de batido (3 sacudidas): histéresis para que cada sacudida
  // física cuente una sola vez, y ventana para encadenarlas.
  static const int _shakeThreshold = 3;
  static const double _shakeAccel = 14; // m/s²: pico de una sacudida
  static const double _shakeRelease = 10; // m/s²: rearma el detector al bajar
  static const Duration _shakeWindow = Duration(milliseconds: 2000);
  final List<DateTime> _shakes = [];
  bool _shakeArmed = true;

  /// true while the grains are scattered after shaking the phone.
  bool _scattered = false;

  Color get _color => widget.style.color ?? const Color(0xFFE8F1F8);

  bool get _hasGrains => _pools.any((p) => p.grains.isNotEmpty);

  Iterable<_Grain> get _allGrains sync* {
    for (final p in _pools) {
      yield* p.grains;
    }
  }

  @override
  void initState() {
    super.initState();
    _frames = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 1),
    )
      ..addListener(_advance)
      ..repeat();
    _secondTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      _resample();
    });
    WidgetsBinding.instance.addPostFrameCallback((_) => _resample());
    _watchShake();
  }

  /// Registers the accelerometer ONLY if [widget.shake] is enabled. The sensor
  /// is unregistered in [dispose]: no battery cost when the clock is not
  /// mounted or the feature is not wanted.
  void _watchShake() {
    if (!widget.shake) return;
    _accelSub?.cancel();
    _accelSub = accelerometerEventStream(
      samplingPeriod: SensorInterval.uiInterval,
    ).listen(_onAccel, onError: (_) {});
  }

  void _onAccel(AccelerometerEvent e) {
    final mag = math.sqrt(e.x * e.x + e.y * e.y + e.z * e.z);
    if (_shakeArmed) {
      if (mag > _shakeAccel) {
        _shakeArmed = false; // consume la sacudida: hay que volver a bajar
        _registerShake();
      }
    } else if (mag < _shakeRelease) {
      _shakeArmed = true; // lista para contar la siguiente sacudida
    }
  }

  void _registerShake() {
    final now = DateTime.now();
    _shakes.removeWhere((t) => now.difference(t) > _shakeWindow);
    _shakes.add(now);
    if (_shakes.length >= _shakeThreshold) {
      _shakes.clear();
      _scatter();
    }
  }

  /// Desparrama la arena por el área (mantiene los granos, solo los suelta).
  void _scatter() {
    if (!mounted || _scattered) return;
    _scattered = true;
    // Haptic feedback on "shake" (idea borrowed from LeafyLauncher): a small
    // haptic tap confirms the gesture at no battery cost (native HapticFeedback).
    HapticFeedback.mediumImpact();
    for (final g in _allGrains) {
      g.target = Offset(
        _rng.nextDouble() * _area.width,
        _rng.nextDouble() * _area.height,
      );
    }
    setState(() {});
    Timer(const Duration(milliseconds: 1400), () {
      if (!mounted) return;
      _scattered = false;
      _lastText = '';
      _forceAll = true;
      _resample();
    });
  }

  @override
  void didUpdateWidget(covariant ParticleClock oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.shake != widget.shake) {
      _watchShake();
    }
    if (oldWidget.format != widget.format ||
        oldWidget.style != widget.style ||
        oldWidget.density != widget.density ||
        oldWidget.particleSize != widget.particleSize) {
      _pools.clear();
      _lastText = '';
      _area = Size.zero; // fuerza remuestreo en el próximo build
      setState(() {});
    }
  }

  @override
  void dispose() {
    _accelSub?.cancel();
    _secondTimer?.cancel();
    _frames.dispose();
    super.dispose();
  }

  /// Movimiento por frame: interpolación hacia el destino + deriva orgánica.
  /// La deriva es proporcional a la distancia al destino: un grano asentado
  /// (su dígito no cambió) queda inmóvil; solo "hierven" los que viajan.
  void _advance() {
    final t = _elapsed.elapsedMilliseconds / 1000.0;
    for (final g in _allGrains) {
      final dx = g.target.dx - g.pos.dx;
      final dy = g.target.dy - g.pos.dy;
      final dist = math.sqrt(dx * dx + dy * dy);
      final amp = widget.wobble * (dist / 15).clamp(0.0, 1.0);
      g.pos = Offset(
        g.pos.dx + dx * 0.14 + math.sin(t * g.speed + g.phase) * 0.25 * amp,
        g.pos.dy + dy * 0.14 + math.cos(t * g.speed * 0.9 + g.phase) * 0.2 * amp,
      );
    }
  }

  /// Rasteriza el texto actual carácter a carácter y reasigna los granos
  /// SOLO de los caracteres que cambiaron (o de todos tras redimensionar).
  Future<void> _resample() async {
    if (_sampling || _area == Size.zero || !mounted) return;
    final text = formatClock(DateTime.now(), widget.format);
    if (text == _lastText && _hasGrains && !_forceAll) return;
    _sampling = true;
    try {
      final samples = await _sampleChars(text, _area);
      if (!mounted || samples.isEmpty) return;
      _lastText = text;
      _assign(samples, forceAll: _forceAll);
      _forceAll = false;
      setState(() {}); // cambia del texto de respaldo al lienzo de granos
    } finally {
      _sampling = false;
    }
  }

  /// Dibuja cada carácter escalado para encajar en [area] y devuelve las
  /// coordenadas lógicas de sus píxeles opacos. La altura de línea es igual
  /// para todos los caracteres (métricas de la fuente), así la línea de base
  /// queda alineada sin kernings entre glifos.
  Future<List<_CharSample>> _sampleChars(String text, Size area) async {
    // Cifras tabulares: todos los dígitos miden lo mismo y no "saltan".
    final style = widget.style.copyWith(
      fontFeatures: const [ui.FontFeature.tabularFigures()],
    );
    final painters = <TextPainter>[];
    var total = 0.0;
    var height = 0.0;
    for (var i = 0; i < text.length; i++) {
      final tp = TextPainter(
        text: TextSpan(text: text[i], style: style),
        textDirection: TextDirection.ltr,
      )..layout();
      painters.add(tp);
      total += tp.width;
      height = math.max(height, tp.height);
    }
    if (total <= 0 || height <= 0 || area.width <= 0 || area.height <= 0) {
      return const [];
    }

    // Encaje del texto en el área (reduce si no cabe, amplía con límite).
    final fit = math.min(
      math.min(area.width / total, area.height / height),
      _maxUpscale,
    );
    const detail = 0.5; // la imagen se rasteriza a mitad de resolución
    final scale = fit * detail; // píxeles de imagen por píxel lógico
    final step = math.max(1, (widget.density.clamp(1.5, 8.0) * scale).round());
    var x = (area.width - total * fit) / 2; // centrado en el área
    final y0 = (area.height - height * fit) / 2;

    final out = <_CharSample>[];
    for (var i = 0; i < text.length; i++) {
      final tp = painters[i];
      final w = math.max(1, (tp.width * scale).ceil());
      final h = math.max(1, (tp.height * scale).ceil());

      final recorder = ui.PictureRecorder();
      final canvas = Canvas(recorder)..scale(scale);
      tp.paint(canvas, Offset.zero);
      final img = await recorder.endRecording().toImage(w, h);
      final data = await img.toByteData(format: ui.ImageByteFormat.rawRgba);
      img.dispose();

      final points = <Offset>[];
      if (data != null) {
        for (var yy = 0; yy < h; yy += step) {
          for (var xx = 0; xx < w; xx += step) {
            final alpha = data.getUint8((yy * w + xx) * 4 + 3);
            if (alpha > 120) {
              points.add(Offset(x + xx / scale, y0 + yy / scale));
            }
          }
        }
      }
      out.add((ch: text[i], points: points, x: x + tp.width * fit / 2));
      x += tp.width * fit;
    }
    return out;
  }

  /// Asigna destinos a los grupos de granos. Si el carácter no cambió (y no
  /// es un remuestreo forzado), su grupo conserva los destinos intactos.
  ///
  /// Cada grupo tiene tamaño FIJO: solo crece la primera vez que un dígito
  /// pide más granos que nunca. Cuando un dígito necesita menos (p. ej. "1"
  /// frente a "8"), los granos sobrantes se apilan sobre los mismos puntos
  /// (varios granos por muestra → más densidad de tinta), en vez de
  /// destruirse y reaparecer desde abajo.
  void _assign(List<_CharSample> samples, {required bool forceAll}) {
    while (_pools.length < samples.length) {
      _pools.add(_CharPool());
    }
    if (_pools.length > samples.length) {
      _pools.removeRange(samples.length, _pools.length);
    }
    for (var i = 0; i < samples.length; i++) {
      final s = samples[i];
      final pool = _pools[i];
      if (!forceAll && pool.ch == s.ch && pool.grains.isNotEmpty) {
        continue; // dígito intacto: sus granos no se mueven
      }
      pool.ch = s.ch;

      if (s.points.isEmpty) {
        // Carácter sin tinta (espacio): los granos se pliegan sobre su propia
        // posición con radio 0 (invisibles). Nada entra desde fuera del área:
        // el texto se imprime y luego se transforma en arena en su sitio.
        for (final g in pool.grains) {
          g.pos = Offset(s.x, _area.height / 2);
          g.target = g.pos;
          g.radius = 0;
        }
        continue;
      }

      // Grow ONLY if this digit requests more grains than the group
      // ha tenido nunca (tras calentar con los dígitos densos ya no
      // aparecen granos nuevos).
      final needed = math.min(s.points.length, _maxGrainsPerChar);
      while (pool.grains.length < needed) {
        // El grano nace EN su destino: se imprime el número y se transforma
        // en arena sin que los granos entren volando desde fuera del área.
        final p = s.points[pool.grains.length % s.points.length];
        final jitter = Offset(
          (_rng.nextDouble() - 0.5) * 1.5,
          (_rng.nextDouble() - 0.5) * 1.5,
        );
        pool.grains.add(_Grain()
          ..pos = p + jitter
          ..target = p + jitter
          ..jitter = jitter
          ..radius = widget.particleSize * (0.5 + _rng.nextDouble())
          ..opacity = 0.55 + _rng.nextDouble() * 0.45
          ..phase = _rng.nextDouble() * math.pi * 2
          ..speed = 0.6 + _rng.nextDouble() * 0.8);
      }
      // Reparto circular: si sobran granos, varios comparten el mismo punto
      // (se "sobrecargan" y el trazo se ve más denso donde hace falta).
      // Los que volvieron de estar plegados (radio 0) recuperan su tamaño.
      for (var j = 0; j < pool.grains.length; j++) {
        final g = pool.grains[j];
        g.target = s.points[j % s.points.length] + g.jitter;
        if (g.radius <= 0.1) {
          g.radius = widget.particleSize * (0.5 + _rng.nextDouble());
        }
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final text = formatClock(DateTime.now(), widget.format);
    final tp = TextPainter(
      text: TextSpan(text: text, style: widget.style),
      textDirection: TextDirection.ltr,
    )..layout();

    return LayoutBuilder(builder: (context, constraints) {
      // Si el padre da límites (p. ej. al redimensionar el widget) el reloj
      // los rellena; si no, usa el tamaño natural del texto.
      final bounded = constraints.hasBoundedWidth &&
          constraints.hasBoundedHeight &&
          constraints.maxWidth > 0 &&
          constraints.maxHeight > 0;
      final area = bounded
          ? Size(constraints.maxWidth, constraints.maxHeight)
          : Size(tp.width, tp.height);

      if (area != _area) {
        _area = area;
        _lastText = '';
        _forceAll = true; // remuestrear todos los caracteres con el nuevo encaje
        WidgetsBinding.instance.addPostFrameCallback((_) => _resample());
      }

      // Hasta que haya muestras se muestra el texto plano (también sirve de
      // degradado elegante si el rasterizado no está disponible).
      if (!_hasGrains) {
        return SizedBox(
          width: area.width,
          height: area.height,
          child: Center(child: FittedBox(child: Text(text, style: widget.style))),
        );
      }
      return SizedBox(
        width: area.width,
        height: area.height,
        child: CustomPaint(
          painter: _ParticlePainter(_pools, _color, repaint: _frames),
        ),
      );
    });
  }
}

class _ParticlePainter extends CustomPainter {
  _ParticlePainter(this.pools, this.color, {required Listenable repaint})
      : super(repaint: repaint);

  final List<_CharPool> pools;
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint();
    for (final pool in pools) {
      for (final g in pool.grains) {
        paint.color = color.withValues(alpha: g.opacity);
        canvas.drawCircle(g.pos, g.radius, paint);
      }
    }
  }

  @override
  bool shouldRepaint(covariant _ParticlePainter oldDelegate) =>
      oldDelegate.color != color || !identical(oldDelegate.pools, pools);
}

// ---------------------------------------------------------------------------
//  Reloj ticker (dígitos rodantes tipo cuentakilómetros / split-flap)
// ---------------------------------------------------------------------------
//  Cada carácter vive en su propio AnimatedSwitcher: al cambiar, el dígito
//  viejo sale deslizándose y el nuevo entra desde el lado opuesto. Los
//  dígitos usan ancho fijo para que la rodadura no desplace al resto.
// ---------------------------------------------------------------------------

class TickerClock extends StatefulWidget {
  const TickerClock({
    super.key,
    required this.format,
    required this.style,
    this.direction = 'up',
    this.digitWidth = 0,
  });

  final String format;
  final TextStyle style;

  /// "up" (el nuevo entra desde abajo) o "down" (entra desde arriba).
  final String direction;

  /// Ancho fijo por dígito en píxeles lógicos; 0 = ancho del glifo "0".
  final double digitWidth;

  @override
  State<TickerClock> createState() => _TickerClockState();
}

class _TickerClockState extends State<TickerClock> {
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  bool _isDigit(String ch) => ch.codeUnitAt(0) >= 0x30 && ch.codeUnitAt(0) <= 0x39;

  double _measureDigit(TextStyle style) {
    final tp = TextPainter(
      text: TextSpan(text: '0', style: style),
      textDirection: TextDirection.ltr,
    )..layout();
    return tp.width + 1;
  }

  @override
  Widget build(BuildContext context) {
    final text = formatClock(DateTime.now(), widget.format);
    final style = widget.style.copyWith(
      fontFeatures: const [ui.FontFeature.tabularFigures()],
    );
    final digitW =
        widget.digitWidth > 0 ? widget.digitWidth : _measureDigit(style);
    final up = widget.direction != 'down';

    final children = <Widget>[];
    for (var i = 0; i < text.length; i++) {
      final ch = text[i];
      if (!_isDigit(ch)) {
        // Separadores (":", espacios…) quedan fijos para no marear.
        children.add(Text(ch, style: style));
        continue;
      }
      final key = ValueKey('$i:$ch');
      children.add(AnimatedSwitcher(
        duration: const Duration(milliseconds: 380),
        switchInCurve: Curves.easeOutCubic,
        switchOutCurve: Curves.easeInCubic,
        transitionBuilder: (child, animation) {
          final incoming = child.key == key;
          final begin =
              Offset(0, incoming ? (up ? 1 : -1) : (up ? -1 : 1));
          return ClipRect(
            child: SlideTransition(
              position: Tween(begin: begin, end: Offset.zero).animate(animation),
              child: FadeTransition(opacity: animation, child: child),
            ),
          );
        },
        child: SizedBox(
          key: key,
          width: digitW,
          child: Text(ch, style: style, textAlign: TextAlign.center),
        ),
      ));
    }

    return Row(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.baseline,
      textBaseline: TextBaseline.alphabetic,
      children: children,
    );
  }
}
