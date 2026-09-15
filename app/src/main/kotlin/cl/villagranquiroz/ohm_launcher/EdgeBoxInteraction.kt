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
    private var settingsOpened = false
    private var boxDragStarted = false
    private var itemDragged = false
    private var wasMoving = false

    fun sample(nowMillis: Long, displacementPixels: Float): EdgeInteractionDecision {
        require(nowMillis >= downAtMillis)
        val moving = displacementPixels > STILLNESS_SLOP_PX
        if (moving && !wasMoving) stillSinceMillis = nowMillis
        if (moving && itemAccepted) itemDragged = true
        wasMoving = moving

        if (!isItem && !boxDragStarted && displacementPixels >= BOX_DRAG_SLOP_PX) {
            boxDragStarted = true
            return EdgeInteractionDecision.START_BOX_DRAG
        }
        if (isItem && !itemAccepted && nowMillis - stillSinceMillis >= ITEM_ACCEPT_MILLIS) {
            itemAccepted = true
            return EdgeInteractionDecision.ACCEPT_ITEM
        }
        if (!settingsOpened && !boxDragStarted && !itemDragged && nowMillis - stillSinceMillis >= SETTINGS_MILLIS) {
            settingsOpened = true
            return EdgeInteractionDecision.OPEN_SETTINGS
        }
        return EdgeInteractionDecision.NONE
    }

    companion object {
        const val STILLNESS_SLOP_PX = 8f
        const val BOX_DRAG_SLOP_PX = 16f
        const val ITEM_ACCEPT_MILLIS = 2_000L
        const val SETTINGS_MILLIS = 3_500L
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
