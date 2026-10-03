package cl.villagranquiroz.ohm_launcher

import android.app.Activity
import android.os.Looper
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class OmarchyDialogBuilderTest {
    @Test fun createdDialogUsesOmarchySurfaceWhenShownLater() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.setTheme(R.style.Theme_OhmLauncher)
        val dialog: AlertDialog = OmarchyDialogBuilder(activity) { 0.72 }
            .setTitle("Incoming Flux file")
            .create()
        dialog.show()
        shadowOf(Looper.getMainLooper()).idle()
        try {
            val window = dialog.window
            assertNotNull(window)
            assertEquals(SettingsDialogSurface.spec(activity, 0.72).widthPx, window!!.attributes.width)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, window.attributes.height)
        } finally { dialog.dismiss() }
    }
}
