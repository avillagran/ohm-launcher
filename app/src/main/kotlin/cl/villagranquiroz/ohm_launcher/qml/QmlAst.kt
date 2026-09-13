package cl.villagranquiroz.ohm_launcher.qml

/** A token in the fault-tolerant QML subset understood by OhmLauncher. */
enum class QmlTokenKind { IDENTIFIER, NUMBER, STRING, REGEX, SYMBOL, OPERATOR, EOF }

data class QmlToken(val kind: QmlTokenKind, val text: String, val line: Int, val column: Int)

sealed interface QmlExpression

data class QmlLiteral(val value: Any?) : QmlExpression
data class QmlReference(val path: List<String>) : QmlExpression
data class QmlCall(val name: String, val arguments: List<QmlExpression>) : QmlExpression
data class QmlMember(val target: QmlExpression, val name: String) : QmlExpression
data class QmlIndex(val target: QmlExpression, val index: QmlExpression) : QmlExpression
data class QmlMethodCall(val target: QmlExpression, val name: String, val arguments: List<QmlExpression>) : QmlExpression
data class QmlUnary(val operator: String, val operand: QmlExpression) : QmlExpression
data class QmlBinary(
    val operator: String,
    val left: QmlExpression,
    val right: QmlExpression,
) : QmlExpression

data class QmlTernary(
    val condition: QmlExpression,
    val whenTrue: QmlExpression,
    val whenFalse: QmlExpression,
) : QmlExpression

data class QmlArray(val items: List<QmlExpression>) : QmlExpression
data class QmlObject(val fields: Map<String, QmlExpression>) : QmlExpression
data class QmlInlineElement(val element: QmlElement) : QmlExpression

/** Marker for JavaScript callbacks and blocks that are intentionally not executed. */
data object QmlFunctionValue

data class QmlElement(
    val type: String,
    val baseType: String = type.substringAfterLast('.'),
    val id: String? = null,
    val properties: Map<String, QmlExpression> = emptyMap(),
    val children: List<QmlElement> = emptyList(),
    val line: Int = 1,
)

data class QmlDocument(
    val imports: List<String>,
    val elements: List<QmlElement>,
    val error: String? = null,
)
