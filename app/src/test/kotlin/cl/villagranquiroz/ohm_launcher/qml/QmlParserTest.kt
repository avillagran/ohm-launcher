package cl.villagranquiroz.ohm_launcher.qml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QmlParserTest {
    @Test
    fun tokenizesCommentsEscapesRegexAndOperators() {
        val tokens = QmlTokenizer.tokenize(
            """
            // comment
            Item { text: "line\n\uF030"; pattern: /a[\/]b/gi; active: x !== 2 }
            """.trimIndent(),
        ).filter { it.kind != QmlTokenKind.EOF }

        assertTrue(tokens.none { it.text == "comment" })
        assertTrue(tokens.any { it.kind == QmlTokenKind.STRING && it.text == "line\n\uF030" })
        assertTrue(tokens.toString(), tokens.any { it.kind == QmlTokenKind.REGEX && it.text == "/a[\\/]b/gi" })
        assertTrue(tokens.any { it.kind == QmlTokenKind.OPERATOR && it.text == "!==" })
    }

    @Test
    fun parsesImportsPropertiesChildrenAndInlineDelegates() {
        val document = QmlParser(
            """
            import QtQuick
            import "helpers" as Helpers
            BarWidget {
              id: root
              readonly property string icon: "clock"
              implicitWidth: button.implicitWidth
              WidgetButton { id: button; text: root.icon }
              Repeater {
                model: [{ label: "One", value: 1 }, { label: "Two", value: 2 }]
                delegate: Rectangle { required property var modelData; width: 20 }
              }
            }
            """.trimIndent(),
        ).parse()

        assertNull(document.error)
        assertEquals(listOf("QtQuick", "helpers"), document.imports)
        val root = document.elements.single()
        assertEquals("BarWidget", root.baseType)
        assertEquals("root", root.id)
        assertEquals(QmlLiteral("clock"), root.properties["icon"])
        assertEquals(2, root.children.size)
        val delegate = root.children[1].properties["delegate"] as QmlInlineElement
        assertEquals("Rectangle", delegate.element.baseType)
        val model = root.children[1].properties["model"] as QmlArray
        assertEquals(2, model.items.size)
    }

    @Test
    fun reportsTruncatedDocumentWithoutThrowing() {
        val document = QmlParser("Item { Text { text: \"oops\" }").parse()

        assertTrue(document.elements.isEmpty())
        assertTrue(document.error.orEmpty().contains("missing"))
    }
}
