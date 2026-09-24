package cl.villagranquiroz.ohm_launcher.qml

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QmlWeatherBindingsTest {
    private val weatherSource = File("src/main/assets/plugins/io.github.ohm.demo.weather/BarWidget.qml")
    private val weatherIcon = String(Character.toChars(0xF0594))

    @Test
    fun weatherPluginQmlParsesWithoutErrors() {
        val document = QmlParser(weatherSource.readText()).parse()

        assertNull("Weather QML parse error: ${document.error}", document.error)
        assertTrue(document.elements.isNotEmpty())
    }

    @Test
    fun boundWeatherValuesOverrideTheStaticDefaults() {
        val bindings = mapOf(
            "Weather" to mapOf(
                "city" to "Valparaíso",
                "tempText" to "19°",
                "condition" to "Rain",
                "updated" to "12:34",
                "unit" to "C",
            ),
        )

        val texts = renderTexts(weatherSource.readText(), bindings)

        assertEquals(listOf(weatherIcon, "Valparaíso", "19°", "Rain", "act. 12:34"), texts.take(5))
    }

    @Test
    fun missingWeatherBindingFallsBackToStaticDefaults() {
        val texts = renderTexts(weatherSource.readText(), emptyMap())

        assertEquals(listOf(weatherIcon, "Santiago", "18°", "Despejado"), texts.take(4))
        assertTrue(texts[4].startsWith("act. "))
    }

    @Test
    fun bindingNamesAreVisibleInsideNestedScopes() {
        val source = """
            BarWidget {
              id: root
              readonly property string ciudad: Weather.city
              Column {
                Text { text: root.ciudad }
                Text { text: Weather.city }
              }
            }
        """.trimIndent()

        val model = QmlRenderModelConverter.withBindings(mapOf("Weather" to mapOf("city" to "Oslo"))).convert(
            QmlParser(source).parse().elements.single(),
        )

        val texts = model!!.children.single().children.mapNotNull(QmlRenderNode::text)
        assertEquals(listOf("Oslo", "Oslo"), texts)
    }

    @Test
    fun usesThemeColorsAndRendersNerdFontForecasts() {
        val rainIcon = ""
        val source = weatherSource.readText()
        val bindings = mapOf(
            "Weather" to mapOf(
                "unit" to "C",
                "city" to "Santiago",
                "tempText" to "18°",
                "condition" to "Rain",
                "updated" to "12:34",
                "icon" to rainIcon,
                "forecast0" to mapOf("day" to "FRI", "icon" to "", "high" to "20°", "low" to "9°"),
                "forecast1" to mapOf("day" to "SAT", "icon" to "", "high" to "21°", "low" to "10°"),
                "forecast2" to mapOf("day" to "SUN", "icon" to "", "high" to "22°", "low" to "11°"),
            ),
            "Color" to mapOf(
                "foreground" to "#FFF1F5F9",
                "muted" to "#FF94A3B8",
                "accent" to "#FF66E0FF",
                "surface" to "#FF151D26",
            ),
        )
        val nodes = renderNodes(source, bindings)
        val texts = nodes.mapNotNull(QmlRenderNode::text)

        assertTrue(texts.containsAll(listOf(rainIcon, "FRI", "SAT", "SUN", "20°", "9°", "21°", "10°", "22°", "11°")))
        assertTrue(texts.filter { QmlTextTypefacePolicy.requiresIconFont(it) }.containsAll(listOf(rainIcon, "", "", "")))
        assertEquals(QmlColor(0xFFF1F5F9.toInt()), nodes.first { it.text == "Santiago" }.style.foregroundColor)
        assertEquals(QmlColor(0xFF66E0FF.toInt()), nodes.first { it.text == rainIcon }.style.foregroundColor)
        assertEquals(QmlColor(0xFF94A3B8.toInt()), nodes.first { it.text == "FRI" }.style.foregroundColor)
    }

    private fun renderTexts(source: String, bindings: Map<String, Any?>): List<String> {
        return renderNodes(source, bindings).mapNotNull(QmlRenderNode::text)
    }

    private fun renderNodes(source: String, bindings: Map<String, Any?>): List<QmlRenderNode> {
        val model = QmlRenderModelConverter.withBindings(bindings)
            .convert(QmlParser(source).parse().elements.single())
            ?: error("weather widget must convert to a render model")
        val nodes = mutableListOf<QmlRenderNode>()
        fun walk(node: QmlRenderNode) {
            if (node.type == QmlVisualType.TEXT) nodes.add(node)
            node.children.forEach(::walk)
        }
        walk(model)
        return nodes
    }
}
