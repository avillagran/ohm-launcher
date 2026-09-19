package cl.villagranquiroz.ohm_launcher

enum class EdgeInteractionDecision {
    NONE,
    START_BOX_DRAG,
    ACCEPT_ITEM,
    ACCEPT_BOX,
    OPEN_SETTINGS,
}

class EdgeBoxInteractionState(
    private val isItem: Boolean,
    private val downAtMillis: Long,
) {
    private var stillSinceMillis = downAtMillis
    private var itemAccepted = false
    private var boxAccepted = false
    private var boxDragStarted = false
    private var wasMoving = false

    fun sample(nowMillis: Long, displacementPixels: Float): EdgeInteractionDecision {
        require(nowMillis >= downAtMillis)
        val moving = displacementPixels > PREARM_SLOP_PX
        if (moving && !wasMoving) stillSinceMillis = nowMillis
        wasMoving = moving

        if (!boxDragStarted && itemAccepted && displacementPixels > STILLNESS_SLOP_PX) {
            boxDragStarted = true
            return EdgeInteractionDecision.START_BOX_DRAG
        }
        if (!boxDragStarted && isItem && !itemAccepted && nowMillis - stillSinceMillis >= ITEM_ACCEPT_MILLIS) {
            itemAccepted = true
            return EdgeInteractionDecision.ACCEPT_ITEM
        }
        if (!boxDragStarted && !boxAccepted && nowMillis - stillSinceMillis >= BOX_ACCEPT_MILLIS) {
            itemAccepted = true
            boxAccepted = true
            return EdgeInteractionDecision.ACCEPT_BOX
        }
        if (boxAccepted && !boxDragStarted && nowMillis - stillSinceMillis >= SETTINGS_MILLIS) {
            return EdgeInteractionDecision.OPEN_SETTINGS
        }
        return EdgeInteractionDecision.NONE
    }

    companion object {
        const val STILLNESS_SLOP_PX = 8f
        const val PREARM_SLOP_PX = 24f
        const val ITEM_ACCEPT_MILLIS = 500L
        const val BOX_ACCEPT_MILLIS = 1_500L
        const val SETTINGS_MILLIS = 2_500L
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
