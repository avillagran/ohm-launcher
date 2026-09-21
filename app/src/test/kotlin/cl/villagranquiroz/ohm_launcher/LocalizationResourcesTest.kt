package cl.villagranquiroz.ohm_launcher

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalizationResourcesTest {
    @Test
    fun systemThemePreferenceIsLocalizedEverywhere() {
        val locales = listOf(
            "values",
            "values-ar",
            "values-de",
            "values-es",
            "values-fr",
            "values-hi",
            "values-in",
            "values-it",
            "values-ja",
            "values-ko",
            "values-nl",
            "values-pl",
            "values-pt-rBR",
            "values-ru",
            "values-sv",
            "values-tr",
            "values-uk",
            "values-zh-rCN",
            "values-zh-rTW",
        )
        val resources = locales.associateWith { stringResources("src/main/res/$it/strings.xml") }

        resources.forEach { (locale, strings) ->
            assertEquals("$locale must define setting_apply_omarchy_system_theme", true, strings.containsKey("setting_apply_omarchy_system_theme"))
            assertEquals("$locale must define setting_apply_omarchy_system_theme_summary", true, strings.containsKey("setting_apply_omarchy_system_theme_summary"))
            assertEquals(
                "$locale system theme label must not use format placeholders",
                strings.getValue("setting_apply_omarchy_system_theme"),
                strings.getValue("setting_apply_omarchy_system_theme").replace(Regex("%\\d?\\$?[sdfl]"), ""),
            )
        }

        val defaults = resources.getValue("values")
        assertEquals("Apply Omarchy colors to Android", defaults.getValue("setting_apply_omarchy_system_theme"))
        assertEquals(
            "Only compatible Material You surfaces and apps follow these colors.",
            defaults.getValue("setting_apply_omarchy_system_theme_summary"),
        )
        assertEquals("Aplicar colores Omarchy a Android", resources.getValue("values-es").getValue("setting_apply_omarchy_system_theme"))
    }

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
