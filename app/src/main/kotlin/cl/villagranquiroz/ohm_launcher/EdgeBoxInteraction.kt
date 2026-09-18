package cl.villagranquiroz.ohm_launcher

enum class EdgeInteractionDecision {
    NONE,
    START_BOX_DRAG,
    ACCEPT_ITEM,
    OPEN_SETTINGS,
}

class EdgeBoxInteractionState(
    private val isItem: Boolean,
    private val downAtMillis: Long,
) {
    private var stillSinceMillis = downAtMillis
    private var itemAccepted = false
    private var boxDragStarted = false
    private var itemDragged = false
    private var wasMoving = false

    fun sample(nowMillis: Long, displacementPixels: Float): EdgeInteractionDecision {
        require(nowMillis >= downAtMillis)
        val moving = displacementPixels > STILLNESS_SLOP_PX
        if (moving && !wasMoving) stillSinceMillis = nowMillis
        if (moving && itemAccepted) itemDragged = true
        wasMoving = moving

        if (!boxDragStarted && itemAccepted && displacementPixels > STILLNESS_SLOP_PX) {
            boxDragStarted = true
            return EdgeInteractionDecision.START_BOX_DRAG
        }
        if (!itemAccepted && nowMillis - stillSinceMillis >= ITEM_ACCEPT_MILLIS) {
            itemAccepted = true
            return EdgeInteractionDecision.ACCEPT_ITEM
        }
        return EdgeInteractionDecision.NONE
    }

    companion object {
        const val STILLNESS_SLOP_PX = 8f
        const val ITEM_ACCEPT_MILLIS = 2_000L
    }
}

class EdgeBoxDoubleTapState(private val timeoutMillis: Long = 300L) {
    private var previousTapMillis: Long? = null

    fun registerTap(nowMillis: Long): Boolean {
        val previous = previousTapMillis
        val doubleTap = previous != null && nowMillis - previous in 0..timeoutMillis
        previousTapMillis = if (doubleTap) null else nowMillis
        return doubleTap
    }
}

object EdgeDropTarget {
    fun target(
        width: Int,
        height: Int,
        pointerX: Float,
        pointerY: Float,
        threshold: Float,
    ): EdgePosition? {
        require(width > 0 && height > 0 && threshold >= 0f)
        val candidates = listOf(
            EdgePosition.LEFT to pointerX,
            EdgePosition.RIGHT to width - pointerX,
            EdgePosition.TOP to pointerY,
            EdgePosition.BOTTOM to height - pointerY,
        )
        val nearest = candidates.minBy { it.second }
        return nearest.first.takeIf { nearest.second in 0f..threshold }
    }
}
