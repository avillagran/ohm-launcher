package cl.villagranquiroz.ohm_launcher.qml

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QmlCompatibilityTest {
    private val nativeRoot = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { it.resolve("settings.gradle.kts").isFile }
    private val flutterRoot = nativeRoot.parentFile.resolve("ohm-launcher-flutter")

    @Test
    fun parsesEveryFlutterQmlFixture() {
        val fixtures = flutterRoot.resolve("test/fixtures").listFiles { file -> file.extension == "qml" }
            ?.sortedBy(File::getName)
            .orEmpty()

        assertEquals("Expected the five Flutter reference fixtures", 5, fixtures.size)
        assertQmlFilesParse(fixtures)
    }

    @Test
    fun parsesQmlReferencedByRealPluginManifests() {
        val pluginFiles = listOf(
            "examples/plugins/io.github.ohm.demo.weather/BarWidget.qml",
            "omarchy-link/BarWidget.qml",
            "omarchy-link/Panel.qml",
            "omarchy-link/Translation.qml",
            "omarchy-link/AndroidIcon.qml",
            "omarchy-link/examples/minimal/BarWidget.qml",
            "omarchy-link/examples/minimal/Panel.qml",
        ).map(flutterRoot::resolve)

        assertTrue("Flutter reference checkout is missing", flutterRoot.isDirectory)
        assertTrue("A manifest-referenced QML file is missing", pluginFiles.all(File::isFile))
        assertQmlFilesParse(pluginFiles)
    }

    @Test
    fun evaluatesBindingsFromTheWeatherPlugin() {
        val source = flutterRoot.resolve("test/fixtures/ohm_weather_widget.qml").readText()
        val root = QmlParser(source).parse().elements.single()
        val runtime = QmlRuntime()
        val scope = runtime.scopeFor(root)
        val temperatureText = root.descendants().first {
            (it.properties["text"] as? QmlBinary)?.operator == "+"
        }

        assertEquals("18°", runtime.evaluate(temperatureText.properties.getValue("text"), scope))
    }

    private fun assertQmlFilesParse(files: List<File>) {
        files.forEach { file ->
            val document = QmlParser(file.readText()).parse()
            assertNull("${file.relativeTo(flutterRoot)}: ${document.error}", document.error)
            assertTrue("${file.relativeTo(flutterRoot)} has no root element", document.elements.isNotEmpty())
        }
    }

    private fun QmlElement.descendants(): Sequence<QmlElement> = sequence {
        yield(this@descendants)
        children.forEach { yieldAll(it.descendants()) }
        properties.values.filterIsInstance<QmlInlineElement>().forEach { yieldAll(it.element.descendants()) }
    }
}
