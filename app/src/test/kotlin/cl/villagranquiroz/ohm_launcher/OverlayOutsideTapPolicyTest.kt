package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayOutsideTapPolicyTest {
    @Test
    fun blankSpaceInsideScrollViewportIsOutsideTheRenderedCard() {
        val bounds = OverlayContentBounds.withinScrollView(
            scrollLeft = 40f,
            scrollTop = 50f,
            scrollRight = 340f,
            scrollBottom = 650f,
            scrollX = 0f,
            scrollY = 0f,
            contentLeft = 0f,
            contentTop = 0f,
            contentRight = 320f,
            contentBottom = 400f,
        )

        assertTrue(bounds.contains(200f, 350f))
        assertFalse(bounds.contains(200f, 500f))
        assertFalse(bounds.contains(20f, 100f))
    }

    @Test
    fun scrollOffsetMovesTheRenderedCardHitBounds() {
        val bounds = OverlayContentBounds.withinScrollView(
            scrollLeft = 40f,
            scrollTop = 50f,
            scrollRight = 340f,
            scrollBottom = 650f,
            scrollX = 8f,
            scrollY = 120f,
            contentLeft = 0f,
            contentTop = 0f,
            contentRight = 320f,
            contentBottom = 600f,
        )

        assertTrue(bounds.contains(200f, 200f))
        assertFalse(bounds.contains(35f, 200f))
        assertFalse(bounds.contains(200f, 540f))
    }

    @Test
    fun dismissesOnStationaryTapOutsideTheCard() {
        val policy = OverlayOutsideTapPolicy(touchSlop = 12f)

        policy.onDown(x = 10f, y = 10f, outsideContent = true)

        assertTrue(policy.onUp(x = 11f, y = 10f, outsideContent = true))
    }

    @Test
    fun doesNotTreatOutsideSwipeAsDismissTap() {
        val policy = OverlayOutsideTapPolicy(touchSlop = 12f)

        policy.onDown(x = 10f, y = 10f, outsideContent = true)

        assertFalse(policy.onUp(x = 10f, y = 100f, outsideContent = true))
    }

    @Test
    fun doesNotDismissWhenGestureStartsInsideTheCard() {
        val policy = OverlayOutsideTapPolicy(touchSlop = 12f)

        policy.onDown(x = 50f, y = 50f, outsideContent = false)

        assertFalse(policy.onUp(x = 60f, y = 60f, outsideContent = true))
    }

    @Test
    fun cancelClearsThePendingTouchSequence() {
        val policy = OverlayOutsideTapPolicy(touchSlop = 12f)
        policy.onDown(x = 10f, y = 10f, outsideContent = true)

        policy.onCancel()

        assertFalse(policy.onUp(x = 10f, y = 10f, outsideContent = true))
    }
}
