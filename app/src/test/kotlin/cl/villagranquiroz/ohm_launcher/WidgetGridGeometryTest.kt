package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetGridGeometryTest {
    @Test
    fun mapsGridCellsToPixelBoundsAndClampsOverflow() {
        assertEquals(GridPixelBounds(100, 200, 300, 400), WidgetGridGeometry.bounds(1000, 1000, 10, 5, 1, 1, 2, 1))
        assertEquals(GridPixelBounds(800, 800, 1000, 1000), WidgetGridGeometry.bounds(1000, 1000, 10, 5, 8, 4, 9, 9))
    }

    @Test
    fun mapsPixelsBackToGridCells() {
        assertEquals(WidgetGridCell(5, 2), WidgetGridGeometry.cellAt(1000, 1000, 10, 5, 550f, 450f))
        assertEquals(WidgetGridCell(9, 4), WidgetGridGeometry.cellAt(1000, 1000, 10, 5, 2000f, 2000f))
    }

    @Test
    fun mapsDirectEditDragFromTheWidgetsTopLeftToANewCell() {
        assertEquals(
            WidgetGridCell(4, 3),
            WidgetGridGeometry.movedCell(
                width = 1000,
                height = 1000,
                columns = 10,
                rows = 5,
                currentLeft = 100,
                currentTop = 200,
                deltaX = 350f,
                deltaY = 450f,
            ),
        )
    }

    @Test
    fun resizesFromEveryCornerWhileKeepingTheOppositeCornerFixed() {
        val original = WidgetGridRect(x = 2, y = 3, width = 4, height = 3)

        assertEquals(
            WidgetGridRect(2, 3, 7, 5),
            WidgetResizeGeometry.resize(original, ResizeCorner.BOTTOM_RIGHT, 3, 2, 14, 10),
        )
        assertEquals(
            WidgetGridRect(4, 4, 2, 2),
            WidgetResizeGeometry.resize(original, ResizeCorner.TOP_LEFT, 2, 1, 14, 10),
        )
        assertEquals(
            WidgetGridRect(2, 4, 6, 2),
            WidgetResizeGeometry.resize(original, ResizeCorner.TOP_RIGHT, 2, 1, 14, 10),
        )
        assertEquals(
            WidgetGridRect(1, 3, 5, 5),
            WidgetResizeGeometry.resize(original, ResizeCorner.BOTTOM_LEFT, -1, 2, 14, 10),
        )
    }

    @Test
    fun resizeClampsToTheGridAndOneCellMinimum() {
        val original = WidgetGridRect(x = 2, y = 3, width = 4, height = 3)

        assertEquals(
            WidgetGridRect(0, 0, 6, 6),
            WidgetResizeGeometry.resize(original, ResizeCorner.TOP_LEFT, -99, -99, 14, 10),
        )
        assertEquals(
            WidgetGridRect(2, 3, 1, 1),
            WidgetResizeGeometry.resize(original, ResizeCorner.BOTTOM_RIGHT, -99, -99, 14, 10),
        )
    }

    @Test
    fun backgroundDoubleTapExitsOnlyWhileEditing() {
        assertEquals(true, WidgetEditModePolicy.shouldExit(editing = true, tapCount = 2))
        assertEquals(false, WidgetEditModePolicy.shouldExit(editing = true, tapCount = 1))
        assertEquals(false, WidgetEditModePolicy.shouldExit(editing = false, tapCount = 2))
    }
}
