package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherConfigTest {
    @Test
    fun allowsLargerTtfxTextSizesForRasterizedCustomText() {
        val twelve = LauncherConfig.parse("""{"desktops":[{"ttfxTextSize":12,"widgets":[]}]}""")
        val clamped = LauncherConfig.parse("""{"desktops":[{"ttfxTextSize":99,"widgets":[]}]}""")

        assertEquals(12, twelve.desktops.single().ttfx.textSize)
        assertEquals(12, clamped.desktops.single().ttfx.textSize)
    }

    @Test
    fun retainsUnknownRootDataForCompatibleMutations() {
        val config = LauncherConfig.parse(
            """{"future":{"enabled":true},"desktops":[{"widgets":[]}]}""",
        )

        assertTrue(config.raw.getJSONObject("future").getBoolean("enabled"))
    }

    @Test
    fun parsesFlutterCompatibleDesktopAndTtfxSettings() {
        val config = LauncherConfig.parse(
            """
            {
              "wallpaper":"#102030",
              "desktops":[{
                "name":"Inicio",
                "background":"#07100A",
                "gridColumns":10,
                "gridRows":17,
                "ttfxBackground":true,
                "ttfxEffect":"crumble",
                "ttfxText":"Omarchy",
                "ttfxTextSize":7,
                "ttfxTextX":0.55,
                "ttfxTextY":0.45,
                "ttfxAudio":true,
                "ttfxIntensity":5,
                "ttfxSpeed":3.0,
                "ttfxResolution":6,
                "ttfxReactivity":2,
                "widgets":[{"type":"clock","format":"HH:mm"}]
              }]
            }
            """.trimIndent(),
        )

        assertEquals("#102030", config.wallpaper)
        assertEquals(1, config.desktops.size)
        val desktop = config.desktops.single()
        assertEquals("Inicio", desktop.name)
        assertEquals(10, desktop.gridColumns)
        assertEquals(17, desktop.gridRows)
        assertEquals("crumble", desktop.ttfx.effect)
        assertEquals(7, desktop.ttfx.textSize)
        assertEquals(6, desktop.ttfx.resolution)
        assertEquals(0.55, desktop.ttfx.textX, 0.0001)
        assertEquals("clock", desktop.widgets.single().type)
    }

    @Test
    fun suppliesCompatibleDefaultsForMissingFields() {
        val config = LauncherConfig.parse("{\"desktops\":[{\"widgets\":[]}]}")
        val desktop = config.desktops.single()

        assertEquals("Escritorio 1", desktop.name)
        assertTrue(desktop.ttfx.enabled)
        assertEquals("matrix", desktop.ttfx.effect)
        assertEquals("OHM", desktop.ttfx.text)
        assertEquals(3, desktop.ttfx.textSize)
        assertEquals(2, desktop.ttfx.resolution)
        assertEquals(14, desktop.gridColumns)
        assertEquals(10, desktop.gridRows)
        assertFalse(desktop.ttfx.audio)
        assertFalse(LauncherConfig.parse(ConfigStorage.DEFAULT_CONFIG).desktops.single().ttfx.audio)
    }

    @Test
    fun parsesEveryEdgeBoxAndItemField() {
        val config = LauncherConfig.parse(
            """
            {
              "desktops":[{"widgets":[]}],
              "edgeBoxes":[{
                "id":"tools","name":"Tools","edge":"left","direction":"grid",
                "visible":false,"showTitle":false,"compact":true,"compactItem":2,
                "color":"#123456","futureBox":"keep",
                "items":[
                  {"type":"app","package":"pkg","activity":".Main","label":"App","futureItem":7},
                  {"type":"system_widget","provider":"pkg/.Widget"},
                  {"type":"plugin","pluginId":"plugin.id"}
                ]
              }]
            }
            """.trimIndent(),
        )

        val box = config.edgeBoxes.single()
        assertEquals("tools", box.id)
        assertEquals("Tools", box.name)
        assertEquals(EdgePosition.LEFT, box.edge)
        assertEquals(EdgeDirection.GRID, box.direction)
        assertEquals(false, box.visible)
        assertEquals(false, box.showTitle)
        assertEquals(true, box.compact)
        assertEquals(2, box.compactItem)
        assertEquals("#123456", box.color)
        assertEquals("keep", box.raw.getString("futureBox"))
        assertEquals(EdgeItemType.APP, box.items[0].type)
        assertEquals("pkg", box.items[0].packageName)
        assertEquals(".Main", box.items[0].activity)
        assertEquals("App", box.items[0].label)
        assertEquals(7, box.items[0].raw.getInt("futureItem"))
        assertEquals("pkg/.Widget", box.items[1].provider)
        assertEquals("plugin.id", box.items[2].pluginId)
    }

    @Test
    fun appliesFlutterEdgeDefaultsAndKeepsUnknownItemTypes() {
        val config = LauncherConfig.parse(
            """{"desktops":[{"widgets":[]}],"edgeBoxes":[{"items":[{"type":"future_type","payload":true}]}]}""",
        )

        val box = config.edgeBoxes.single()
        assertEquals("Caja 1", box.name)
        assertEquals(EdgePosition.BOTTOM, box.edge)
        assertEquals(EdgeDirection.HORIZONTAL, box.direction)
        assertTrue(box.visible)
        assertTrue(box.showTitle)
        assertEquals(false, box.compact)
        assertEquals(0, box.compactItem)
        assertEquals("#66E0FF", box.color)
        assertEquals(EdgeItemType.UNKNOWN, box.items.single().type)
        assertEquals("future_type", box.items.single().rawType)
        assertTrue(box.items.single().raw.getBoolean("payload"))
    }

    @Test
    fun parsesWidgetGeometryWithRuntimeDefaults() {
        val config = LauncherConfig.parse(
            """{"desktops":[{"widgets":[{"type":"clock","span":3,"future":"keep"},{"type":"text","x":2,"y":4,"w":5,"h":2}]}]}""",
        )

        val first = config.desktops.single().widgets[0]
        assertEquals(0, first.x)
        assertEquals(0, first.y)
        assertEquals(3, first.width)
        assertEquals(1, first.height)
        assertEquals(3, first.span)
        assertEquals("keep", first.raw.getString("future"))
        val second = config.desktops.single().widgets[1]
        assertEquals(2, second.x)
        assertEquals(4, second.y)
        assertEquals(5, second.width)
        assertEquals(2, second.height)
        assertEquals(null, second.span)
    }
}
