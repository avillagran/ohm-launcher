package cl.villagranquiroz.ohm_launcher

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FluxTouchpadGestureIsolationTest {
    private fun swipeDown(root: NativeLauncherView) {
        val downTime = SystemClock.uptimeMillis()
        listOf(
            Triple(MotionEvent.ACTION_DOWN, 80f, 0L),
            Triple(MotionEvent.ACTION_MOVE, 280f, 16L),
            Triple(MotionEvent.ACTION_UP, 560f, 32L),
        ).forEach { (action, y, delay) ->
            val event = MotionEvent.obtain(downTime, downTime + delay, action, 300f, y, 0)
            try { root.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }

    @Test fun fullScreenTouchpadSwipesDoNotTriggerLauncherGestures() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val root = NativeLauncherView(activity)
        activity.setContentView(root)
        root.layout(0, 0, 600, 1000)
        var launcherRequests = 0
        root.onQuakeRequested = { launcherRequests++ }

        swipeDown(root)
        assertEquals("The baseline swipe must exercise the launcher gesture", 1, launcherRequests)

        val touchpad = FluxTouchpadView(
            activity,
            OmarchyThemePalette("Omarchy", OmarchyThemeMode.DARK, emptyMap()),
            FluxTouchpadView.Labels(
                "Remote", "Touchpad", "Scroll", "Left", "Double", "Right", "Middle",
                "Text", "Send", "Close", "Enter", "Backspace", "Tab", "Escape",
                "Up", "Down", "Left arrow", "Right arrow",
            ),
            canSend = { false }, sendAction = {}, onClosed = {},
        )
        root.addView(touchpad, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        swipeDown(root)
        assertEquals("Touchpad swipes must never reach the launcher gesture detector", 1, launcherRequests)

        root.removeView(touchpad)
        swipeDown(root)
        assertEquals("Removing the touchpad restores launcher swipes", 2, launcherRequests)
    }

}
