package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil

internal data class GridPixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class WidgetGridCell(val column: Int, val row: Int)
internal data class WidgetGridRect(val x: Int, val y: Int, val width: Int, val height: Int)

internal enum class ResizeCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

internal object WidgetEditModePolicy {
    fun shouldExit(editing: Boolean, tapCount: Int): Boolean = editing && tapCount >= 2
}

internal object WidgetResizeGeometry {
    fun resize(
        rect: WidgetGridRect,
        corner: ResizeCorner,
        deltaColumns: Int,
        deltaRows: Int,
        gridColumns: Int,
        gridRows: Int,
    ): WidgetGridRect {
        require(gridColumns > 0 && gridRows > 0)
        val left = rect.x.coerceIn(0, gridColumns - 1)
        val top = rect.y.coerceIn(0, gridRows - 1)
        val right = (left + rect.width.coerceAtLeast(1)).coerceAtMost(gridColumns)
        val bottom = (top + rect.height.coerceAtLeast(1)).coerceAtMost(gridRows)
        val resizedLeft = if (corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.BOTTOM_LEFT) {
            (left + deltaColumns).coerceIn(0, right - 1)
        } else left
        val resizedRight = if (corner == ResizeCorner.TOP_RIGHT || corner == ResizeCorner.BOTTOM_RIGHT) {
            (right + deltaColumns).coerceIn(left + 1, gridColumns)
        } else right
        val resizedTop = if (corner == ResizeCorner.TOP_LEFT || corner == ResizeCorner.TOP_RIGHT) {
            (top + deltaRows).coerceIn(0, bottom - 1)
        } else top
        val resizedBottom = if (corner == ResizeCorner.BOTTOM_LEFT || corner == ResizeCorner.BOTTOM_RIGHT) {
            (bottom + deltaRows).coerceIn(top + 1, gridRows)
        } else bottom
        return WidgetGridRect(
            resizedLeft,
            resizedTop,
            resizedRight - resizedLeft,
            resizedBottom - resizedTop,
        )
    }
}

internal object WidgetGridGeometry {
    fun bounds(
        width: Int,
        height: Int,
        columns: Int,
        rows: Int,
        x: Int,
        y: Int,
        cellWidth: Int,
        cellHeight: Int,
    ): GridPixelBounds {
        require(width >= 0 && height >= 0 && columns > 0 && rows > 0)
        val left = (x.coerceIn(0, columns - 1) * width) / columns
        val top = (y.coerceIn(0, rows - 1) * height) / rows
        val rightColumn = (x.coerceIn(0, columns - 1) + cellWidth.coerceAtLeast(1)).coerceAtMost(columns)
        val bottomRow = (y.coerceIn(0, rows - 1) + cellHeight.coerceAtLeast(1)).coerceAtMost(rows)
        val right = ceil(rightColumn * width.toDouble() / columns).toInt()
        val bottom = ceil(bottomRow * height.toDouble() / rows).toInt()
        return GridPixelBounds(left, top, right, bottom)
    }

    fun cellAt(width: Int, height: Int, columns: Int, rows: Int, x: Float, y: Float): WidgetGridCell {
        require(width > 0 && height > 0 && columns > 0 && rows > 0)
        return WidgetGridCell(
            ((x / width) * columns).toInt().coerceIn(0, columns - 1),
            ((y / height) * rows).toInt().coerceIn(0, rows - 1),
        )
    }

    fun movedCell(
        width: Int,
        height: Int,
        columns: Int,
        rows: Int,
        currentLeft: Int,
        currentTop: Int,
        deltaX: Float,
        deltaY: Float,
    ): WidgetGridCell = cellAt(
        width,
        height,
        columns,
        rows,
        currentLeft + deltaX,
        currentTop + deltaY,
    )
}

/** Fixed 14×10 launcher grid that lays out native widget views from persisted cell geometry. */
class WidgetGridLayout @JvmOverloads constructor(
    context: Context,
    attributes: AttributeSet? = null,
) : ViewGroup(context, attributes) {
    internal var columns: Int = DEFAULT_COLUMNS
        private set
    internal var rows: Int = DEFAULT_ROWS
        private set

    private class GridParams(
        var column: Int,
        var row: Int,
        var columns: Int,
        var rows: Int,
        val centered: Boolean,
    ) : LayoutParams(WRAP_CONTENT, WRAP_CONTENT)

    fun addWidget(view: View, node: WidgetNode) {
        val width = if (node.raw.has("w")) node.width else node.span ?: node.width
        addView(view, GridParams(node.x, node.y, width, node.height, false))
    }

    fun addCentered(view: View) {
        addView(view, GridParams(0, 0, columns, rows, true))
    }

    fun configureGrid(columns: Int, rows: Int) {
        this.columns = columns.coerceIn(1, 64)
        this.rows = rows.coerceIn(1, 64)
        requestLayout()
    }

    internal fun cellAt(x: Float, y: Float): WidgetGridCell = WidgetGridGeometry.cellAt(
        (width - paddingLeft - paddingRight).coerceAtLeast(1),
        (height - paddingTop - paddingBottom).coerceAtLeast(1),
        columns,
        rows,
        x - paddingLeft,
        y - paddingTop,
    )

    internal fun movedCell(view: View, deltaX: Float, deltaY: Float): WidgetGridCell =
        WidgetGridGeometry.movedCell(
            width = (width - paddingLeft - paddingRight).coerceAtLeast(1),
            height = (height - paddingTop - paddingBottom).coerceAtLeast(1),
            columns = columns,
            rows = rows,
            currentLeft = view.left - paddingLeft,
            currentTop = view.top - paddingTop,
            deltaX = deltaX,
            deltaY = deltaY,
        )

    internal fun previewWidget(view: View, rect: WidgetGridRect) {
        val params = view.layoutParams as? GridParams ?: return
        params.column = rect.x
        params.row = rect.y
        params.columns = rect.width
        params.rows = rect.height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
        val availableWidth = (measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val params = child.layoutParams as GridParams
            if (params.centered) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(availableWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST),
                )
            } else {
                val bounds = WidgetGridGeometry.bounds(
                    availableWidth, availableHeight, columns, rows,
                    params.column, params.row, params.columns, params.rows,
                )
                child.measure(
                    MeasureSpec.makeMeasureSpec((bounds.right - bounds.left).coerceAtLeast(1), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec((bounds.bottom - bounds.top).coerceAtLeast(1), MeasureSpec.EXACTLY),
                )
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val availableWidth = (right - left - paddingLeft - paddingRight).coerceAtLeast(0)
        val availableHeight = (bottom - top - paddingTop - paddingBottom).coerceAtLeast(0)
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val params = child.layoutParams as GridParams
            if (params.centered) {
                val childLeft = paddingLeft + (availableWidth - child.measuredWidth) / 2
                val childTop = paddingTop + (availableHeight - child.measuredHeight) / 2
                child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
            } else {
                val bounds = WidgetGridGeometry.bounds(
                    availableWidth, availableHeight, columns, rows,
                    params.column, params.row, params.columns, params.rows,
                )
                child.layout(
                    paddingLeft + bounds.left,
                    paddingTop + bounds.top,
                    paddingLeft + bounds.right,
                    paddingTop + bounds.bottom,
                )
            }
        }
    }

    companion object {
        const val DEFAULT_COLUMNS = 14
        const val DEFAULT_ROWS = 10
    }
}
