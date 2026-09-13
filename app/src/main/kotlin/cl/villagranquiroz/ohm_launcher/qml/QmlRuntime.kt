package cl.villagranquiroz.ohm_launcher.qml

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

class QmlScope(val parent: QmlScope? = null) {
    val values: MutableMap<String, Any?> = linkedMapOf()

    fun define(name: String, value: Any?): QmlScope = apply { values[name] = value }

    fun lookup(name: String): Any? = if (values.containsKey(name)) values[name] else parent?.lookup(name)
}

data class QmlClockValue(val date: Date)

class QmlElementValue internal constructor(
    val element: QmlElement,
    private val scope: QmlScope,
    private val runtime: QmlRuntime,
) {
    private val evaluating = mutableSetOf<String>()

    fun getProperty(name: String, depth: Int = 0): Any? {
        if (depth > QmlRuntime.MAX_DEPTH || !evaluating.add(name)) return null
        return try {
            element.properties[name]?.let { runtime.evaluate(it, scope, depth + 1) }
        } catch (_: RuntimeException) {
            null
        } finally {
            evaluating.remove(name)
        }
    }
}

class QmlRuntime(
    private val clock: () -> Date = { Date() },
    private val locale: Locale = Locale.getDefault(),
    private val timeZone: TimeZone = TimeZone.getDefault(),
) {
    fun scopeFor(root: QmlElement, parent: QmlScope? = null): QmlScope {
        val scope = QmlScope(parent)
        val rootValue = QmlElementValue(root, scope, this)
        scope.define("root", rootValue)
        root.id?.let { scope.define(it, rootValue) }

        fun register(element: QmlElement) {
            element.id?.let { id ->
                val value = if (element.baseType == "SystemClock") QmlClockValue(clock())
                    else QmlElementValue(element, scope, this)
                scope.define(id, value)
            }
            element.children.forEach(::register)
            element.properties.values.filterIsInstance<QmlInlineElement>().forEach { register(it.element) }
        }
        root.children.forEach(::register)
        return scope
    }

    fun evaluate(expression: QmlExpression, scope: QmlScope, depth: Int = 0): Any? {
        if (depth > MAX_DEPTH) return null
        return try {
            when (expression) {
                is QmlLiteral -> expression.value
                is QmlReference -> resolveReference(expression.path, scope, depth)
                is QmlMember -> getProperty(evaluate(expression.target, scope, depth + 1), expression.name, depth + 1)
                is QmlIndex -> getIndex(
                    evaluate(expression.target, scope, depth + 1),
                    evaluate(expression.index, scope, depth + 1),
                )
                is QmlCall -> call(expression.name, expression.arguments.map { evaluate(it, scope, depth + 1) })
                is QmlMethodCall -> callMethod(
                    evaluate(expression.target, scope, depth + 1),
                    expression.name,
                    expression.arguments.map { evaluate(it, scope, depth + 1) },
                )
                is QmlUnary -> unary(expression.operator, evaluate(expression.operand, scope, depth + 1))
                is QmlBinary -> evaluateBinary(expression, scope, depth)
                is QmlTernary -> if (truthy(evaluate(expression.condition, scope, depth + 1))) {
                    evaluate(expression.whenTrue, scope, depth + 1)
                } else {
                    evaluate(expression.whenFalse, scope, depth + 1)
                }
                is QmlArray -> expression.items.map { evaluate(it, scope, depth + 1) }
                is QmlObject -> expression.fields.mapValues { evaluate(it.value, scope, depth + 1) }
                is QmlInlineElement -> null
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble() != 0.0 && !value.toDouble().isNaN()
        is String -> value.isNotEmpty()
        is Collection<*> -> value.isNotEmpty()
        is Map<*, *> -> true
        else -> true
    }

    private fun evaluateBinary(expression: QmlBinary, scope: QmlScope, depth: Int): Any? {
        val left = evaluate(expression.left, scope, depth + 1)
        if (expression.operator == "&&") return if (truthy(left)) evaluate(expression.right, scope, depth + 1) else left
        if (expression.operator == "||") return if (truthy(left)) left else evaluate(expression.right, scope, depth + 1)
        val right = evaluate(expression.right, scope, depth + 1)
        return binary(expression.operator, left, right)
    }

    private fun resolveReference(path: List<String>, scope: QmlScope, depth: Int): Any? {
        if (path.isEmpty()) return null
        if (path.first() == "parent") return null
        var value: Any? = scope.lookup(path.first())
        if (value == null && path.first().firstOrNull()?.isUpperCase() == true) value = path.first()
        path.drop(1).forEach { part -> value = getProperty(value, part, depth + 1) }
        return value
    }

    private fun getProperty(value: Any?, name: String, depth: Int): Any? = when (value) {
        is QmlElementValue -> value.getProperty(name, depth)
        is QmlClockValue -> if (name == "date") value.date else null
        is Map<*, *> -> value[name]
        is List<*> -> when (name) {
            "length", "count" -> value.size.toLong()
            else -> null
        }
        is String -> when (name) {
            "length" -> value.length.toLong()
            else -> if (value.firstOrNull()?.isUpperCase() == true) "$value.$name" else null
        }
        is Date -> when (name) {
            "time" -> value.time
            else -> null
        }
        is StringBuilder -> if (name == "length") value.length.toLong() else null
        else -> null
    }

    private fun getIndex(target: Any?, index: Any?): Any? = when (target) {
        is List<*> -> number(index)?.toInt()?.let(target::getOrNull)
        is String -> number(index)?.toInt()?.takeIf { it in target.indices }?.let { target[it].toString() }
        is Map<*, *> -> target[index]
        else -> null
    }

    private fun unary(operator: String, value: Any?): Any? = when (operator) {
        "!" -> !truthy(value)
        "-" -> number(value)?.let { -it }
        "+" -> number(value)
        else -> value
    }

    private fun binary(operator: String, left: Any?, right: Any?): Any? {
        if (operator in setOf("=", "+=", "-=")) return right
        if (operator == "+") {
            val leftNumber = left as? Number
            val rightNumber = right as? Number
            return if (leftNumber != null && rightNumber != null) normalizeNumber(leftNumber.toDouble() + rightNumber.toDouble())
                else jsString(left) + jsString(right)
        }
        val leftNumber = number(left)
        val rightNumber = number(right)
        return when (operator) {
            "-" -> numericResult(leftNumber, rightNumber) { a, b -> a - b }
            "*" -> numericResult(leftNumber, rightNumber) { a, b -> a * b }
            "/" -> if (leftNumber == null || rightNumber == null) null else leftNumber / rightNumber
            "%" -> numericResult(leftNumber, rightNumber) { a, b -> a % b }
            "<" -> compare(left, right, leftNumber, rightNumber) < 0
            ">" -> compare(left, right, leftNumber, rightNumber) > 0
            "<=" -> compare(left, right, leftNumber, rightNumber) <= 0
            ">=" -> compare(left, right, leftNumber, rightNumber) >= 0
            "==", "===" -> equal(left, right)
            "!=", "!==" -> !equal(left, right)
            else -> null
        }
    }

    private fun call(name: String, arguments: List<Any?>): Any? = when (name) {
        "Qt.formatTime" -> format(arguments, "HH:mm")
        "Qt.formatDateTime" -> format(arguments, "yyyy-MM-dd HH:mm:ss")
        "Qt.formatDate" -> format(arguments, "yyyy-MM-dd")
        "Qt.resolvedUrl" -> arguments.firstOrNull()?.toString().orEmpty()
        "Qt.locale" -> mapOf("name" to locale.toString(), "firstDayOfWeek" to firstDayOfWeek())
        "Qt.point" -> mapOf("x" to arguments.getOrNull(0), "y" to arguments.getOrNull(1))
        "Qt.size" -> mapOf("width" to arguments.getOrNull(0), "height" to arguments.getOrNull(1))
        "Qt.rect" -> mapOf(
            "x" to arguments.getOrNull(0), "y" to arguments.getOrNull(1),
            "width" to arguments.getOrNull(2), "height" to arguments.getOrNull(3),
        )
        "Math.round" -> number(arguments.firstOrNull())?.let { round(it).toLong() }
        "Math.floor" -> number(arguments.firstOrNull())?.let { floor(it).toLong() }
        "Math.ceil" -> number(arguments.firstOrNull())?.let { ceil(it).toLong() }
        "Math.abs" -> number(arguments.firstOrNull())?.let(::abs)?.let(::normalizeNumber)
        "Math.min" -> arguments.mapNotNull(::number).reduceOrNull(::min)?.let(::normalizeNumber)
        "Math.max" -> arguments.mapNotNull(::number).reduceOrNull(::max)?.let(::normalizeNumber)
        "Style.space" -> number(arguments.firstOrNull() ?: 16)?.toDouble()
        "String" -> arguments.firstOrNull()?.let(::jsString)
        "Number", "parseInt" -> number(arguments.firstOrNull())?.toLong()
        "parseFloat" -> number(arguments.firstOrNull())
        "Array.isArray" -> arguments.firstOrNull() is List<*>
        else -> null
    }

    private fun callMethod(target: Any?, name: String, arguments: List<Any?>): Any? = when (name) {
        "toString" -> target?.toString()
        "trim" -> (target as? String)?.trim()
        "toLowerCase" -> (target as? String)?.lowercase(locale)
        "toUpperCase" -> (target as? String)?.uppercase(locale)
        "slice" -> when (target) {
            is String -> target.substring(arguments.getOrNull(0).asIndex(target.length), arguments.getOrNull(1).asIndex(target.length, target.length))
            is List<*> -> target.subList(arguments.getOrNull(0).asIndex(target.size), arguments.getOrNull(1).asIndex(target.size, target.size))
            else -> null
        }
        "replace" -> (target as? String)?.replace(arguments.getOrNull(0)?.toString().orEmpty(), arguments.getOrNull(1)?.toString().orEmpty())
        "concat" -> (target as? List<*>)?.plus(arguments.flatMap { if (it is List<*>) it else listOf(it) })
        "getFullYear" -> (target as? Date)?.let { formatDate(it, "yyyy").toLong() }
        "getMonth" -> (target as? Date)?.let { formatDate(it, "M").toLong() - 1 }
        "getDate" -> (target as? Date)?.let { formatDate(it, "d").toLong() }
        else -> null
    }

    private fun format(arguments: List<Any?>, defaultPattern: String): String? {
        val value = arguments.firstOrNull()
        val date = when (value) {
            is Date -> value
            is QmlClockValue -> value.date
            else -> return null
        }
        val pattern = arguments.getOrNull(1) as? String ?: defaultPattern
        return formatDate(date, pattern)
    }

    private fun formatDate(date: Date, pattern: String): String =
        SimpleDateFormat(pattern, locale).apply { timeZone = this@QmlRuntime.timeZone }.format(date)

    private fun firstDayOfWeek(): Long = java.util.Calendar.getInstance(timeZone, locale).firstDayOfWeek.toLong()

    private fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is Boolean -> if (value) 1.0 else 0.0
        is String -> value.trim().toDoubleOrNull()
        else -> null
    }

    private fun numericResult(a: Double?, b: Double?, operation: (Double, Double) -> Double): Any? =
        if (a == null || b == null) null else normalizeNumber(operation(a, b))

    private fun normalizeNumber(value: Double): Number = if (value.isFinite() && value % 1.0 == 0.0) value.toLong() else value

    private fun compare(left: Any?, right: Any?, a: Double?, b: Double?): Int =
        if (a != null && b != null) a.compareTo(b) else jsString(left).compareTo(jsString(right))

    private fun equal(left: Any?, right: Any?): Boolean =
        if (left is Number && right is Number) left.toDouble() == right.toDouble() else left == right

    private fun jsString(value: Any?): String = when (value) {
        null -> "null"
        is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
        else -> value.toString()
    }

    private fun Any?.asIndex(size: Int, default: Int = 0): Int =
        (number(this)?.toInt() ?: default).let { if (it < 0) (size + it).coerceAtLeast(0) else it.coerceIn(0, size) }

    companion object {
        internal const val MAX_DEPTH = 24
    }
}

fun qmlEval(expression: QmlExpression, scope: QmlScope, depth: Int = 0): Any? =
    QmlRuntime().evaluate(expression, scope, depth)
