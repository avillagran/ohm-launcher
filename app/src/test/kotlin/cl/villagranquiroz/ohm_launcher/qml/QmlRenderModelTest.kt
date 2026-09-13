package cl.villagranquiroz.ohm_launcher.qml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QmlRenderModelTest {
    @Test
    fun detectsPrivateUseGlyphsThatRequireTheBundledIconFont() {
        assertEquals(true, QmlTextTypefacePolicy.requiresIconFont("󰖔"))
        assertEquals(true, QmlTextTypefacePolicy.requiresIconFont("status \ue000"))
        assertEquals(false, QmlTextTypefacePolicy.requiresIconFont("Santiago 18°"))
    }

    @Test
    fun preservesVisualChildrenInsideOmarchySurfaceWrappers() {
        val root = QmlElement(
            type = "BarWidget",
            children = listOf(
                QmlElement(type = "SystemClock"),
                QmlElement(
                    type = "KeyboardPanel",
                    children = listOf(
                        QmlElement(
                            type = "PanelKeyCatcher",
                            children = listOf(QmlElement(type = "Column", children = listOf(QmlElement(type = "Text")))),
                        ),
                    ),
                ),
            ),
        )

        val model = QmlRenderModelConverter().convert(root)

        assertEquals(QmlVisualType.ITEM, model?.type)
        assertEquals(QmlVisualType.ITEM, model?.children?.single()?.type)
        assertEquals(QmlVisualType.ITEM, model?.children?.single()?.children?.single()?.type)
        assertEquals(QmlVisualType.COLUMN, model?.children?.single()?.children?.single()?.children?.single()?.type)
    }

    @Test
    fun rendersBarIconButtonTooltipAsFallbackText() {
        val button = QmlElement(
            type = "BarIconButton",
            properties = mapOf("tooltipText" to QmlLiteral("Minesweeper")),
        )

        val model = QmlRenderModelConverter().convert(button)

        assertEquals(QmlVisualType.TEXT, model?.type)
        assertEquals("Minesweeper", model?.text)
    }

    @Test
    fun convertsRectangleTextAndImageStyle() {
        val root = QmlElement(
            type = "Item",
            properties = mapOf(
                "title" to QmlLiteral("Connected"),
                "accent" to QmlLiteral("#336699"),
            ),
            children = listOf(
                QmlElement(
                    type = "Rectangle",
                    properties = mapOf(
                        "color" to QmlReference(listOf("root", "accent")),
                        "radius" to QmlLiteral(8),
                        "border.color" to QmlLiteral("#80ffffff"),
                        "border.width" to QmlLiteral(2),
                        "opacity" to QmlLiteral(0.5),
                    ),
                ),
                QmlElement(
                    type = "Text",
                    properties = mapOf(
                        "text" to QmlReference(listOf("root", "title")),
                        "color" to QmlLiteral("white"),
                        "font.pixelSize" to QmlLiteral(18),
                        "font.bold" to QmlLiteral(true),
                        "font.family" to QmlLiteral("monospace"),
                        "font.letterSpacing" to QmlLiteral(1.5),
                        "horizontalAlignment" to QmlReference(listOf("Text", "AlignHCenter")),
                    ),
                ),
                QmlElement(
                    type = "Image",
                    properties = mapOf(
                        "source" to QmlLiteral("file:///tmp/preview.png"),
                        "fillMode" to QmlReference(listOf("Image", "PreserveAspectCrop")),
                    ),
                ),
            ),
        )

        val children = QmlRenderModelConverter().convert(root)!!.children
        val rectangle = children[0]
        val text = children[1]
        val image = children[2]

        assertEquals(QmlColor(0xff336699.toInt()), rectangle.style.backgroundColor)
        assertNull(rectangle.style.foregroundColor)
        assertEquals(QmlColor(0x80ffffff.toInt()), rectangle.style.borderColor)
        assertEquals(2f, rectangle.style.borderWidth)
        assertEquals(8f, rectangle.style.cornerRadius)
        assertEquals(0.5f, rectangle.style.opacity)
        assertEquals("Connected", text.text)
        assertNull(text.style.backgroundColor)
        assertEquals(QmlColor(0xffffffff.toInt()), text.style.foregroundColor)
        assertEquals(18f, text.style.fontSize)
        assertEquals(true, text.style.bold)
        assertEquals("monospace", text.style.fontFamily)
        assertEquals(1.5f, text.style.letterSpacing)
        assertEquals(QmlTextAlignment.CENTER, text.style.textAlignment)
        assertEquals("file:///tmp/preview.png", image.imageSource)
        assertEquals(QmlImageScale.CROP, image.style.imageScale)
    }

    @Test
    fun convertsLayoutDimensionsSpacingVisibilityAndAnchors() {
        val element = QmlElement(
            type = "Row",
            properties = mapOf(
                "width" to QmlLiteral(120),
                "implicitWidth" to QmlLiteral(80),
                "height" to QmlLiteral(32.5),
                "spacing" to QmlCall("Style.space", listOf(QmlLiteral(6))),
                "visible" to QmlLiteral(false),
                "anchors.fill" to QmlReference(listOf("parent")),
                "anchors.margins" to QmlLiteral(4),
                "anchors.leftMargin" to QmlLiteral(7),
                "anchors.horizontalCenter" to QmlReference(listOf("parent", "horizontalCenter")),
                "anchors.bottom" to QmlReference(listOf("parent", "bottom")),
            ),
        )

        val model = QmlRenderModelConverter().convert(element)

        assertEquals(120f, model?.layout?.width)
        assertEquals(32.5f, model?.layout?.height)
        assertEquals(6f, model?.layout?.spacing)
        assertEquals(false, model?.layout?.visible)
        assertEquals(true, model?.layout?.fillParent)
        assertEquals(QmlInsets(left = 7f, top = 4f, right = 4f, bottom = 4f), model?.layout?.margins)
        assertEquals(QmlHorizontalAlignment.CENTER, model?.layout?.horizontalAlignment)
        assertEquals(QmlVerticalAlignment.BOTTOM, model?.layout?.verticalAlignment)
    }

    @Test
    fun convertsSupportedVisualTreeAndDropsHelperTypes() {
        val root = QmlElement(
            type = "Column",
            children = listOf(
                QmlElement(type = "Timer", children = listOf(QmlElement(type = "Text"))),
                QmlElement(type = "Item"),
                QmlElement(type = "Rectangle"),
                QmlElement(type = "Text"),
                QmlElement(type = "Row"),
                QmlElement(type = "Column"),
                QmlElement(type = "Image"),
            ),
        )

        val model = QmlRenderModelConverter().convert(root)

        assertEquals(QmlVisualType.COLUMN, model?.type)
        assertEquals(
            listOf(
                QmlVisualType.ITEM,
                QmlVisualType.RECTANGLE,
                QmlVisualType.TEXT,
                QmlVisualType.ROW,
                QmlVisualType.COLUMN,
                QmlVisualType.IMAGE,
            ),
            model?.children?.map(QmlRenderNode::type),
        )
        assertNull(QmlRenderModelConverter().convert(QmlElement(type = "Process")))
    }
}
