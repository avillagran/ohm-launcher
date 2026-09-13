package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject

object DesktopConfigEditor {
    fun insertDesktop(source: String, insertIndex: Int, templateIndex: Int): String {
        val root = JSONObject(source)
        val desktops = root.getJSONArray("desktops")
        require(templateIndex in 0 until desktops.length()) { "template desktop index out of range" }
        val insertion = insertIndex.coerceIn(0, desktops.length())
        val template = desktops.getJSONObject(templateIndex)
        val widgets = template.optJSONArray("widgets")?.let { org.json.JSONArray(it.toString()) }
            ?: org.json.JSONArray()
        val created = JSONObject()
            .put("name", "Escritorio ${desktops.length() + 1}")
            .put("widgets", widgets)
        val reordered = MutableList<Any?>(desktops.length()) { desktops.get(it) }
        reordered.add(insertion, created)
        root.put("desktops", org.json.JSONArray(reordered))
        return root.toString(2)
    }

    fun deleteDesktop(source: String, desktopIndex: Int): String {
        val root = JSONObject(source)
        val desktops = root.getJSONArray("desktops")
        require(desktops.length() > 1) { "cannot delete the final desktop" }
        require(desktopIndex in 0 until desktops.length()) { "desktop index out of range" }
        desktops.remove(desktopIndex)
        return root.toString(2)
    }

    fun moveEdgeBox(source: String, id: String, edge: EdgePosition): String {
        val root = JSONObject(source)
        val boxes = root.optJSONArray("edgeBoxes") ?: error("edgeBoxes is missing")
        val box = (0 until boxes.length())
            .mapNotNull(boxes::optJSONObject)
            .firstOrNull { it.optString("id") == id }
            ?: error("edge box not found: $id")
        box.put("edge", edge.jsonName)
        box.put(
            "direction",
            if (edge == EdgePosition.LEFT || edge == EdgePosition.RIGHT) "vertical" else "horizontal",
        )
        return root.toString(2)
    }

    fun appendWidget(source: String, desktopIndex: Int, widget: JSONObject): String {
        val root = JSONObject(source)
        widgetsAt(root, desktopIndex).put(JSONObject(widget.toString()))
        return root.toString(2)
    }

    fun removeWidget(source: String, desktopIndex: Int, widgetIndex: Int): String {
        val root = JSONObject(source)
        val widgets = widgetsAt(root, desktopIndex)
        require(widgetIndex in 0 until widgets.length()) { "widget index out of range" }
        widgets.remove(widgetIndex)
        return root.toString(2)
    }

    fun reorderWidget(source: String, desktopIndex: Int, fromIndex: Int, toIndex: Int): String {
        val root = JSONObject(source)
        val widgets = widgetsAt(root, desktopIndex)
        require(fromIndex in 0 until widgets.length()) { "widget index out of range" }
        require(toIndex in 0 until widgets.length()) { "destination index out of range" }
        val reordered = MutableList<Any?>(widgets.length()) { widgets.get(it) }
        val widget = reordered.removeAt(fromIndex)
        reordered.add(toIndex, widget)
        val desktop = root.getJSONArray("desktops").getJSONObject(desktopIndex)
        desktop.put("widgets", org.json.JSONArray(reordered))
        return root.toString(2)
    }

    fun resizeWidgetSpan(source: String, desktopIndex: Int, widgetIndex: Int, delta: Int): String {
        val root = JSONObject(source)
        val widgets = widgetsAt(root, desktopIndex)
        require(widgetIndex in 0 until widgets.length()) { "widget index out of range" }
        val widget = widgets.getJSONObject(widgetIndex)
        widget.put("span", (widget.optInt("span", 1) + delta).coerceIn(1, 4))
        return root.toString(2)
    }

    fun moveWidget(source: String, desktopIndex: Int, widgetIndex: Int, x: Int, y: Int): String {
        require(x >= 0 && y >= 0) { "widget coordinates must not be negative" }
        val root = JSONObject(source)
        val widgets = widgetsAt(root, desktopIndex)
        require(widgetIndex in 0 until widgets.length()) { "widget index out of range" }
        widgets.getJSONObject(widgetIndex).apply {
            put("x", x)
            put("y", y)
        }
        return root.toString(2)
    }

    fun setWidgetGeometry(
        source: String,
        desktopIndex: Int,
        widgetIndex: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): String {
        require(x >= 0 && y >= 0) { "widget coordinates must not be negative" }
        require(width >= 1 && height >= 1) { "widget dimensions must be positive" }
        val root = JSONObject(source)
        val widgets = widgetsAt(root, desktopIndex)
        require(widgetIndex in 0 until widgets.length()) { "widget index out of range" }
        widgets.getJSONObject(widgetIndex).apply {
            put("x", x)
            put("y", y)
            put("w", width)
            put("h", height)
        }
        return root.toString(2)
    }

    fun updateTtfx(source: String, desktopIndex: Int, settings: TtfxConfig): String {
        val root = JSONObject(source)
        val desktops = root.getJSONArray("desktops")
        require(desktopIndex in 0 until desktops.length()) { "desktop index out of range" }
        desktops.getJSONObject(desktopIndex).apply {
            put("ttfxBackground", settings.enabled)
            put("ttfxEffect", settings.effect)
            put("ttfxText", settings.text)
            put("ttfxTextSize", settings.textSize)
            put("ttfxTextX", settings.textX)
            put("ttfxTextY", settings.textY)
            put("ttfxAudio", settings.audio)
            put("ttfxIntensity", settings.intensity)
            put("ttfxSpeed", settings.speed)
            put("ttfxResolution", settings.resolution)
            put("ttfxReactivity", settings.reactivity)
        }
        return root.toString(2)
    }

    private fun widgetsAt(root: JSONObject, desktopIndex: Int): org.json.JSONArray {
        val desktops = root.getJSONArray("desktops")
        require(desktopIndex in 0 until desktops.length()) { "desktop index out of range" }
        val desktop = desktops.getJSONObject(desktopIndex)
        return desktop.optJSONArray("widgets") ?: org.json.JSONArray().also { desktop.put("widgets", it) }
    }
}
