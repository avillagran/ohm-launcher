package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopConfigEditorTest {
    @Test
    fun createsAnEmptyEdgeBoxWithoutDroppingExistingConfiguration() {
        val source = """{"future":{"keep":true},"edgeBoxes":[{"id":"box-1","items":[]}],"desktops":[{"widgets":[]}]}"""

        val updated = DesktopConfigEditor.appendEdgeBox(source, "Trabajo", EdgePosition.LEFT)
        val root = JSONObject(updated)
        val box = root.getJSONArray("edgeBoxes").getJSONObject(1)

        assertEquals("box-2", box.getString("id"))
        assertEquals("Trabajo", box.getString("name"))
        assertEquals("left", box.getString("edge"))
        assertEquals("vertical", box.getString("direction"))
        assertEquals(0, box.getJSONArray("items").length())
        assertTrue(root.getJSONObject("future").getBoolean("keep"))
    }

    @Test
    fun appendsAnInstalledApplicationToAnEdgeBoxWithoutDroppingExistingItems() {
        val source = """{"edgeBoxes":[{"id":"social","items":[{"type":"app","package":"old.pkg","activity":"Old"}],"future":true}],"desktops":[{"widgets":[]}]}"""
        val app = InstalledApp("Nueva", "new.pkg", "new.pkg.Main")

        val updated = DesktopConfigEditor.appendEdgeBoxApp(source, "social", app)
        val box = JSONObject(updated).getJSONArray("edgeBoxes").getJSONObject(0)
        val items = box.getJSONArray("items")

        assertEquals(2, items.length())
        assertEquals("old.pkg", items.getJSONObject(0).getString("package"))
        assertEquals("new.pkg", items.getJSONObject(1).getString("package"))
        assertEquals("new.pkg.Main", items.getJSONObject(1).getString("activity"))
        assertEquals("Nueva", items.getJSONObject(1).getString("label"))
        assertTrue(box.getBoolean("future"))
    }

    @Test
    fun appendsEveryCheckedApplicationInOneLosslessUpdate() {
        val source = """{"edgeBoxes":[{"id":"social","items":[{"type":"app","package":"old.pkg","activity":"Old"}],"future":true}],"desktops":[{"widgets":[]}]}"""
        val selected = listOf(
            InstalledApp("Alpha", "alpha.pkg", "alpha.pkg.Main"),
            InstalledApp("Beta", "beta.pkg", "beta.pkg.Main"),
        )

        val updated = DesktopConfigEditor.appendEdgeBoxApps(source, "social", selected)
        val box = JSONObject(updated).getJSONArray("edgeBoxes").getJSONObject(0)

        assertEquals(listOf("old.pkg", "alpha.pkg", "beta.pkg"), packages(box.getJSONArray("items")))
        assertTrue(box.getBoolean("future"))
    }

    @Test
    fun movesAnEdgeItemBetweenBoxesAtTheRequestedIndex() {
        val source = """{"edgeBoxes":[{"id":"a","items":[{"label":"one"},{"label":"two"}]},{"id":"b","items":[{"label":"three"}]}],"desktops":[{"widgets":[]}]}"""

        val updated = DesktopConfigEditor.moveEdgeBoxItem(source, "a", 1, "b", 0)
        val boxes = JSONObject(updated).getJSONArray("edgeBoxes")

        assertEquals(listOf("one"), labels(boxes.getJSONObject(0).getJSONArray("items")))
        assertEquals(listOf("two", "three"), labels(boxes.getJSONObject(1).getJSONArray("items")))
    }

    @Test
    fun reordersAnEdgeItemInsideItsBox() {
        val source = """{"edgeBoxes":[{"id":"a","items":[{"label":"one"},{"label":"two"},{"label":"three"}]}],"desktops":[{"widgets":[]}]}"""

        val updated = DesktopConfigEditor.moveEdgeBoxItem(source, "a", 0, "a", 3)
        val items = JSONObject(updated).getJSONArray("edgeBoxes").getJSONObject(0).getJSONArray("items")

        assertEquals(listOf("two", "three", "one"), labels(items))
    }

    @Test
    fun removesOnlyTheSelectedEdgeItem() {
        val source = """{"edgeBoxes":[{"id":"a","items":[{"label":"one"},{"label":"two"}]}],"desktops":[{"widgets":[]}]}"""

        val updated = DesktopConfigEditor.removeEdgeBoxItem(source, "a", 0)
        val items = JSONObject(updated).getJSONArray("edgeBoxes").getJSONObject(0).getJSONArray("items")

        assertEquals(listOf("two"), labels(items))
    }

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
    fun addsOnlyOneOmarchyNotifyWidgetPerDesktop() {
        val source = """{"desktops":[{"name":"One","widgets":[]}]}"""

        val once = DesktopConfigEditor.appendOmarchyNotifyWidget(source, 0)
        val twice = DesktopConfigEditor.appendOmarchyNotifyWidget(once, 0)
        val widgets = JSONObject(twice).getJSONArray("desktops").getJSONObject(0).getJSONArray("widgets")

        assertEquals(1, widgets.length())
        assertEquals("omarchy_notify", widgets.getJSONObject(0).getString("type"))
        assertEquals(6, widgets.getJSONObject(0).getInt("w"))
        assertEquals(3, widgets.getJSONObject(0).getInt("h"))
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
    fun movesEdgeBoxToRequestedPositionAmongBoxesOnTargetEdge() {
        val source = """{"edgeBoxes":[{"id":"a","edge":"left"},{"id":"b","edge":"right"},{"id":"c","edge":"right"}],"desktops":[{"widgets":[]}]}"""

        val updated = DesktopConfigEditor.moveEdgeBox(source, "a", EdgePosition.RIGHT, 1)
        val boxes = JSONObject(updated).getJSONArray("edgeBoxes")

        assertEquals(listOf("b", "a", "c"), (0 until boxes.length()).map { boxes.getJSONObject(it).getString("id") })
        assertEquals("right", boxes.getJSONObject(1).getString("edge"))
        assertEquals("vertical", boxes.getJSONObject(1).getString("direction"))
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

    private fun labels(items: JSONArray): List<String> =
        (0 until items.length()).map { items.getJSONObject(it).optString("label") }

    private fun packages(items: JSONArray): List<String> =
        (0 until items.length()).map { items.getJSONObject(it).optString("package") }
}
