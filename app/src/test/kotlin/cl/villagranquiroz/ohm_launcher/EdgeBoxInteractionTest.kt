package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeBoxInteractionTest {
    @Test
    fun boxMovementRequiresOnePointFiveSecondHoldBeforeDragging() {
        val state = EdgeBoxInteractionState(isItem = false, downAtMillis = 0)

        assertEquals(EdgeInteractionDecision.NONE, state.sample(1_499, 0f))
        assertEquals(EdgeInteractionDecision.ACCEPT_BOX, state.sample(1_500, 0f))
        assertEquals(EdgeInteractionDecision.START_BOX_DRAG, state.sample(1_510, 9f))
    }

    @Test
    fun continuingToHoldAnItemAdvancesFromItemToBoxToSettings() {
        val state = EdgeBoxInteractionState(isItem = true, downAtMillis = 0)

        assertEquals(EdgeInteractionDecision.NONE, state.sample(499, 0f))
        assertEquals(EdgeInteractionDecision.ACCEPT_ITEM, state.sample(500, 0f))
        assertEquals(EdgeInteractionDecision.ACCEPT_BOX, state.sample(1_500, 0f))
        assertEquals(EdgeInteractionDecision.OPEN_SETTINGS, state.sample(2_500, 0f))
    }

    @Test
    fun slightMovementDoesNotCancelIconActivation() {
        val state = EdgeBoxInteractionState(isItem = true, downAtMillis = 0)

        assertEquals(EdgeInteractionDecision.NONE, state.sample(200, 20f))
        assertEquals(EdgeInteractionDecision.ACCEPT_ITEM, state.sample(500, 20f))
    }

    @Test
    fun movingAnAcceptedItemStartsItsDrag() {
        val state = EdgeBoxInteractionState(isItem = true, downAtMillis = 0)

        assertEquals(EdgeInteractionDecision.ACCEPT_ITEM, state.sample(500, 0f))
        assertEquals(EdgeInteractionDecision.START_BOX_DRAG, state.sample(550, 20f))
        assertEquals(EdgeInteractionDecision.NONE, state.sample(8_000, 0f))
    }

    @Test
    fun holdingBoxForTwoPointFiveSecondsOpensItsMenu() {
        val state = EdgeBoxInteractionState(isItem = false, downAtMillis = 0)

        assertEquals(EdgeInteractionDecision.ACCEPT_BOX, state.sample(1_500, 0f))
        assertEquals(EdgeInteractionDecision.NONE, state.sample(2_499, 0f))
        assertEquals(EdgeInteractionDecision.OPEN_SETTINGS, state.sample(2_500, 0f))
    }

    @Test
    fun edgeDropRequiresPointerWithinTargetBand() {
        assertEquals(EdgePosition.LEFT, EdgeDropTarget.target(1000, 2000, 100f, 900f, 120f))
        assertEquals(EdgePosition.RIGHT, EdgeDropTarget.target(1000, 2000, 900f, 900f, 120f))
        assertEquals(EdgePosition.TOP, EdgeDropTarget.target(1000, 2000, 500f, 100f, 120f))
        assertEquals(EdgePosition.BOTTOM, EdgeDropTarget.target(1000, 2000, 500f, 1_900f, 120f))
        assertEquals(null, EdgeDropTarget.target(1000, 2000, 500f, 1_000f, 120f))
    }
}
