package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeBoxInteractionTest {
    @Test
    fun boxMovementStartsImmediatelyAfterSixteenPixels() {
        val state = EdgeBoxInteractionState(isItem = false, downAtMillis = 0)

        assertEquals(EdgeInteractionDecision.NONE, state.sample(100, 15f))
        assertEquals(EdgeInteractionDecision.START_BOX_DRAG, state.sample(110, 16f))
    }

    @Test
    fun itemAcceptsAtNineHundredMillisecondsAndSettingsAtTwoSecondsStill() {
        val state = EdgeBoxInteractionState(isItem = true, downAtMillis = 0)

        assertEquals(EdgeInteractionDecision.NONE, state.sample(899, 0f))
        assertEquals(EdgeInteractionDecision.ACCEPT_ITEM, state.sample(900, 0f))
        assertEquals(EdgeInteractionDecision.OPEN_SETTINGS, state.sample(2_000, 0f))
    }

    @Test
    fun movementAboveEightPixelsRestartsTheSettingsStillnessTimer() {
        val state = EdgeBoxInteractionState(isItem = true, downAtMillis = 0)

        assertEquals(EdgeInteractionDecision.NONE, state.sample(500, 9f))
        assertEquals(EdgeInteractionDecision.ACCEPT_ITEM, state.sample(900, 0f))
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
