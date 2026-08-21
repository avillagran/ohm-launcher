// ============================================================================
//  CLOCK WIDGET — reloj en vivo compartido
//  ============================================================================
//  Formatea DateTime con tokens estilo QML/strftime y provee un widget Text
//  que actualiza cada segundo. Lo usan tanto el motor JSON como el bridge QML.
//  Tokens: HH, hh, mm, ss, a, dd, d, MM, MMM, MMMM, yyyy, EEE, EEEE, ddd, dddd.
// ============================================================================

import 'dart:async';

import 'package:flutter/material.dart';

/// Convierte [now] a texto usando el [format] dado.
String formatClock(DateTime now, String format) {
  const days = ['lunes', 'martes', 'miércoles', 'jueves', 'viernes', 'sábado', 'domingo'];
  const daysShort = ['lun', 'mar', 'mié', 'jue', 'vie', 'sáb', 'dom'];
  const months = [
    'enero', 'febrero', 'marzo', 'abril', 'mayo', 'junio',
    'julio', 'agosto', 'septiembre', 'octubre', 'noviembre', 'diciembre',
  ];
  const monthsShort = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];
  final hour12 = now.hour % 12 == 0 ? 12 : now.hour % 12;

  final tokens = <String, String>{
    'HH': twoDigits(now.hour),
    'hh': twoDigits(hour12),
    'mm': twoDigits(now.minute),
    'ss': twoDigits(now.second),
    'a': now.hour < 12 ? 'AM' : 'PM',
    'dd': twoDigits(now.day),
    'd': now.day.toString(),
    'MM': twoDigits(now.month),
    'MMM': monthsShort[now.month - 1],
    'MMMM': months[now.month - 1],
    'yyyy': now.year.toString(),
    'EEE': daysShort[now.weekday - 1],
    'EEEE': days[now.weekday - 1],
    'ddd': daysShort[now.weekday - 1],
    'dddd': days[now.weekday - 1],
  };

  var out = format;
  // Sustituir primero los tokens más largos y solo cuando forman un token
  // completo (no dentro de una palabra): "a" no debe corromper "ago".
  final keys = tokens.keys.toList()..sort((a, b) => b.length.compareTo(a.length));
  for (final key in keys) {
    final re = RegExp('(?<![A-Za-z0-9])${RegExp.escape(key)}(?![A-Za-z0-9])');
    out = out.replaceAllMapped(re, (_) => tokens[key]!);
  }
  return out;
}

String twoDigits(int n) => n.toString().padLeft(2, '0');

/// Widget de texto que muestra la hora actualizada cada segundo.
class ClockText extends StatefulWidget {
  const ClockText({
    super.key,
    required this.format,
    required this.style,
    this.textAlign = TextAlign.left,
  });

  final String format;
  final TextStyle style;
  final TextAlign textAlign;

  @override
  State<ClockText> createState() => _ClockTextState();
}

class _ClockTextState extends State<ClockText> {
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

  @override
  Widget build(BuildContext context) {
    return Text(
      formatClock(DateTime.now(), widget.format),
      style: widget.style,
      textAlign: widget.textAlign,
    );
  }
}