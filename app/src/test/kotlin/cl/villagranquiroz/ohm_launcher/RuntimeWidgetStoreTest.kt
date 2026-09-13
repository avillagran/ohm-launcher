package cl.villagranquiroz.ohm_launcher

import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeWidgetStoreTest {
    @Test
    fun appendsQmlObjectsAndJsonArrays() {
        val file = Files.createTempDirectory("ohm-runtime").resolve("runtime_widgets.json").toFile()
        val store = RuntimeWidgetStore(file)

        store.append("Item { Text { text: \"Hi\" } }", "qml")
        store.append("[{\"type\":\"text\",\"value\":\"A\"},{\"type\":\"battery\"}]", "json")

        val nodes = store.load()
        assertEquals(3, nodes.size)
        assertEquals("qml", nodes[0].getString("type"))
        assertEquals("A", nodes[1].getString("value"))
        assertEquals("battery", nodes[2].getString("type"))
        assertEquals(3, org.json.JSONArray(file.readText()).length())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedFormat() {
        val file = Files.createTempFile("ohm-runtime", ".json").toFile()
        RuntimeWidgetStore(file).append(JSONObject().toString(), "xml")
    }
}
