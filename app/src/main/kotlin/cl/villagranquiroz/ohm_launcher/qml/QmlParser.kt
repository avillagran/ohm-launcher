package cl.villagranquiroz.ohm_launcher.qml

class QmlParser(source: String) {
    private val tokens = QmlTokenizer.tokenize(source)
    private var position = 0

    private val current: QmlToken get() = tokens[position]
    private val atEnd: Boolean get() = current.kind == QmlTokenKind.EOF

    fun parse(): QmlDocument {
        val imports = mutableListOf<String>()
        val elements = mutableListOf<QmlElement>()
        return try {
            while (!atEnd) {
                when {
                    matchId("import") -> parseImport(imports)
                    matchId("pragma") -> skipToStatementEnd()
                    else -> elements += parseElement()
                }
            }
            QmlDocument(imports, elements)
        } catch (error: QmlParseException) {
            QmlDocument(imports, emptyList(), error.message)
        } catch (error: RuntimeException) {
            QmlDocument(imports, emptyList(), "QML parse error: ${error.message ?: error::class.java.simpleName}")
        }
    }

    private fun parseImport(imports: MutableList<String>) {
        val module = if (check(QmlTokenKind.STRING)) advance().text else readDottedName()
        imports += module
        if (check(QmlTokenKind.NUMBER)) advance() // Optional QML module version.
        if (matchId("as") && check(QmlTokenKind.IDENTIFIER)) advance()
        matchSymbol(";")
    }

    private fun parseElement(): QmlElement {
        val line = current.line
        val type = readDottedName()
        expectSymbol("{", "opening '{' for element $type")
        return parseElementBody(type, line)
    }

    private fun parseElementBody(type: String, line: Int): QmlElement {
        var id: String? = null
        val properties = linkedMapOf<String, QmlExpression>()
        val children = mutableListOf<QmlElement>()

        while (!checkSymbol("}")) {
            if (atEnd) fail("Truncated QML document: missing '}'", current)
            if (matchSymbol(";")) continue

            when {
                matchId("id") -> {
                    expectSymbol(":", "':' after id")
                    id = expect(QmlTokenKind.IDENTIFIER, "element id").text
                }
                checkId("readonly") || checkId("required") || checkId("default") || checkId("property") -> parsePropertyDeclaration(properties)
                matchId("function") -> skipFunctionAfterKeyword()
                matchId("signal") -> skipSignalDeclaration()
                matchId("enum") -> skipEnumDeclaration()
                else -> {
                    val memberLine = current.line
                    val name = readDottedName()
                    when {
                        matchSymbol(":") -> properties[name] = parseValue()
                        matchId("on") -> {
                            readDottedName()
                            expectSymbol("{", "opening '{' for $name behavior")
                            children += parseElementBody(name, memberLine)
                        }
                        matchSymbol("{") -> children += parseElementBody(name, memberLine)
                        else -> fail("Expected ':' or '{' after '$name'", current)
                    }
                }
            }
        }
        expectSymbol("}", "closing '}' for element $type")
        return QmlElement(type = type, id = id, properties = properties, children = children, line = line)
    }

    private fun parsePropertyDeclaration(properties: MutableMap<String, QmlExpression>) {
        while (checkId("readonly") || checkId("required") || checkId("default")) advance()
        matchId("property")
        if (!check(QmlTokenKind.IDENTIFIER)) return

        // Type can be `string`, `alias`, or a generic such as `list<Item>`.
        advance()
        if (matchOperator("<")) {
            var genericDepth = 1
            while (!atEnd && genericDepth > 0) {
                when {
                    matchOperator("<") -> genericDepth++
                    matchOperator(">") -> genericDepth--
                    else -> advance()
                }
            }
        }
        if (!check(QmlTokenKind.IDENTIFIER)) return
        val name = advance().text
        if (matchSymbol(":")) properties[name] = parseValue()
    }

    private fun skipSignalDeclaration() {
        if (check(QmlTokenKind.IDENTIFIER)) advance()
        if (checkSymbol("(")) skipBalanced("(", ")")
        matchSymbol(";")
    }

    private fun skipEnumDeclaration() {
        if (check(QmlTokenKind.IDENTIFIER)) advance()
        if (checkSymbol("{")) skipBalanced("{", "}")
        matchSymbol(";")
    }

    private fun skipFunctionAfterKeyword() {
        if (check(QmlTokenKind.IDENTIFIER)) advance()
        if (checkSymbol("(")) skipBalanced("(", ")")
        if (matchSymbol(":")) while (!atEnd && !checkSymbol("{")) advance()
        if (checkSymbol("{")) skipBalanced("{", "}")
    }

    private fun parseValue(): QmlExpression {
        if (matchId("function")) {
            skipFunctionAfterKeyword()
            return QmlLiteral(QmlFunctionValue)
        }
        if (checkId("if") || checkId("for") || checkId("while") || checkId("switch") || checkId("try")) {
            advance()
            if (checkSymbol("(")) skipBalanced("(", ")")
            skipStatementOrBlock()
            if (matchId("else") || matchId("catch") || matchId("finally")) skipStatementOrBlock()
            return QmlLiteral(null)
        }
        if (checkSymbol("[")) return parseArray()
        if (checkSymbol("{")) {
            skipBalanced("{", "}")
            return QmlLiteral(null)
        }
        if (looksLikeInlineElement()) {
            val line = current.line
            val type = readDottedName()
            expectSymbol("{", "opening '{' for inline element $type")
            return QmlInlineElement(parseElementBody(type, line))
        }
        return parseExpression()
    }

    private fun looksLikeInlineElement(): Boolean {
        if (!check(QmlTokenKind.IDENTIFIER)) return false
        var cursor = position + 1
        while (tokens.getOrNull(cursor)?.let { it.kind == QmlTokenKind.SYMBOL && it.text == "." } == true &&
            tokens.getOrNull(cursor + 1)?.kind == QmlTokenKind.IDENTIFIER
        ) cursor += 2
        return tokens.getOrNull(cursor)?.let { it.kind == QmlTokenKind.SYMBOL && it.text == "{" } == true
    }

    private fun parseArray(): QmlArray {
        expectSymbol("[", "'['")
        val items = mutableListOf<QmlExpression>()
        while (!checkSymbol("]")) {
            items += parseExpression()
            if (!matchSymbol(",")) break
            if (checkSymbol("]")) break
        }
        expectSymbol("]", "']'")
        return QmlArray(items)
    }

    private fun parseExpression(): QmlExpression {
        if (looksLikeArrowParameters()) {
            skipBalanced("(", ")")
            consumeArrowBody()
            return QmlLiteral(QmlFunctionValue)
        }
        val expression = parseTernary()
        if (matchOperator("=>")) {
            consumeArrowBody(afterArrow = true)
            return QmlLiteral(QmlFunctionValue)
        }
        return when {
            matchOperator("=") -> QmlBinary("=", expression, parseExpression())
            matchOperator("+=") -> QmlBinary("+=", expression, parseExpression())
            matchOperator("-=") -> QmlBinary("-=", expression, parseExpression())
            else -> expression
        }
    }

    private fun looksLikeArrowParameters(): Boolean {
        if (!checkSymbol("(")) return false
        var cursor = position
        var depth = 0
        while (cursor < tokens.size) {
            val token = tokens[cursor++]
            if (token.kind == QmlTokenKind.SYMBOL && token.text == "(") depth++
            if (token.kind == QmlTokenKind.SYMBOL && token.text == ")") {
                depth--
                if (depth == 0) break
            }
        }
        return tokens.getOrNull(cursor)?.let { it.kind == QmlTokenKind.OPERATOR && it.text == "=>" } == true
    }

    private fun consumeArrowBody(afterArrow: Boolean = false) {
        if (!afterArrow) expectOperator("=>", "'=>' after arrow parameters")
        if (checkSymbol("{")) skipBalanced("{", "}") else parseExpression()
    }

    private fun parseTernary(): QmlExpression {
        val condition = parseOr()
        if (!matchOperator("?")) return condition
        val thenExpression = parseExpression()
        expectSymbol(":", "':' in ternary expression")
        return QmlTernary(condition, thenExpression, parseExpression())
    }

    private fun parseOr(): QmlExpression = parseLeftAssociative(::parseAnd, setOf("||"))
    private fun parseAnd(): QmlExpression = parseLeftAssociative(::parseEquality, setOf("&&"))
    private fun parseEquality(): QmlExpression = parseLeftAssociative(::parseRelational, setOf("==", "===", "!=", "!=="))
    private fun parseRelational(): QmlExpression = parseLeftAssociative(::parseAdditive, setOf("<", ">", "<=", ">="))
    private fun parseAdditive(): QmlExpression = parseLeftAssociative(::parseMultiplicative, setOf("+", "-"))
    private fun parseMultiplicative(): QmlExpression = parseLeftAssociative(::parseUnary, setOf("*", "/", "%"))

    private fun parseLeftAssociative(next: () -> QmlExpression, operators: Set<String>): QmlExpression {
        var result = next()
        while (current.kind == QmlTokenKind.OPERATOR && current.text in operators) {
            val operator = advance().text
            result = QmlBinary(operator, result, next())
        }
        return result
    }

    private fun parseUnary(): QmlExpression = when {
        matchOperator("!") -> QmlUnary("!", parseUnary())
        matchOperator("-") -> QmlUnary("-", parseUnary())
        matchOperator("+") -> QmlUnary("+", parseUnary())
        else -> parsePrimary()
    }

    private fun parsePrimary(): QmlExpression {
        val token = current
        when (token.kind) {
            QmlTokenKind.STRING, QmlTokenKind.REGEX -> {
                advance()
                return QmlLiteral(token.text)
            }
            QmlTokenKind.NUMBER -> {
                advance()
                val value = if (token.text.startsWith("0x", true)) token.text.substring(2).toLong(16)
                    else token.text.toLongOrNull() ?: token.text.toDouble()
                return QmlLiteral(value)
            }
            QmlTokenKind.IDENTIFIER -> {
                val first = advance().text
                when (first) {
                    "true" -> return QmlLiteral(true)
                    "false" -> return QmlLiteral(false)
                    "null", "undefined" -> return QmlLiteral(null)
                    "new" -> {
                        if (check(QmlTokenKind.IDENTIFIER)) readDottedName()
                        if (checkSymbol("(")) skipBalanced("(", ")")
                        return QmlLiteral(null)
                    }
                }

                val path = mutableListOf(first)
                while (matchSymbol(".")) path += expect(QmlTokenKind.IDENTIFIER, "member name").text
                var result: QmlExpression = if (checkSymbol("(")) QmlCall(path.joinToString("."), parseArguments()) else QmlReference(path)
                return parsePostfix(result)
            }
            QmlTokenKind.SYMBOL -> when (token.text) {
                "(" -> {
                    advance()
                    val expression = parseExpression()
                    expectSymbol(")", "')'")
                    return parsePostfix(expression)
                }
                "{" -> return parseObject()
            }
            else -> Unit
        }
        fail("Unexpected expression '${token.text}'", token)
    }

    private fun parsePostfix(initial: QmlExpression): QmlExpression {
        var result = initial
        while (true) {
            result = when {
                matchSymbol(".") -> {
                    val name = expect(QmlTokenKind.IDENTIFIER, "member name").text
                    if (checkSymbol("(")) QmlMethodCall(result, name, parseArguments()) else QmlMember(result, name)
                }
                matchSymbol("[") -> {
                    val index = parseExpression()
                    expectSymbol("]", "']' after index")
                    QmlIndex(result, index)
                }
                else -> return result
            }
        }
    }

    private fun parseArguments(): List<QmlExpression> {
        expectSymbol("(", "'('")
        val arguments = mutableListOf<QmlExpression>()
        while (!checkSymbol(")")) {
            arguments += parseExpression()
            if (!matchSymbol(",")) break
            if (checkSymbol(")")) break
        }
        expectSymbol(")", "')'")
        return arguments
    }

    private fun parseObject(): QmlObject {
        expectSymbol("{", "'{'")
        val fields = linkedMapOf<String, QmlExpression>()
        while (!checkSymbol("}")) {
            val key = if (check(QmlTokenKind.STRING)) advance().text else readDottedName()
            expectSymbol(":", "':' after object key")
            fields[key] = parseExpression()
            if (!matchSymbol(",")) break
            if (checkSymbol("}")) break
        }
        expectSymbol("}", "'}'")
        return QmlObject(fields)
    }

    private fun skipStatementOrBlock() {
        if (checkSymbol("{")) {
            skipBalanced("{", "}")
            return
        }
        while (!atEnd && !checkSymbol(";") && !checkSymbol("}")) {
            when {
                checkSymbol("(") -> skipBalanced("(", ")")
                checkSymbol("[") -> skipBalanced("[", "]")
                checkSymbol("{") -> skipBalanced("{", "}")
                else -> advance()
            }
        }
        matchSymbol(";")
    }

    private fun skipBalanced(open: String, close: String) {
        expectSymbol(open, "'$open'")
        var depth = 1
        while (!atEnd && depth > 0) {
            when {
                matchSymbol(open) -> depth++
                matchSymbol(close) -> depth--
                else -> advance()
            }
        }
        if (depth != 0) fail("Unterminated '$open' block; missing '$close'", current)
    }

    private fun skipToStatementEnd() {
        while (!atEnd && !checkSymbol(";")) {
            // Pragmas commonly omit semicolons; stop before the next root element.
            if (check(QmlTokenKind.IDENTIFIER) && tokens.getOrNull(position + 1)?.text == "{") break
            advance()
        }
        matchSymbol(";")
    }

    private fun readDottedName(): String {
        val name = StringBuilder(expect(QmlTokenKind.IDENTIFIER, "identifier").text)
        while (checkSymbol(".") && tokens.getOrNull(position + 1)?.kind == QmlTokenKind.IDENTIFIER) {
            advance()
            name.append('.').append(advance().text)
        }
        return name.toString()
    }

    private fun check(kind: QmlTokenKind, text: String? = null): Boolean =
        current.kind == kind && (text == null || current.text == text)

    private fun checkId(value: String): Boolean = check(QmlTokenKind.IDENTIFIER, value)
    private fun checkSymbol(value: String): Boolean = check(QmlTokenKind.SYMBOL, value)
    private fun matchId(value: String): Boolean = checkId(value).also { if (it) advance() }
    private fun matchSymbol(value: String): Boolean = checkSymbol(value).also { if (it) advance() }
    private fun matchOperator(value: String): Boolean = check(QmlTokenKind.OPERATOR, value).also { if (it) advance() }
    private fun expectSymbol(value: String, description: String) = expect(QmlTokenKind.SYMBOL, description, value)
    private fun expectOperator(value: String, description: String) = expect(QmlTokenKind.OPERATOR, description, value)

    private fun expect(kind: QmlTokenKind, description: String, text: String? = null): QmlToken {
        if (!check(kind, text)) fail("Expected $description but found '${current.text}'", current)
        return advance()
    }

    private fun advance(): QmlToken = tokens[position++].also { if (position >= tokens.size) position = tokens.lastIndex }

    private fun fail(message: String, token: QmlToken): Nothing =
        throw QmlParseException("$message (line ${token.line}, column ${token.column})")
}

private class QmlParseException(message: String) : RuntimeException(message)

fun qmlLiteral(value: Any?): QmlExpression = QmlLiteral(value)

fun qmlUrlToPath(url: String): String {
    if (!url.startsWith("file://")) return url
    return try {
        java.net.URLDecoder.decode(url.removePrefix("file://"), Charsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        url.removePrefix("file://")
    }
}
