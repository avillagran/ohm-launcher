package cl.villagranquiroz.ohm_launcher

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OmarchyMenuDynamicEntriesTest {
    private fun searchInput(view: View): EditText? {
        if (view is EditText) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            searchInput(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun menu(activity: Activity, entries: List<OmarchyMenuEntry>) = OmarchyMenuOverlay(
        activity, entries,
        colors = OmarchyMenuOverlay.Colors(0, 0, 0, 0, 0, 0, 0),
        onDismissed = {},
    ).also { activity.setContentView(it) }

    private fun enter(menu: OmarchyMenuOverlay) {
        searchInput(menu)!!.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
    }

    @Test fun liveMediaRowsBecomeSearchableWithoutClearingTheQuery() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var plays = 0
        val overlay = menu(activity, listOf(OmarchyMenuEntry("", "No desktop players yet")))
        val input = searchInput(overlay)!!
        input.setText("play")
        overlay.updateRootEntries(listOf(OmarchyMenuEntry("", "Play", action = { plays++ }, staysOpen = true)))

        assertEquals("play", input.text.toString())
        enter(overlay)
        assertEquals(1, plays)
    }

    @Test fun searchCannotExecuteAControlRemovedByALiveUpdate() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var pauses = 0
        val overlay = menu(activity, listOf(
            OmarchyMenuEntry("", "Pause", action = { pauses++ }, staysOpen = true)))
        searchInput(overlay)!!.setText("pause")
        overlay.updateRootEntries(listOf(OmarchyMenuEntry("", "No desktop players yet")))

        enter(overlay)
        assertEquals(0, pauses)
    }
}
