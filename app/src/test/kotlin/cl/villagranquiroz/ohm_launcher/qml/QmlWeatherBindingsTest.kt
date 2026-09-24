package cl.villagranquiroz.ohm_launcher.qml

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QmlWeatherBindingsTest {
    private val weatherSource = File("src/main/assets/plugins/io.github.ohm.demo.weather/BarWidget.qml")
    private val weatherIcon = String(Character.toChars(0xF0594))

    @Test
    fun weatherPluginQmlParsesWithoutErrors() {
        val document = QmlParser(weatherSource.readText()).parse()

        assertEquals(null, document.error)
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

    private fun renderTexts(source: String, bindings: Map<String, Any?>): List<String> {
        val model = QmlRenderModelConverter.withBindings(bindings)
            .convert(QmlParser(source).parse().elements.single())
            ?: error("weather widget must convert to a render model")
        val texts = mutableListOf<String>()
        fun walk(node: QmlRenderNode) {
            if (node.type == QmlVisualType.TEXT) texts.add(node.text.orEmpty())
            node.children.forEach(::walk)
        }
        walk(model)
        return texts
    }
}
