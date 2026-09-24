package cl.villagranquiroz.ohm_launcher

internal data class OverlayContentBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x <= right && y >= top && y <= bottom

    companion object {
        fun withinScrollView(
            scrollLeft: Float,
            scrollTop: Float,
            scrollRight: Float,
            scrollBottom: Float,
            scrollX: Float,
            scrollY: Float,
            contentLeft: Float,
            contentTop: Float,
            contentRight: Float,
            contentBottom: Float,
        ) = OverlayContentBounds(
            left = maxOf(scrollLeft, scrollLeft + contentLeft - scrollX),
            top = maxOf(scrollTop, scrollTop + contentTop - scrollY),
            right = minOf(scrollRight, scrollLeft + contentRight - scrollX),
            bottom = minOf(scrollBottom, scrollTop + contentBottom - scrollY),
        )
    }
}

internal class OverlayOutsideTapPolicy(private val touchSlop: Float) {
    private var downX: Float? = null
    private var downY: Float? = null
    private var downOutsideContent = false
    private var movedBeyondTouchSlop = false

    fun onDown(x: Float, y: Float, outsideContent: Boolean) {
        downX = x
        downY = y
        downOutsideContent = outsideContent
        movedBeyondTouchSlop = false
    }

    fun onMove(x: Float, y: Float) {
        val startX = downX ?: return
        val startY = downY ?: return
        val dx = x - startX
        val dy = y - startY
        if (dx * dx + dy * dy > touchSlop * touchSlop) {
            movedBeyondTouchSlop = true
        }
    }

    fun onUp(x: Float, y: Float, outsideContent: Boolean): Boolean {
        val startX = downX
        val startY = downY
        val isOutsideTap = startX != null && startY != null && downOutsideContent &&
            outsideContent && !movedBeyondTouchSlop &&
            distanceSquared(x - startX, y - startY) <= touchSlop * touchSlop
        reset()
        return isOutsideTap
    }

    fun onCancel() = reset()

    private fun reset() {
        downX = null
        downY = null
        downOutsideContent = false
        movedBeyondTouchSlop = false
    }

    private fun distanceSquared(dx: Float, dy: Float): Float = dx * dx + dy * dy
}
