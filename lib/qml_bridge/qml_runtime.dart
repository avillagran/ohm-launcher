// ============================================================================
//  QML BRIDGE — runtime
//  ============================================================================
//  Evalúa el AST de expresiones en un scope de ids, resolviendo referencias
//  (parent/root/ids), SystemClock -> .date, y funciones conocidas de Qt,
//  Math y Style. Todo fallo se degrada a null (el constructor de widgets
//  aplica valores por defecto).
// ============================================================================

import 'dart:math' as math;

import '../clock_widget.dart';
import 'qml_parser.dart';

/// Ámbito de visibilidad de ids durante el build del QML.
class QmlScope {
  QmlScope([this.parent]);

  final QmlScope? parent;
  final Map<String, Object?> values = {};

  Object? lookup(String name) =>
      values.containsKey(name) ? values[name] : parent?.lookup(name);
}

/// Valor especial de SystemClock: expone la fecha actual.
class QmlClockValue {
  QmlClockValue(this.date);
  final DateTime date;

  QmlClockValue.now() : date = DateTime.now();
}

/// Valor de un elemento QML con id/root: permite resolver sus propiedades
/// (`root.icon`, `button.text`, `clock.precision`...) con guarda anti-recursión
/// para bindings circulares típicos de QML (implicitWidth: button.implicitWidth).
class QmlElementValue {
  QmlElementValue(this.element, this.scope);

  final QmlElement element;
  final QmlScope scope;

  Object? getProperty(String name, int depth) {
    if (depth > 24) return null;
    final expr = element.properties[name];
    if (expr == null) return null;
    try {
      return qmlEval(expr, scope, depth + 1);
    } catch (_) {
      return null;
    }
  }
}

Object? qmlEval(QmlValueExpr expr, QmlScope scope, [int depth = 0]) {
  if (depth > 24) return null;
  switch (expr) {
    case QmlLiteral():
      return expr.value;
    case QmlRef():
      return _resolvePath(expr.path, scope, depth);
    case QmlCall():
      return _call(expr.name, [for (final a in expr.args) qmlEval(a, scope, depth + 1)]);
    case QmlUnary():
      final v = qmlEval(expr.operand, scope, depth + 1);
      if (expr.op == '!') return !_truthy(v);
      if (expr.op == '-') return -_num(v);
      return v;
    case QmlBinary():
      return _binary(expr.op, qmlEval(expr.left, scope, depth + 1), qmlEval(expr.right, scope, depth + 1));
    case QmlTernary():
      return _truthy(qmlEval(expr.cond, scope, depth + 1))
          ? qmlEval(expr.then, scope, depth + 1)
          : qmlEval(expr.orElse, scope, depth + 1);
    case QmlArray():
      return [for (final i in expr.items) qmlEval(i, scope, depth + 1)];
    case QmlObject():
      return {for (final k in expr.fields.keys) k: qmlEval(expr.fields[k]!, scope, depth + 1)};
    case QmlInlineElement():
      return null; // los elementos inline (delegate) no se evalúan como valor
  }
}

Object? _resolvePath(List<String> path, QmlScope scope, int depth) {
  if (path.isEmpty) return null;
  Object? cur = _resolveName(path.first, scope);
  for (final part in path.skip(1)) {
    cur = _getProp(cur, part, depth);
  }
  return cur;
}

Object? _resolveName(String name, QmlScope scope) {
  switch (name) {
    case 'parent':
      return null; // referencias de layout: los constructores aplican defaults
    case 'Qt':
    case 'Math':
    case 'Style':
      return null; // los nombres punteados (Qt.formatTime) se resuelven en _call
    default:
      return scope.lookup(name);
  }
}

Object? _getProp(Object? obj, String name, int depth) {
  if (obj is QmlClockValue) {
    if (name == 'date') return obj.date;
    return null;
  }
  if (obj is QmlElementValue) return obj.getProperty(name, depth);
  if (obj is Map) return obj[name];
  if (obj is String && name == 'length') return obj.length;
  return null;
}

Object? _call(String name, List<Object?> args) {
  switch (name) {
    case 'Qt.formatTime':
      return _format(args, 'HH:mm');
    case 'Qt.formatDateTime':
      return _format(args, 'yyyy-MM-dd HH:mm:ss');
    case 'Qt.formatDate':
      return _format(args, 'yyyy-MM-dd');
    case 'Qt.resolvedUrl':
      return args.isNotEmpty ? '${args.first}' : '';
    case 'Qt.locale':
    case 'Qt.formatNumber':
    case 'Qt.rect':
    case 'Qt.size':
    case 'Qt.point':
      return null;
    case 'Math.round':
      return _num(args.isNotEmpty ? args.first : 0).round();
    case 'Math.floor':
      return _num(args.isNotEmpty ? args.first : 0).floor();
    case 'Math.ceil':
      return _num(args.isNotEmpty ? args.first : 0).ceil();
    case 'Math.abs':
      return _num(args.isNotEmpty ? args.first : 0).abs();
    case 'Math.min':
      return args.fold<double>(double.infinity, (m, a) => math.min(m, _num(a).toDouble()));
    case 'Math.max':
      return args.fold<double>(double.negativeInfinity, (m, a) => math.max(m, _num(a).toDouble()));
    case 'Style.space':
      return _num(args.isNotEmpty ? args.first : 16).toDouble();
    case 'String':
      return args.isEmpty ? null : '${args.first}';
    case 'Number':
    case 'parseInt':
    case 'parseFloat':
      return _numOrNull(args.isEmpty ? null : args.first);
    case 'Array.isArray':
      return args.isNotEmpty && args.first is List;
    default:
      return null;
  }
}

String? _format(List<Object?> args, String defaultFormat) {
  if (args.isEmpty) return null;
  final date = args.first;
  final format = args.length > 1 && args[1] is String ? args[1] as String : defaultFormat;
  if (date is DateTime) return formatClock(date, format);
  if (date is QmlClockValue) return formatClock(date.date, format);
  return null;
}

Object? _binary(String op, Object? l, Object? r) {
  if (op == '=') return r; // asignación en handler: resultado no utilizado
  if (op == '+') {
    if (l is num && r is num) return l + r;
    return '$l$r';
  }
  if (op == '&&') return _truthy(l) ? r : l;
  if (op == '||') return _truthy(l) ? l : r;
  final a = _numOrNull(l);
  final b = _numOrNull(r);
  switch (op) {
    case '-':
      return a == null || b == null ? null : a - b;
    case '*':
      return a == null || b == null ? null : a * b;
    case '/':
      return a == null || b == null ? null : a / b;
    case '%':
      return a == null || b == null ? null : a % b;
    case '<':
      return a != null && b != null ? a < b : ('$l'.compareTo('$r') < 0);
    case '>':
      return a != null && b != null ? a > b : ('$l'.compareTo('$r') > 0);
    case '<=':
      return a != null && b != null ? a <= b : ('$l'.compareTo('$r') <= 0);
    case '>=':
      return a != null && b != null ? a >= b : ('$l'.compareTo('$r') >= 0);
    case '==':
    case '===':
      return _eq(l, r);
    case '!=':
    case '!==':
      return !_eq(l, r);
  }
  return null;
}

bool _eq(Object? a, Object? b) {
  if (a is num && b is num) return a == b;
  return a == b;
}

bool _truthy(Object? v) {
  if (v == null) return false;
  if (v is bool) return v;
  if (v is num) return v != 0;
  if (v is String) return v.isNotEmpty;
  if (v is List) return v.isNotEmpty;
  return true;
}

num _num(Object? v) {
  if (v is num) return v;
  if (v is bool) return v ? 1 : 0;
  if (v is String) return double.tryParse(v) ?? 0;
  return 0;
}

num? _numOrNull(Object? v) {
  if (v is num) return v;
  if (v is String) return double.tryParse(v);
  return null;
}