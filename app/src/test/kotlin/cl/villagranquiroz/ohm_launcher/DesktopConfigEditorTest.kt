package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopConfigEditorTest {
    @Test
    fun insertsDesktopWithCopiedWidgetListAndPreservesRootData() {
        val source = """{"future":7,"desktops":[{"name":"One","widgets":[{"type":"clock","x":2}]}]}"""

        val updated = DesktopConfigEditor.insertDesktop(source, insertIndex = 1, templateIndex = 0)
        val root = JSONObject(updated)
        val desktops = root.getJSONArray("desktops")

        assertEquals(7, root.getInt("future"))
        assertEquals(2, desktops.length())
        assertEquals("Escritorio 2", desktops.getJSONObject(1).getString("name"))
        assertEquals(2, desktops.getJSONObject(1).getJSONArray("widgets").getJSONObject(0).getInt("x"))
    }

    @Test
    fun deletesDesktopButNeverTheLastOne() {
        val source = """{"desktops":[{"name":"One","widgets":[]},{"name":"Two","widgets":[]}]}"""
        val updated = DesktopConfigEditor.deleteDesktop(source, 0)
        assertEquals("Two", JSONObject(updated).getJSONArray("desktops").getJSONObject(0).getString("name"))

        val error = runCatching { DesktopConfigEditor.deleteDesktop(updated, 0) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun movesEdgeBoxAndNormalizesDirectionForTheTargetEdge() {
        val source = """{"edgeBoxes":[{"id":"tools","edge":"top","direction":"grid","future":true}],"desktops":[{"widgets":[]}]}"""

        val left = DesktopConfigEditor.moveEdgeBox(source, "tools", EdgePosition.LEFT)
        val leftBox = JSONObject(left).getJSONArray("edgeBoxes").getJSONObject(0)
        assertEquals("left", leftBox.getString("edge"))
        assertEquals("vertical", leftBox.getString("direction"))
        assertTrue(leftBox.getBoolean("future"))

        val bottom = DesktopConfigEditor.moveEdgeBox(left, "tools", EdgePosition.BOTTOM)
        assertEquals(
            "horizontal",
            JSONObject(bottom).getJSONArray("edgeBoxes").getJSONObject(0).getString("direction"),
        )
    }

    @Test
    fun updatesTtfxFieldsWithoutDroppingUnknownDesktopData() {
        val source = """{"futureRoot":{"keep":true},"desktops":[{"name":"Inicio","custom":"keep","widgets":[{"type":"clock"}]}]}"""
        val settings = TtfxConfig.parse(
            JSONObject(
                """{"ttfxEffect":"beams","ttfxText":"Omarchy","ttfxTextSize":7,"ttfxResolution":6}""",
            ),
        )

        val updated = DesktopConfigEditor.updateTtfx(source, 0, settings)
        val desktop = JSONObject(updated).getJSONArray("desktops").getJSONObject(0)

        assertEquals("keep", desktop.getString("custom"))
        assertEquals("clock", desktop.getJSONArray("widgets").getJSONObject(0).getString("type"))
        assertEquals("beams", desktop.getString("ttfxEffect"))
        assertEquals(7, desktop.getInt("ttfxTextSize"))
        assertTrue(desktop.getBoolean("ttfxBackground"))
        assertTrue(JSONObject(updated).getJSONObject("futureRoot").getBoolean("keep"))
    }

    @Test
    fun appendsSystemWidgetWithoutDroppingExistingWidgets() {
        val source = """{"desktops":[{"widgets":[{"type":"clock"}]}]}"""
        val widget = JSONObject()
            .put("type", "system_widget")
            .put("provider", "com.example/.ClockWidget")
            .put("appWidgetId", 42)

        val updated = DesktopConfigEditor.appendWidget(source, 0, widget)
        val widgets = JSONObject(updated).getJSONArray("desktops").getJSONObject(0).getJSONArray("widgets")

        assertEquals(2, widgets.length())
        assertEquals("clock", widgets.getJSONObject(0).getString("type"))
        assertEquals(42, widgets.getJSONObject(1).getInt("appWidgetId"))
    }

    @Test
    fun removesWidgetWithoutDroppingUnknownData() {
        val source = """{"futureRoot":1,"desktops":[{"futureDesktop":2,"widgets":[{"type":"clock","futureWidget":3},{"type":"text","value":"keep"}]}]}"""

        val updated = DesktopConfigEditor.removeWidget(source, 0, 0)
        val root = JSONObject(updated)

        assertEquals(1, root.getInt("futureRoot"))
        assertEquals(2, root.getJSONArray("desktops").getJSONObject(0).getInt("futureDesktop"))
        val widgets = root.getJSONArray("desktops").getJSONObject(0).getJSONArray("widgets")
        assertEquals(1, widgets.length())
        assertEquals("keep", widgets.getJSONObject(0).getString("value"))
    }

    @Test
    fun reordersWidgetsByAbsoluteDestination() {
        val source = """{"desktops":[{"widgets":[{"id":"a"},{"id":"b"},{"id":"c"}]}]}"""

        val updated = DesktopConfigEditor.reorderWidget(source, 0, 0, 2)
        val widgets = JSONObject(updated).getJSONArray("desktops").getJSONObject(0).getJSONArray("widgets")

        assertEquals("b", widgets.getJSONObject(0).getString("id"))
        assertEquals("c", widgets.getJSONObject(1).getString("id"))
        assertEquals("a", widgets.getJSONObject(2).getString("id"))
    }

    @Test
    fun resizesLegacySpanWithinFlutterBounds() {
        val source = """{"desktops":[{"widgets":[{"type":"clock","future":"keep"},{"type":"text","span":4}]}]}"""

        val grown = DesktopConfigEditor.resizeWidgetSpan(source, 0, 0, 2)
        val grownWidgets = JSONObject(grown).getJSONArray("desktops").getJSONObject(0).getJSONArray("widgets")
        assertEquals(3, grownWidgets.getJSONObject(0).getInt("span"))
        assertEquals("keep", grownWidgets.getJSONObject(0).getString("future"))

        val clamped = DesktopConfigEditor.resizeWidgetSpan(grown, 0, 1, 3)
        assertEquals(
            4,
            JSONObject(clamped).getJSONArray("desktops").getJSONObject(0)
                .getJSONArray("widgets").getJSONObject(1).getInt("span"),
        )
    }

    @Test
    fun movesAndResizesWidgetGeometryInOneCommit() {
        val source = """{"desktops":[{"widgets":[{"type":"clock","future":"keep"}]}]}"""

        val updated = DesktopConfigEditor.setWidgetGeometry(source, 0, 0, x = 3, y = 4, width = 5, height = 2)
        val widget = JSONObject(updated).getJSONArray("desktops").getJSONObject(0)
            .getJSONArray("widgets").getJSONObject(0)

        assertEquals(3, widget.getInt("x"))
        assertEquals(4, widget.getInt("y"))
        assertEquals(5, widget.getInt("w"))
        assertEquals(2, widget.getInt("h"))
        assertEquals("keep", widget.getString("future"))
    }

    @Test
    fun movesWidgetWithoutChangingItsSize() {
        val source = """{"desktops":[{"widgets":[{"type":"clock","x":1,"y":2,"w":5,"h":3}]}]}"""

        val updated = DesktopConfigEditor.moveWidget(source, 0, 0, x = 7, y = 8)
        val widget = JSONObject(updated).getJSONArray("desktops").getJSONObject(0)
            .getJSONArray("widgets").getJSONObject(0)

        assertEquals(7, widget.getInt("x"))
        assertEquals(8, widget.getInt("y"))
        assertEquals(5, widget.getInt("w"))
        assertEquals(3, widget.getInt("h"))
    }
}
