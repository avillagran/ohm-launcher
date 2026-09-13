package cl.villagranquiroz.ohm_launcher.qml

enum class QmlVisualType { ITEM, RECTANGLE, TEXT, ROW, COLUMN, IMAGE }

enum class QmlHorizontalAlignment { START, CENTER, END }
enum class QmlVerticalAlignment { TOP, CENTER, BOTTOM }
enum class QmlTextAlignment { START, CENTER, END }
enum class QmlImageScale { FIT, CROP, FILL, CENTER }

@JvmInline
value class QmlColor(val argb: Int)

data class QmlInsets(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
)

data class QmlLayout(
    val width: Float? = null,
    val height: Float? = null,
    val spacing: Float = 0f,
    val visible: Boolean = true,
    val fillParent: Boolean = false,
    val margins: QmlInsets = QmlInsets(),
    val horizontalAlignment: QmlHorizontalAlignment = QmlHorizontalAlignment.START,
    val verticalAlignment: QmlVerticalAlignment = QmlVerticalAlignment.TOP,
)

data class QmlStyle(
    val backgroundColor: QmlColor? = null,
    val foregroundColor: QmlColor? = null,
    val borderColor: QmlColor? = null,
    val borderWidth: Float = 0f,
    val cornerRadius: Float = 0f,
    val opacity: Float = 1f,
    val fontSize: Float = 14f,
    val bold: Boolean = false,
    val fontFamily: String? = null,
    val letterSpacing: Float = 0f,
    val textAlignment: QmlTextAlignment = QmlTextAlignment.START,
    val imageScale: QmlImageScale = QmlImageScale.FIT,
)

data class QmlRenderNode(
    val type: QmlVisualType,
    val layout: QmlLayout = QmlLayout(),
    val style: QmlStyle = QmlStyle(),
    val text: String? = null,
    val imageSource: String? = null,
    val children: List<QmlRenderNode> = emptyList(),
)

class QmlRenderModelConverter(private val runtime: QmlRuntime = QmlRuntime()) {
    fun convert(element: QmlElement): QmlRenderNode? = convert(element, runtime.scopeFor(element))

    private fun convert(element: QmlElement, scope: QmlScope): QmlRenderNode? {
        val type = when (element.baseType) {
            "Item" -> QmlVisualType.ITEM
            "Rectangle" -> QmlVisualType.RECTANGLE
            "Text" -> QmlVisualType.TEXT
            "Row" -> QmlVisualType.ROW
            "Column" -> QmlVisualType.COLUMN
            "Image" -> QmlVisualType.IMAGE
            in TEXT_CONTROL_TYPES -> QmlVisualType.TEXT
            in TRANSPARENT_ITEM_TYPES -> QmlVisualType.ITEM
            in TRANSPARENT_COLUMN_TYPES -> QmlVisualType.COLUMN
            in NONVISUAL_TYPES -> return null
            else -> return null
        }
        return QmlRenderNode(
            type = type,
            layout = layout(element, scope),
            style = style(element, scope, type),
            text = if (type == QmlVisualType.TEXT) {
                sequenceOf("text", "label", "tooltipText")
                    .mapNotNull { value(element, scope, it)?.toString()?.takeIf(String::isNotBlank) }
                    .firstOrNull()
                    ?: element.baseType.takeIf { element.baseType in TEXT_CONTROL_TYPES }.orEmpty()
            } else {
                null
            },
            imageSource = if (type == QmlVisualType.IMAGE) value(element, scope, "source")?.toString() else null,
            children = element.children.mapNotNull { convert(it, scope) },
        )
    }

    private fun layout(element: QmlElement, scope: QmlScope): QmlLayout = QmlLayout(
        width = number(element, scope, "width") ?: number(element, scope, "implicitWidth"),
        height = number(element, scope, "height") ?: number(element, scope, "implicitHeight"),
        spacing = number(element, scope, "spacing") ?: 0f,
        visible = element.properties["visible"]?.let { runtime.truthy(runtime.evaluate(it, scope)) } ?: true,
        fillParent = element.properties.containsKey("anchors.fill"),
        margins = margins(element, scope),
        horizontalAlignment = when {
            element.properties.containsKey("anchors.horizontalCenter") ||
                element.properties.containsKey("anchors.centerIn") -> QmlHorizontalAlignment.CENTER
            element.properties.containsKey("anchors.right") -> QmlHorizontalAlignment.END
            else -> QmlHorizontalAlignment.START
        },
        verticalAlignment = when {
            element.properties.containsKey("anchors.verticalCenter") ||
                element.properties.containsKey("anchors.centerIn") -> QmlVerticalAlignment.CENTER
            element.properties.containsKey("anchors.bottom") -> QmlVerticalAlignment.BOTTOM
            else -> QmlVerticalAlignment.TOP
        },
    )

    private fun margins(element: QmlElement, scope: QmlScope): QmlInsets {
        val all = number(element, scope, "anchors.margins") ?: 0f
        return QmlInsets(
            left = number(element, scope, "anchors.leftMargin") ?: all,
            top = number(element, scope, "anchors.topMargin") ?: all,
            right = number(element, scope, "anchors.rightMargin") ?: all,
            bottom = number(element, scope, "anchors.bottomMargin") ?: all,
        )
    }

    private fun style(element: QmlElement, scope: QmlScope, type: QmlVisualType): QmlStyle = QmlStyle(
        backgroundColor = if (type == QmlVisualType.RECTANGLE) color(value(element, scope, "color")) else null,
        foregroundColor = if (type == QmlVisualType.TEXT) color(value(element, scope, "color")) else null,
        borderColor = color(value(element, scope, "border.color")),
        borderWidth = number(element, scope, "border.width") ?: 0f,
        cornerRadius = number(element, scope, "radius") ?: 0f,
        opacity = (number(element, scope, "opacity") ?: 1f).coerceIn(0f, 1f),
        fontSize = number(element, scope, "font.pixelSize") ?: 14f,
        bold = value(element, scope, "font.bold")?.let(runtime::truthy) ?: false,
        fontFamily = value(element, scope, "font.family") as? String,
        letterSpacing = number(element, scope, "font.letterSpacing") ?: 0f,
        textAlignment = when {
            value(element, scope, "horizontalAlignment")?.toString()?.contains("Center", ignoreCase = true) == true ->
                QmlTextAlignment.CENTER
            value(element, scope, "horizontalAlignment")?.toString()?.contains("Right", ignoreCase = true) == true ->
                QmlTextAlignment.END
            else -> QmlTextAlignment.START
        },
        imageScale = when {
            value(element, scope, "fillMode")?.toString()?.contains("Crop", ignoreCase = true) == true -> QmlImageScale.CROP
            value(element, scope, "fillMode")?.toString()?.contains("Stretch", ignoreCase = true) == true -> QmlImageScale.FILL
            value(element, scope, "fillMode")?.toString()?.contains("Center", ignoreCase = true) == true -> QmlImageScale.CENTER
            else -> QmlImageScale.FIT
        },
    )

    private fun value(element: QmlElement, scope: QmlScope, name: String): Any? =
        element.properties[name]?.let { runtime.evaluate(it, scope) }

    private fun number(element: QmlElement, scope: QmlScope, name: String): Float? =
        (value(element, scope, name) as? Number)?.toFloat()?.takeIf(Float::isFinite)

    private fun color(value: Any?): QmlColor? {
        val text = (value as? String)?.trim()?.lowercase() ?: return null
        NAMED_COLORS[text]?.let { return QmlColor(it) }
        if (!text.startsWith('#')) return null
        val hex = text.drop(1)
        val expanded = when (hex.length) {
            3 -> "ff" + hex.map { "$it$it" }.joinToString("")
            4 -> hex.map { "$it$it" }.joinToString("")
            6 -> "ff$hex"
            8 -> hex
            else -> return null
        }
        return expanded.toLongOrNull(16)?.toInt()?.let(::QmlColor)
    }

    private companion object {
        val TRANSPARENT_ITEM_TYPES = setOf(
            "BarWidget",
            "Panel",
            "KeyboardPanel",
            "PanelKeyCatcher",
            "Popup",
            "PopupCard",
        )
        val TRANSPARENT_COLUMN_TYPES = setOf("Flickable", "ScrollView")
        val TEXT_CONTROL_TYPES = setOf("BarIconButton", "WidgetButton", "Button", "Label")
        val NONVISUAL_TYPES = setOf(
            "Behavior",
            "Timer",
            "Connections",
            "Transition",
            "PropertyAnimation",
            "SequentialAnimation",
            "ParallelAnimation",
            "AnchorChanges",
            "SpringAnimation",
            "NumberAnimation",
            "ColorAnimation",
            "OpacityAnimator",
            "RotationAnimator",
            "ScaleAnimator",
            "State",
            "States",
            "PropertyChanges",
            "Binding",
            "Shortcut",
            "Keys",
            "ListModel",
            "Component",
            "SystemClock",
            "Process",
            "IpcHandler",
            "FileView",
            "QtObject",
        )
        val NAMED_COLORS = mapOf(
            "transparent" to 0x00000000,
            "black" to 0xff000000.toInt(),
            "white" to 0xffffffff.toInt(),
            "red" to 0xffff0000.toInt(),
            "green" to 0xff008000.toInt(),
            "blue" to 0xff0000ff.toInt(),
        )
    }
}
