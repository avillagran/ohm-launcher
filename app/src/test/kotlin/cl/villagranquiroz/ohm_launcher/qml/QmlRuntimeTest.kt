package cl.villagranquiroz.ohm_launcher.qml

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QmlRuntimeTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val instant = GregorianCalendar(utc, Locale.US).apply {
        set(2026, Calendar.SEPTEMBER, 12, 14, 5, 9)
        set(Calendar.MILLISECOND, 0)
    }.time
    private val runtime = QmlRuntime(clock = { instant }, locale = Locale.US, timeZone = utc)

    @Test
    fun evaluatesReferencesBindingsAndBuiltins() {
        val root = QmlElement(
            type = "Item",
            id = "root",
            properties = mapOf(
                "name" to QmlLiteral("Ohm"),
                "label" to QmlBinary("+", QmlMember(QmlReference(listOf("root")), "name"), QmlLiteral(" Launcher")),
            ),
            children = listOf(QmlElement(type = "SystemClock", id = "clock")),
        )
        val scope = runtime.scopeFor(root)

        assertEquals("Ohm Launcher", runtime.evaluate(root.properties.getValue("label"), scope))
        assertEquals(
            "14:05",
            runtime.evaluate(
                QmlCall(
                    "Qt.formatTime",
                    listOf(QmlMember(QmlReference(listOf("clock")), "date"), QmlLiteral("HH:mm")),
                ),
                scope,
            ),
        )
        assertEquals(24.0, runtime.evaluate(QmlCall("Style.space", listOf(QmlLiteral(24))), scope))
        assertEquals(3L, runtime.evaluate(QmlCall("Math.floor", listOf(QmlLiteral(3.9))), scope))
    }

    @Test
    fun evaluatesCollectionsOperatorsAndTernaries() {
        val expression = QmlTernary(
            QmlBinary(">", QmlLiteral(5), QmlLiteral(2)),
            QmlIndex(QmlArray(listOf(QmlLiteral("no"), QmlLiteral("yes"))), QmlLiteral(1)),
            QmlLiteral("never"),
        )

        assertEquals("yes", runtime.evaluate(expression, QmlScope()))
        assertEquals(false, runtime.evaluate(QmlUnary("!", QmlLiteral("value")), QmlScope()))
        assertEquals(2L, runtime.evaluate(QmlMember(QmlLiteral(listOf(1, 2)), "length"), QmlScope()))
        assertTrue(runtime.evaluate(QmlBinary("&&", QmlLiteral(true), QmlLiteral(7)), QmlScope()) == 7)
        assertEquals("fallback", runtime.evaluate(QmlBinary("||", QmlLiteral(""), QmlLiteral("fallback")), QmlScope()))
    }

    @Test
    fun circularBindingsAndUnsupportedCallsDegradeToNull() {
        val root = QmlElement(
            type = "Item",
            id = "root",
            properties = mapOf("loop" to QmlMember(QmlReference(listOf("root")), "loop")),
        )
        val scope = runtime.scopeFor(root)

        assertNull(runtime.evaluate(root.properties.getValue("loop"), scope))
        assertNull(runtime.evaluate(QmlCall("Unknown.call", emptyList()), scope))
        assertFalse(runtime.truthy(null))
    }
}
