package cl.villagranquiroz.ohm_launcher

import android.content.Context
import androidx.appcompat.app.AlertDialog

/** Gives every launcher dialog the same live Omarchy surface. */
class OmarchyDialogBuilder(context: Context, private val opacity: () -> Double) : AlertDialog.Builder(context) {
    override fun show(): AlertDialog = super.show().also { SettingsDialogSurface.apply(it, opacity()) }
}