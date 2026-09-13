package cl.villagranquiroz.ohm_launcher.qml

object QmlTokenizer {
    private val threeCharacterOperators = setOf("===", "!==")
    private val twoCharacterOperators = setOf("==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=", "=>")
    private val symbols = setOf('{', '}', '(', ')', '[', ']', ',', ':', ';', '.')
    private val oneCharacterOperators = setOf('+', '-', '*', '/', '%', '<', '>', '=', '!', '?', '&', '|')

    fun tokenize(source: String): List<QmlToken> {
        val result = mutableListOf<QmlToken>()
        var index = 0
        var line = 1
        var column = 1

        fun advance(): Char {
            val char = source[index++]
            if (char == '\n') {
                line++
                column = 1
            } else {
                column++
            }
            return char
        }

        fun add(kind: QmlTokenKind, text: String, tokenLine: Int, tokenColumn: Int) {
            result += QmlToken(kind, text, tokenLine, tokenColumn)
        }

        while (index < source.length) {
            val char = source[index]
            if (char.isWhitespace()) {
                advance()
                continue
            }

            if (char == '/' && source.getOrNull(index + 1) == '/') {
                while (index < source.length && source[index] != '\n') advance()
                continue
            }
            if (char == '/' && source.getOrNull(index + 1) == '*') {
                advance()
                advance()
                while (index < source.length && !(source[index] == '*' && source.getOrNull(index + 1) == '/')) advance()
                if (index < source.length) {
                    advance()
                    if (index < source.length) advance()
                }
                continue
            }

            val tokenLine = line
            val tokenColumn = column
            if (char == '/' && isOperandPosition(result)) {
                val start = index
                advance()
                var inCharacterClass = false
                while (index < source.length) {
                    when (source[index]) {
                        '\\' -> {
                            advance()
                            if (index < source.length) advance()
                        }
                        '[' -> {
                            inCharacterClass = true
                            advance()
                        }
                        ']' -> {
                            inCharacterClass = false
                            advance()
                        }
                        '/' -> {
                            advance()
                            if (!inCharacterClass) break
                        }
                        else -> advance()
                    }
                }
                while (index < source.length && isIdentifierPart(source[index])) advance()
                add(QmlTokenKind.REGEX, source.substring(start, index), tokenLine, tokenColumn)
                continue
            }

            if (isIdentifierStart(char)) {
                val start = index
                advance()
                while (index < source.length && isIdentifierPart(source[index])) advance()
                add(QmlTokenKind.IDENTIFIER, source.substring(start, index), tokenLine, tokenColumn)
                continue
            }

            if (char.isDigit() || (char == '.' && source.getOrNull(index + 1)?.isDigit() == true)) {
                val start = index
                if (char == '0' && source.getOrNull(index + 1) in setOf('x', 'X')) {
                    advance()
                    advance()
                    while (index < source.length && source[index].isHexDigit()) advance()
                } else {
                    while (index < source.length && source[index].isDigit()) advance()
                    if (source.getOrNull(index) == '.') {
                        advance()
                        while (index < source.length && source[index].isDigit()) advance()
                    }
                    if (source.getOrNull(index) == 'e' || source.getOrNull(index) == 'E') {
                        advance()
                        if (source.getOrNull(index) == '+' || source.getOrNull(index) == '-') advance()
                        while (index < source.length && source[index].isDigit()) advance()
                    }
                }
                add(QmlTokenKind.NUMBER, source.substring(start, index), tokenLine, tokenColumn)
                continue
            }

            if (char == '"' || char == '\'') {
                val quote = advance()
                val value = StringBuilder()
                while (index < source.length && source[index] != quote) {
                    if (source[index] == '\\' && index + 1 < source.length) {
                        advance()
                        val escape = advance()
                        when (escape) {
                            'n' -> value.append('\n')
                            'r' -> value.append('\r')
                            't' -> value.append('\t')
                            'b' -> value.append('\b')
                            'f' -> value.append('\u000c')
                            'v' -> value.append('\u000b')
                            '0' -> value.append('\u0000')
                            'u' -> {
                                val digits = source.substring(index, (index + 4).coerceAtMost(source.length))
                                val decoded = digits.takeIf { it.length == 4 }?.toIntOrNull(16)
                                if (decoded == null) value.append('u') else {
                                    value.append(decoded.toChar())
                                    repeat(4) { advance() }
                                }
                            }
                            'x' -> {
                                val digits = source.substring(index, (index + 2).coerceAtMost(source.length))
                                val decoded = digits.takeIf { it.length == 2 }?.toIntOrNull(16)
                                if (decoded == null) value.append('x') else {
                                    value.append(decoded.toChar())
                                    repeat(2) { advance() }
                                }
                            }
                            else -> value.append(escape)
                        }
                    } else {
                        value.append(advance())
                    }
                }
                if (index < source.length) advance()
                add(QmlTokenKind.STRING, value.toString(), tokenLine, tokenColumn)
                continue
            }

            val three = source.substring(index, (index + 3).coerceAtMost(source.length))
            if (three in threeCharacterOperators) {
                repeat(3) { advance() }
                add(QmlTokenKind.OPERATOR, three, tokenLine, tokenColumn)
                continue
            }
            val two = source.substring(index, (index + 2).coerceAtMost(source.length))
            if (two in twoCharacterOperators) {
                repeat(2) { advance() }
                add(QmlTokenKind.OPERATOR, two, tokenLine, tokenColumn)
                continue
            }
            if (char in symbols) {
                advance()
                add(QmlTokenKind.SYMBOL, char.toString(), tokenLine, tokenColumn)
                continue
            }
            if (char in oneCharacterOperators) {
                advance()
                add(QmlTokenKind.OPERATOR, char.toString(), tokenLine, tokenColumn)
                continue
            }

            // Unknown JavaScript/QML punctuation is harmless to the structural parser.
            advance()
        }
        result += QmlToken(QmlTokenKind.EOF, "", line, column)
        return result
    }

    private fun isIdentifierStart(char: Char): Boolean = char.isLetter() || char == '_' || char == '$'
    private fun isIdentifierPart(char: Char): Boolean = char.isLetterOrDigit() || char == '_' || char == '$'
    private fun Char.isHexDigit(): Boolean = isDigit() || lowercaseChar() in 'a'..'f'

    private fun isOperandPosition(tokens: List<QmlToken>): Boolean {
        val last = tokens.lastOrNull() ?: return true
        return when (last.kind) {
            QmlTokenKind.OPERATOR -> true
            QmlTokenKind.SYMBOL -> last.text in setOf("(", "[", "{", ",", ":", ";")
            else -> false
        }
    }
}
