package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeBoxInteractionTest {
    @Test
    fun boxMovementRequiresTwoSecondHoldBeforeDragging() {
        val state = EdgeBoxInteractionState(isItem = false, downAtMillis = 0)

        assertEquals(EdgeInteractionDecision.NONE, state.sample(1_999, 0f))
        assertEquals(EdgeInteractionDecision.ACCEPT_ITEM, state.sample(2_000, 0f))
        assertEquals(EdgeInteractionDecision.START_BOX_DRAG, state.sample(2_010, 9f))
    }

    @Test
    fun itemAcceptsAtTwoSecondsWithoutOpeningSettingsOnContinuedHold() {
        val state = EdgeBoxInteractionState(isItem = true, downAtMillis = 0)

        assertEquals(EdgeInteractionDecision.NONE, state.sample(1_999, 0f))
        assertEquals(EdgeInteractionDecision.ACCEPT_ITEM, state.sample(2_000, 0f))
        assertEquals(EdgeInteractionDecision.NONE, state.sample(3_500, 0f))
    }

    @Test
    fun movementAboveEightPixelsRestartsTheHoldTimer() {
        val state = EdgeBoxInteractionState(isItem = true, downAtMillis = 0)

        assertEquals(EdgeInteractionDecision.NONE, state.sample(500, 9f))
        assertEquals(EdgeInteractionDecision.NONE, state.sample(2_499, 0f))
        assertEquals(EdgeInteractionDecision.ACCEPT_ITEM, state.sample(2_500, 0f))
        assertEquals(EdgeInteractionDecision.NONE, state.sample(4_000, 0f))
    }

    @Test
    fun movingAnAcceptedItemStartsItsDrag() {
        val state = EdgeBoxInteractionState(isItem = true, downAtMillis = 0)

        assertEquals(EdgeInteractionDecision.ACCEPT_ITEM, state.sample(2_000, 0f))
        assertEquals(EdgeInteractionDecision.START_BOX_DRAG, state.sample(2_050, 20f))
        assertEquals(EdgeInteractionDecision.NONE, state.sample(8_000, 0f))
    }

    @Test
    fun secondTapWithinTimeoutOpensBoxMenu() {
        val taps = EdgeBoxDoubleTapState()

        assertEquals(false, taps.registerTap(100L))
        assertEquals(true, taps.registerTap(350L))
        assertEquals(false, taps.registerTap(900L))
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
