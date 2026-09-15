package cl.villagranquiroz.ohm_launcher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizationResourcesTest {
    @Test
    fun spanishResourcesCoverEveryDefaultString() {
        val defaults = stringResources("src/main/res/values/strings.xml")
        val spanish = stringResources("src/main/res/values-es/strings.xml")

        assertEquals(defaults.keys, spanish.keys)
        assertEquals("Desktops", defaults.getValue("menu_desktops"))
        assertEquals("Escritorios", spanish.getValue("menu_desktops"))
        assertEquals("New box", defaults.getValue("add_box_title"))
        assertEquals("Nueva caja", spanish.getValue("add_box_title"))
        assertEquals(
            "TTFX %1\$s; size %2\$d; resolution %3\$d; speed %4\$.1f",
            defaults.getValue("ttfx_accessibility"),
        )
    }

    private fun stringResources(path: String): Map<String, String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index)
                put(element.attributes.getNamedItem("name").nodeValue, element.textContent)
            }
        }
    }
}
