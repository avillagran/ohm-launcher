// ============================================================================
//  QML BRIDGE — parser
//  ============================================================================
//  Parser recursivo descendente para el subconjunto de QML usado por los
//  plugins de Omarchy (QtQuick/Quickshell):
//
//    BarWidget { id: root
//      SystemClock { id: clock }
//      WidgetButton { text: Qt.formatTime(clock.date, "HH:mm") }
//    }
//
//  Soporta: imports, elementos anidados, "id:", propiedades (incluidas las
//  punteadas: font.pixelSize, anchors.fill), literales, referencias a ids,
//  llamadas (Qt.*, Math.*, Style.*) y expresiones JS básicas (+, comparaciones,
//  ternarios, &&, ||, !). Cualquier cosa que no entienda se degrada a null o a
//  un valor seguro en lugar de fallar.
// ============================================================================

enum QmlTokenKind { id, num, str, regex, sym, op, eof }

class QmlToken {
  const QmlToken(this.kind, this.text, this.line);
  final QmlTokenKind kind;
  final String text;
  final int line;
}

// ---------------------------------------------------------------------------
//  AST
// ---------------------------------------------------------------------------

sealed class QmlValueExpr {
  const QmlValueExpr();
}

class QmlLiteral extends QmlValueExpr {
  const QmlLiteral(this.value);
  final Object? value;
}

class QmlRef extends QmlValueExpr {
  const QmlRef(this.path);
  final List<String> path;
}

class QmlCall extends QmlValueExpr {
  const QmlCall(this.name, this.args);
  final String name;
  final List<QmlValueExpr> args;
}

class QmlUnary extends QmlValueExpr {
  const QmlUnary(this.op, this.operand);
  final String op;
  final QmlValueExpr operand;
}

class QmlBinary extends QmlValueExpr {
  const QmlBinary(this.op, this.left, this.right);
  final String op;
  final QmlValueExpr left;
  final QmlValueExpr right;
}

class QmlTernary extends QmlValueExpr {
  const QmlTernary(this.cond, this.then, this.orElse);
  final QmlValueExpr cond;
  final QmlValueExpr then;
  final QmlValueExpr orElse;
}

class QmlArray extends QmlValueExpr {
  const QmlArray(this.items);
  final List<QmlValueExpr> items;
}

class QmlObject extends QmlValueExpr {
  const QmlObject(this.fields);
  final Map<String, QmlValueExpr> fields;
}

/// Valor de propiedad que es un elemento QML inline (delegate: Item {...}).
class QmlInlineElement extends QmlValueExpr {
  const QmlInlineElement(this.element);
  final QmlElement element;
}

/// Marca de una función JS (cuerpo no interpretable); se ignora.
class QmlFunctionValue {
  const QmlFunctionValue();
}

class QmlElement {
  const QmlElement({
    required this.type,
    required this.baseType,
    this.id,
    required this.properties,
    required this.children,
    required this.line,
  });

  final String type;
  final String baseType;
  final String? id;
  final Map<String, QmlValueExpr> properties;
  final List<QmlElement> children;
  final int line;
}

class QmlDocument {
  const QmlDocument({
    required this.imports,
    required this.elements,
    this.error,
  });

  final List<String> imports;
  final List<QmlElement> elements;

  /// Mensaje de error de parseo (null si todo fue bien).
  final String? error;
}

// ---------------------------------------------------------------------------
//  Tokenizador
// ---------------------------------------------------------------------------

List<QmlToken> qmlTokenize(String src) {
  final tokens = <QmlToken>[];
  var i = 0;
  var line = 1;

  bool isIdStart(String c) => RegExp(r'[A-Za-z_$]').hasMatch(c);
  bool isIdChar(String c) => RegExp(r'[A-Za-z0-9_$]').hasMatch(c);
  bool isDigit(String c) => RegExp(r'[0-9]').hasMatch(c);

  while (i < src.length) {
    final c = src[i];
    if (c == '\n') {
      line++;
      i++;
      continue;
    }
    if (c == ' ' || c == '\t' || c == '\r') {
      i++;
      continue;
    }
    // Comentarios.
    if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
      while (i < src.length && src[i] != '\n') {
        i++;
      }
      continue;
    }
    if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
      i += 2;
      while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) {
        if (src[i] == '\n') line++;
        i++;
      }
      i += 2;
      continue;
    }
    // Regex literal /patron/flags (solo en posición de operando).
    if (c == '/' && _operandPosition(tokens)) {
      final s = i;
      i++;
      var inClass = false;
      while (i < src.length) {
        final ch = src[i];
        if (ch == '\\') {
          i += 2;
          continue;
        }
        if (ch == '[') {
          inClass = true;
        } else if (ch == ']') {
          inClass = false;
        } else if (ch == '/' && !inClass) {
          i++; // consumir el cierre del regex
          break;
        }
        i++;
      }
      while (i < src.length && isIdChar(src[i])) {
        i++;
      }
      tokens.add(QmlToken(QmlTokenKind.regex, src.substring(s, i), line));
      continue;
    }
    // Identificadores.
    if (isIdStart(c)) {
      final s = i;
      while (i < src.length && isIdChar(src[i])) {
        i++;
      }
      tokens.add(QmlToken(QmlTokenKind.id, src.substring(s, i), line));
      continue;
    }
    // Números (con decimales y exponentes).
    if (isDigit(c) || (c == '.' && i + 1 < src.length && isDigit(src[i + 1]))) {
      final s = i;
      while (i < src.length) {
        final ch = src[i];
        if (isDigit(ch) || ch == '.') {
          i++;
        } else if ((ch == 'e' || ch == 'E') &&
            i + 1 < src.length &&
            (isDigit(src[i + 1]) || ((src[i + 1] == '+' || src[i + 1] == '-') && i + 2 < src.length && isDigit(src[i + 2])))) {
          i += 2;
        } else {
          break;
        }
      }
      tokens.add(QmlToken(QmlTokenKind.num, src.substring(s, i), line));
      continue;
    }
    // Strings con escapes.
    if (c == '"' || c == "'") {
      final quote = c;
      i++;
      final buf = StringBuffer();
      while (i < src.length && src[i] != quote) {
        if (src[i] == '\\' && i + 1 < src.length) {
          final esc = src[i + 1];
          // \uXXXX: decodificar al carácter real (glifos Nerd Font 0xF000...).
          if (esc == 'u' && i + 5 < src.length) {
            final cp = int.tryParse(src.substring(i + 2, i + 6), radix: 16);
            if (cp != null) {
              buf.writeCharCode(cp);
              i += 6;
              continue;
            }
          }
          switch (esc) {
            case 'n':
              buf.write('\n');
            case 't':
              buf.write('\t');
            case 'r':
              buf.write('\r');
            case '"':
              buf.write('"');
            case "'":
              buf.write("'");
            case '\\':
              buf.write('\\');
            default:
              buf.write(esc);
          }
          i += 2;
          continue;
        }
        if (src[i] == '\n') line++;
        buf.write(src[i]);
        i++;
      }
      i++; // cierre
      tokens.add(QmlToken(QmlTokenKind.str, buf.toString(), line));
      continue;
    }
    // Símbolos (incluido el punto para nombres calificados).
    if ('{}()[],:;.'.contains(c)) {
      tokens.add(QmlToken(QmlTokenKind.sym, c, line));
      i++;
      continue;
    }
    // Operadores (primero los de tres caracteres, luego los de dos).
    const threeCharOps = ['===', '!=='];
    if (i + 2 < src.length && threeCharOps.contains(src.substring(i, i + 3))) {
      tokens.add(QmlToken(QmlTokenKind.op, src.substring(i, i + 3), line));
      i += 3;
      continue;
    }
    const twoCharOps = ['==', '!=', '<=', '>=', '&&', '||'];
    if (i + 1 < src.length && twoCharOps.contains(src.substring(i, i + 2))) {
      tokens.add(QmlToken(QmlTokenKind.op, src.substring(i, i + 2), line));
      i += 2;
      continue;
    }
    if ('+-*/%<>=!?:'.contains(c)) {
      tokens.add(QmlToken(QmlTokenKind.op, c, line));
      i++;
      continue;
    }
    i++; // carácter desconocido: se omite
  }
  tokens.add(const QmlToken(QmlTokenKind.eof, '', 0));
  return tokens;
}

/// True si `/` puede iniciar un regex (posición de operando, no división).
bool _operandPosition(List<QmlToken> tokens) {
  if (tokens.isEmpty) return true;
  final last = tokens.last;
  switch (last.kind) {
    case QmlTokenKind.op:
      return true;
    case QmlTokenKind.sym:
      return last.text == '(' ||
          last.text == '[' ||
          last.text == '{' ||
          last.text == ',' ||
          last.text == ':' ||
          last.text == ';';
    default:
      return false;
  }
}

// ---------------------------------------------------------------------------
//  Parser
// ---------------------------------------------------------------------------

class QmlParser {
  QmlParser(String source) : _tokens = qmlTokenize(source);

  final List<QmlToken> _tokens;
  int _pos = 0;

  QmlToken get _cur => _tokens[_pos];
  bool get _atEnd => _cur.kind == QmlTokenKind.eof;

  bool _check(QmlTokenKind kind, [String? text]) =>
      _cur.kind == kind && (text == null || _cur.text == text);

  bool _match(QmlTokenKind kind, [String? text]) {
    if (_check(kind, text)) {
      _pos++;
      return true;
    }
    return false;
  }

  QmlToken _expect(QmlTokenKind kind, String what, [String? message]) {
    if (_cur.kind != kind) {
      throw FormatException(
        message ?? 'Se esperaba $what pero se encontró "${_cur.text}" (línea ${_cur.line})',
      );
    }
    final t = _cur;
    _pos++;
    return t;
  }

  String _expectId() => _expect(QmlTokenKind.id, 'un identificador').text;

  bool _matchId(String id) {
    if (_check(QmlTokenKind.id, id)) {
      _pos++;
      return true;
    }
    return false;
  }

  bool _matchOp(String op) => _match(QmlTokenKind.op, op);

  bool _checkOp(String op) => _check(QmlTokenKind.op, op);

  bool _matchSym(String sym) => _match(QmlTokenKind.sym, sym);

  bool _checkSym(String sym) => _check(QmlTokenKind.sym, sym);

  /// Lee un nombre punteado (Qt.formatTime, anchors.fill, qs.Ui.BarWidget).
  String _readDottedName() {
    final buf = StringBuffer(_expectId());
    while (_checkSym('.') && _pos + 1 < _tokens.length && _tokens[_pos + 1].kind == QmlTokenKind.id) {
      _pos++;
      buf.write('.');
      buf.write(_expectId());
    }
    return buf.toString();
  }

  QmlDocument parse() {
    final imports = <String>[];
    final elements = <QmlElement>[];
    try {
      while (!_atEnd) {
        if (_matchId('import')) {
          if (_check(QmlTokenKind.str)) {
            imports.add(_expect(QmlTokenKind.str, 'un módulo (string)').text);
          } else {
            imports.add(_readDottedName());
          }
          if (_matchId('as')) {
            if (_check(QmlTokenKind.id)) _pos++;
          }
          _matchSym(';');
          continue;
        }
        if (_matchId('pragma')) {
          while (!_atEnd && !_checkSym(';')) {
            _pos++;
          }
          _matchSym(';');
          continue;
        }
        elements.add(_parseElement());
      }
      return QmlDocument(imports: imports, elements: elements);
    } on FormatException catch (e) {
      return QmlDocument(imports: imports, elements: [], error: e.message);
    } catch (e) {
      return QmlDocument(imports: imports, elements: [], error: 'Error de parseo: $e');
    }
  }

  QmlElement _parseElement() {
    final line = _cur.line;
    final type = _readDottedName();
    _expect(QmlTokenKind.sym, '{', 'la apertura "{" del elemento $type');
    final base = type.split('.').last;

    String? id;
    final props = <String, QmlValueExpr>{};
    final children = <QmlElement>[];

    while (!_checkSym('}')) {
      if (_atEnd) throw const FormatException('Archivo QML truncado: falta "}"');
      if (_matchSym(';')) continue;
      if (_matchId('id')) {
        _expect(QmlTokenKind.sym, ':');
        id = _expect(QmlTokenKind.id, 'el id').text;
        continue;
      }
      if (_matchId('readonly')) {
        _matchId('property');
        _skipPropertyDeclaration(props);
        continue;
      }
      if (_matchId('property')) {
        _skipPropertyDeclaration(props);
        continue;
      }
      if (_matchId('required')) {
        _matchId('property');
        _skipPropertyDeclaration(props);
        continue;
      }
      if (_matchId('function')) {
        _skipFunctionBody();
        continue;
      }
      if (_matchId('signal')) {
        while (!_atEnd && !_checkSym(';') && !_checkSym('{')) {
          _pos++;
        }
        _matchSym(';');
        _matchSym('{');
        continue;
      }

      final name = _readDottedName();
      if (_matchSym(':')) {
        props[name] = _parseValue();
      } else if (_matchId('on')) {
        // Modificador QML: Behavior on opacity { ... }
        _readDottedName();
        _expect(QmlTokenKind.sym, '{', '"{" de $name on <prop>');
        children.add(_parseElementTail(name, line));
      } else if (_matchSym('{')) {
        children.add(_parseElementTail(name, line));
      } else {
        throw FormatException('Se esperaba ":" o "{" tras "$name" (línea ${_cur.line})');
      }
    }
    _expect(QmlTokenKind.sym, '}', 'el cierre "}" de $type');
    return QmlElement(
      type: type,
      baseType: base,
      id: id,
      properties: props,
      children: children,
      line: line,
    );
  }

  /// Termina de parsear un elemento cuyo "{" ya se consumió.
  QmlElement _parseElementTail(String type, int line) {
    final base = type.split('.').last;
    String? id;
    final props = <String, QmlValueExpr>{};
    final children = <QmlElement>[];

    while (!_checkSym('}')) {
      if (_atEnd) throw const FormatException('Archivo QML truncado: falta "}"');
      if (_matchSym(';')) continue;
      if (_matchId('id')) {
        _expect(QmlTokenKind.sym, ':');
        id = _expect(QmlTokenKind.id, 'el id').text;
        continue;
      }
      if (_matchId('readonly')) {
        _matchId('property');
        _skipPropertyDeclaration(props);
        continue;
      }
      if (_matchId('property')) {
        _skipPropertyDeclaration(props);
        continue;
      }
      if (_matchId('required')) {
        _matchId('property');
        _skipPropertyDeclaration(props);
        continue;
      }
      if (_matchId('function')) {
        _skipFunctionBody();
        continue;
      }

      final name = _readDottedName();
      if (_matchSym(':')) {
        props[name] = _parseValue();
      } else if (_matchId('on')) {
        // Modificador QML: Behavior on opacity { ... }
        _readDottedName();
        _expect(QmlTokenKind.sym, '{', '"{" de $name on <prop>');
        children.add(_parseElementTail(name, line));
      } else if (_matchSym('{')) {
        children.add(_parseElementTail(name, line));
      } else {
        throw FormatException('Se esperaba ":" o "{" tras "$name" (línea ${_cur.line})');
      }
    }
    _expect(QmlTokenKind.sym, '}', 'el cierre "}" de $type');
    return QmlElement(type: type, baseType: base, id: id, properties: props, children: children, line: line);
  }

  /// `property bool opened: expr` (o sin inicializador). Se ignora.
  /// `readonly property string icon: "..."` — guarda la propiedad declarada en
  /// el mapa del elemento para que `root.<nombre>` pueda resolverse.
  void _skipPropertyDeclaration(Map<String, QmlValueExpr>? into) {
    if (_check(QmlTokenKind.id)) _pos++; // tipo (bool, var, string, alias...)
    if (!_check(QmlTokenKind.id)) return; // sin nombre: no almacenable
    final name = _expectId();
    if (_matchSym(':')) {
      into?[name] = _parseValue();
    }
  }

  /// `function name(args): tipo { ... }` — se ignora el cuerpo (balanceo de
  /// llaves). Soporta el tipo de retorno TS-style `: void`.
  void _skipFunctionBody() {
    if (_check(QmlTokenKind.id)) _pos++; // nombre
    if (_checkSym('(')) {
      _pos++;
      var depth = 1;
      while (!_atEnd && depth > 0) {
        if (_matchSym('(')) {
          depth++;
        } else if (_matchSym(')')) {
          depth--;
        } else {
          _pos++;
        }
      }
    }
    if (_matchSym(':')) {
      // Tipo de retorno opcional (void, bool, string...).
      while (!_atEnd && !_checkSym('{')) {
        _pos++;
      }
    }
    if (_matchSym('{')) {
      var depth = 1;
      while (!_atEnd && depth > 0) {
        if (_matchSym('{')) {
          depth++;
        } else if (_matchSym('}')) {
          depth--;
        } else {
          _pos++;
        }
      }
    }
  }

  // ------------------------------------------------- valores / expresiones

  QmlValueExpr _parseValue() {
    if (_matchId('function')) {
      _skipFunctionBody();
      return const QmlLiteral(QmlFunctionValue());
    }
    // Sentencias JS como valores de handlers: if/for ... { ... }.
    if (_matchId('if') || _matchId('for') || _matchId('while')) {
      if (_checkSym('(')) _skipBalanced('(', ')');
      _skipStatementOrBlock();
      if (_matchId('else')) _skipStatementOrBlock();
      return const QmlLiteral(null);
    }
    if (_checkSym('[')) return _parseArray();
    if (_checkSym('{')) {
      // Bloque JS ({ if (...) return ... }) u objeto literal: no evaluable en
      // el bridge → se ignora de forma segura.
      _skipBalanced('{', '}');
      return const QmlLiteral(null);
    }
    // Valor = elemento QML inline (delegate: Item {...}).
    if (_looksLikeInlineElement()) {
      final line = _cur.line;
      final type = _readDottedName();
      _expect(QmlTokenKind.sym, '{', '"{" del elemento inline $type');
      return QmlInlineElement(_parseElementTail(type, line));
    }
    return _parseExpression();
  }

  /// True si el token actual inicia "Tipo { ... }" (elemento inline).
  bool _looksLikeInlineElement() {
    if (!_check(QmlTokenKind.id)) return false;
    var i = _pos + 1;
    while (i < _tokens.length &&
        _tokens[i].kind == QmlTokenKind.sym &&
        _tokens[i].text == '.' &&
        i + 1 < _tokens.length &&
        _tokens[i + 1].kind == QmlTokenKind.id) {
      i += 2;
    }
    return i < _tokens.length && _tokens[i].kind == QmlTokenKind.sym && _tokens[i].text == '{';
  }

  /// Salta una declaración: `{ ... }` o una única expresión hasta el final
  /// del contexto (`,`/`}`).
  void _skipStatementOrBlock() {
    if (_checkSym('{')) {
      _skipBalanced('{', '}');
    } else {
      while (!_atEnd) {
        if (_checkSym('{') || _checkSym('(') || _checkSym('[')) {
          final open = _cur.text;
          _skipBalanced(open, _closeFor(open));
          continue;
        }
        if (_checkSym(',') || _checkSym('}') || _checkSym(';')) break;
        _pos++;
      }
    }
  }

  String _closeFor(String open) => switch (open) {
        '(' => ')',
        '[' => ']',
        _ => '}',
      };

  QmlValueExpr _parseArray() {
    _expect(QmlTokenKind.sym, '[', '"["');
    final items = <QmlValueExpr>[];
    if (!_checkSym(']')) {
      while (true) {
        items.add(_parseExpression());
        if (!_matchSym(',')) break;
        if (_checkSym(']')) break; // coma final permitida en JS
      }
    }
    _expect(QmlTokenKind.sym, ']', '"]"');
    return QmlArray(items);
  }

  QmlValueExpr _parseExpression() {
    // Arrow functions como argumentos: (a, b) => body  o  a => body.
    if (_looksLikeArrowParams()) {
      _skipBalanced('(', ')');
      _consumeArrowBody();
      return const QmlLiteral(QmlFunctionValue());
    }
    final e = _parseTernary();
    if (_nextIsArrow()) {
      _consumeArrowBody();
      return const QmlLiteral(QmlFunctionValue());
    }
    // Asignaciones en handlers (onScanned: root.omarchy = items).
    if (_matchOp('=')) {
      return QmlBinary('=', e, _parseExpression());
    }
    return e;
  }

  bool _nextIsArrow() =>
      _checkOp('=') &&
      _pos + 1 < _tokens.length &&
      _tokens[_pos + 1].kind == QmlTokenKind.op &&
      _tokens[_pos + 1].text == '>';

  /// Comprueba si el token actual es "(params) =>".
  bool _looksLikeArrowParams() {
    if (!_checkSym('(')) return false;
    var i = _pos + 1;
    var depth = 1;
    while (i < _tokens.length && depth > 0) {
      final tk = _tokens[i];
      if (tk.kind == QmlTokenKind.sym && tk.text == '(') {
        depth++;
      } else if (tk.kind == QmlTokenKind.sym && tk.text == ')') {
        depth--;
      }
      i++;
    }
    if (depth != 0 || i >= _tokens.length) return false;
    return _tokens[i].kind == QmlTokenKind.op &&
        _tokens[i].text == '=' &&
        i + 1 < _tokens.length &&
        _tokens[i + 1].kind == QmlTokenKind.op &&
        _tokens[i + 1].text == '>';
  }

  void _consumeArrowBody() {
    _pos += 2; // '=>'
    if (_checkSym('{')) {
      _skipBalanced('{', '}');
    } else {
      _parseExpression();
    }
  }

  QmlValueExpr _parseTernary() {
    final cond = _parseOr();
    if (_matchOp('?')) {
      final thenExpr = _parseExpression();
      _expect(QmlTokenKind.sym, ':', '":" del ternario');
      final elseExpr = _parseExpression();
      return QmlTernary(cond, thenExpr, elseExpr);
    }
    return cond;
  }

  QmlValueExpr _parseOr() {
    var left = _parseAnd();
    while (_matchOp('||')) {
      left = QmlBinary('||', left, _parseAnd());
    }
    return left;
  }

  QmlValueExpr _parseAnd() {
    var left = _parseEquality();
    while (_matchOp('&&')) {
      left = QmlBinary('&&', left, _parseEquality());
    }
    return left;
  }

  QmlValueExpr _parseEquality() {
    var left = _parseRelational();
    while (true) {
      if (_matchOp('==')) {
        left = QmlBinary('==', left, _parseRelational());
      } else if (_matchOp('===')) {
        left = QmlBinary('===', left, _parseRelational());
      } else if (_matchOp('!=')) {
        left = QmlBinary('!=', left, _parseRelational());
      } else if (_matchOp('!==')) {
        left = QmlBinary('!==', left, _parseRelational());
      } else {
        return left;
      }
    }
  }

  QmlValueExpr _parseRelational() {
    var left = _parseAdditive();
    while (true) {
      if (_matchOp('<')) {
        left = QmlBinary('<', left, _parseAdditive());
      } else if (_matchOp('>')) {
        left = QmlBinary('>', left, _parseAdditive());
      } else if (_matchOp('<=')) {
        left = QmlBinary('<=', left, _parseAdditive());
      } else if (_matchOp('>=')) {
        left = QmlBinary('>=', left, _parseAdditive());
      } else {
        return left;
      }
    }
  }

  QmlValueExpr _parseAdditive() {
    var left = _parseMultiplicative();
    while (true) {
      if (_matchOp('+')) {
        left = QmlBinary('+', left, _parseMultiplicative());
      } else if (_matchOp('-')) {
        left = QmlBinary('-', left, _parseMultiplicative());
      } else {
        return left;
      }
    }
  }

  QmlValueExpr _parseMultiplicative() {
    var left = _parseUnary();
    while (true) {
      if (_matchOp('*')) {
        left = QmlBinary('*', left, _parseUnary());
      } else if (_matchOp('/')) {
        left = QmlBinary('/', left, _parseUnary());
      } else if (_matchOp('%')) {
        left = QmlBinary('%', left, _parseUnary());
      } else {
        return left;
      }
    }
  }

  QmlValueExpr _parseUnary() {
    if (_matchOp('!')) return QmlUnary('!', _parseUnary());
    if (_matchOp('-')) return QmlUnary('-', _parseUnary());
    return _parsePrimary();
  }

  QmlValueExpr _parsePrimary() {
    final t = _cur;
    if (t.kind == QmlTokenKind.str) {
      _pos++;
      return QmlLiteral(t.text);
    }
    if (t.kind == QmlTokenKind.regex) {
      _pos++;
      return QmlLiteral(t.text);
    }
    if (t.kind == QmlTokenKind.num) {
      _pos++;
      final n = t.text.contains('.') ? double.parse(t.text) : int.parse(t.text);
      return QmlLiteral(n);
    }
    if (t.kind == QmlTokenKind.id) {
      final first = _expectId();
      if (first == 'true') return const QmlLiteral(true);
      if (first == 'false') return const QmlLiteral(false);
      if (first == 'null' || first == 'undefined') return const QmlLiteral(null);
      if (first == 'function') {
        _skipFunctionBody();
        return const QmlLiteral(QmlFunctionValue());
      }
      // `new Date(...)`: sin constructor real; se degrada a null.
      if (first == 'new') {
        _readDottedName();
        if (_checkSym('(')) _skipBalanced('(', ')');
        while (_checkSym('.') || _checkSym('[')) {
          if (_matchSym('.')) {
            if (_check(QmlTokenKind.id)) _pos++;
            if (_checkSym('(')) _skipBalanced('(', ')');
          } else if (_matchSym('[')) {
            _skipBalanced('[', ']');
          }
        }
        return const QmlLiteral(null);
      }
      final buf = StringBuffer(first);
      while (_checkSym('.') && _pos + 1 < _tokens.length && _tokens[_pos + 1].kind == QmlTokenKind.id) {
        _pos++;
        buf.write('.');
        buf.write(_expectId());
      }
      final full = buf.toString();
      if (_checkSym('(')) {
        _pos++;
        final args = <QmlValueExpr>[];
        if (!_checkSym(')')) {
          while (true) {
            args.add(_parseExpression());
            if (!_matchSym(',')) break;
            if (_checkSym(')')) break; // coma final permitida en JS
          }
        }
        _expect(QmlTokenKind.sym, ')', '")" de la llamada $full');
        return _postfixChain(QmlCall(full, args));
      }
      return _postfixChain(QmlRef(full.split('.')));
    }
    if (_checkSym('(')) {
      _pos++;
      final inner = _parseExpression();
      _expect(QmlTokenKind.sym, ')', '")"');
      return inner;
    }
    if (_checkSym('{')) {
      // Objeto literal en arrays/expresiones: { icon: "...", label: "..." }.
      _expect(QmlTokenKind.sym, '{', '"{"');
      final fields = <String, QmlValueExpr>{};
      if (!_checkSym('}')) {
        while (true) {
          final key = _readDottedName();
          _expect(QmlTokenKind.sym, ':', '":" del objeto');
          fields[key] = _parseExpression();
          if (!_matchSym(',')) break;
          if (_checkSym('}')) break; // coma final permitida en JS
        }
      }
      _expect(QmlTokenKind.sym, '}', '"}"');
      return QmlObject(fields);
    }
    throw FormatException('Expresión inesperada "${t.text}" (línea ${t.line})');
  }

  /// Procesa accesos postfijos tras una referencia o llamada:
  /// `Qt.locale().firstDayOfWeek`, `items[index]`, `foo.bar.baz`.
  QmlValueExpr _postfixChain(QmlValueExpr base) {
    var result = base;
    while (true) {
      if (_checkSym('.') && _pos + 1 < _tokens.length && _tokens[_pos + 1].kind == QmlTokenKind.id) {
        _pos++; // '.'
        final prop = _expectId();
        if (_checkSym('(')) {
          // Método encadenado: se consume y se degrada (p. ej. getFullYear()).
          _skipBalanced('(', ')');
          result = result is QmlRef ? QmlRef([...result.path, '<call>']) : result;
        } else {
          result = result is QmlRef
              ? QmlRef([...result.path, prop])
              : QmlRef([(result as QmlCall).name, prop]);
        }
        continue;
      }
      if (result is QmlRef && _checkSym('[')) {
        // Acceso por índice: se consume; el valor se resuelve como null.
        _skipBalanced('[', ']');
        result = QmlRef([...result.path, '[index]']);
        continue;
      }
      return result;
    }
  }

  /// Consume un bloque balanceado (open/close) sin interpretarlo.
  void _skipBalanced(String open, String close) {
    _pos++; // open
    var depth = 1;
    while (!_atEnd && depth > 0) {
      if (_matchSym(open)) {
        depth++;
      } else if (_matchSym(close)) {
        depth--;
      } else {
        _pos++;
      }
    }
  }
}

/// Helper para construir literales (usado por el runtime).
QmlValueExpr qmlLiteral(Object? value) => QmlLiteral(value);

/// Decodifica una URL "file:///..." a una ruta local.
String qmlUrlToPath(String url) {
  if (!url.startsWith('file://')) return url;
  final raw = url.substring('file://'.length);
  try {
    return Uri.decodeComponent(raw);
  } catch (_) {
    return raw;
  }
}