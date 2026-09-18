package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject

object DesktopConfigEditor {
    fun appendEdgeBox(source: String, name: String, edge: EdgePosition): String {
        val root = JSONObject(source)
        val boxes = root.optJSONArray("edgeBoxes") ?: org.json.JSONArray().also { root.put("edgeBoxes", it) }
        val usedIds = (0 until boxes.length()).mapNotNull(boxes::optJSONObject).map { it.optString("id") }.toSet()
        var number = boxes.length() + 1
        while ("box-$number" in usedIds) number++
        boxes.put(
            JSONObject()
                .put("id", "box-$number")
                .put("name", name.trim().ifBlank { "Caja $number" })
                .put("edge", edge.jsonName)
                .put("direction", if (edge == EdgePosition.LEFT || edge == EdgePosition.RIGHT) "vertical" else "horizontal")
                .put("visible", true)
                .put("showTitle", true)
                .put("compact", false)
                .put("showExpandButton", true)
                .put("color", "#66E0FF")
                .put("items", org.json.JSONArray()),
        )
        return root.toString(2)
    }

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

    fun moveEdgeBox(source: String, id: String, edge: EdgePosition, targetIndex: Int? = null): String {
        val root = JSONObject(source)
        val boxes = root.optJSONArray("edgeBoxes") ?: error("edgeBoxes is missing")
        val boxList = (0 until boxes.length()).mapNotNull(boxes::optJSONObject).toMutableList()
        val box = boxList.firstOrNull { it.optString("id") == id }
            ?: error("edge box not found: $id")
        box.put("edge", edge.jsonName)
        box.put(
            "direction",
            if (edge == EdgePosition.LEFT || edge == EdgePosition.RIGHT) "vertical" else "horizontal",
        )
        if (targetIndex != null) {
            boxList.remove(box)
            val targetBoxes = boxList.filter { it.optString("edge") == edge.jsonName }
            val insertion = targetIndex.coerceIn(0, targetBoxes.size)
            val globalIndex = targetBoxes.getOrNull(insertion)?.let(boxList::indexOf)
                ?: targetBoxes.lastOrNull()?.let { boxList.indexOf(it) + 1 }
                ?: boxList.size
            boxList.add(globalIndex.coerceIn(0, boxList.size), box)
            root.put("edgeBoxes", org.json.JSONArray(boxList))
        }
        return root.toString(2)
    }

    fun appendEdgeBoxApp(source: String, id: String, app: InstalledApp): String =
        appendEdgeBoxApps(source, id, listOf(app))

    fun appendEdgeBoxApps(source: String, id: String, apps: List<InstalledApp>): String {
        val root = JSONObject(source)
        val boxes = root.optJSONArray("edgeBoxes") ?: error("edgeBoxes is missing")
        val box = (0 until boxes.length())
            .mapNotNull(boxes::optJSONObject)
            .firstOrNull { it.optString("id") == id }
            ?: error("edge box not found: $id")
        val items = box.optJSONArray("items") ?: org.json.JSONArray().also { box.put("items", it) }
        val existing = (0 until items.length())
            .mapNotNull(items::optJSONObject)
            .filter { it.optString("type") == "app" }
            .mapTo(mutableSetOf()) {
                "${it.optString("package")}/${it.optString("activity")}"
            }
        apps.forEach { app ->
            val key = "${app.packageName}/${app.activityName}"
            if (!existing.add(key)) return@forEach
            items.put(
                JSONObject()
                    .put("type", "app")
                    .put("package", app.packageName)
                    .put("activity", app.activityName)
                    .put("label", app.label),
            )
        }
        return root.toString(2)
    }

    fun moveEdgeBoxItem(
        source: String,
        sourceBoxId: String,
        sourceIndex: Int,
        targetBoxId: String,
        targetIndex: Int,
    ): String {
        val root = JSONObject(source)
        val boxes = root.optJSONArray("edgeBoxes") ?: error("edgeBoxes is missing")
        fun box(id: String): JSONObject = (0 until boxes.length())
            .mapNotNull(boxes::optJSONObject)
            .firstOrNull { it.optString("id") == id }
            ?: error("edge box not found: $id")

        val sourceBox = box(sourceBoxId)
        val targetBox = box(targetBoxId)
        val sourceItems = sourceBox.optJSONArray("items") ?: error("source items are missing")
        require(sourceIndex in 0 until sourceItems.length()) { "source item index out of range" }
        val sourceList = MutableList<Any?>(sourceItems.length()) { sourceItems.get(it) }
        val moved = sourceList.removeAt(sourceIndex)
        if (sourceBox === targetBox) {
            val insertion = (if (targetIndex > sourceIndex) targetIndex - 1 else targetIndex)
                .coerceIn(0, sourceList.size)
            sourceList.add(insertion, moved)
            sourceBox.put("items", org.json.JSONArray(sourceList))
        } else {
            val targetItems = targetBox.optJSONArray("items") ?: org.json.JSONArray()
            val targetList = MutableList<Any?>(targetItems.length()) { targetItems.get(it) }
            targetList.add(targetIndex.coerceIn(0, targetList.size), moved)
            sourceBox.put("items", org.json.JSONArray(sourceList))
            targetBox.put("items", org.json.JSONArray(targetList))
        }
        return root.toString(2)
    }

    fun removeEdgeBoxItem(source: String, boxId: String, itemIndex: Int): String {
        val root = JSONObject(source)
        val boxes = root.optJSONArray("edgeBoxes") ?: error("edgeBoxes is missing")
        val box = (0 until boxes.length())
            .mapNotNull(boxes::optJSONObject)
            .firstOrNull { it.optString("id") == boxId }
            ?: error("edge box not found: $boxId")
        val items = box.optJSONArray("items") ?: error("items are missing")
        require(itemIndex in 0 until items.length()) { "item index out of range" }
        items.remove(itemIndex)
        return root.toString(2)
    }

    fun appendOmarchyNotifyWidget(source: String, desktopIndex: Int): String {
        val root = JSONObject(source)
        val widgets = widgetsAt(root, desktopIndex)
        val exists = (0 until widgets.length())
            .mapNotNull(widgets::optJSONObject)
            .any { it.optString("type") == "omarchy_notify" }
        if (!exists) {
            widgets.put(
                JSONObject()
                    .put("type", "omarchy_notify")
                    .put("w", 6)
                    .put("h", 3)
                    .put("span", 6)
                    .put("maxMessages", 5),
            )
        }
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
