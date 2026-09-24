package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.min

/** Only well-known packages receive a monochrome replacement; other apps keep their own icons. */
internal object AppNerdIconPolicy {
    fun glyphFor(packageName: String): String? = when (packageName) {
        "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer",
        "com.miui.dialer", "com.truecaller" -> "\uF095"
        "com.android.camera", "com.google.android.GoogleCamera", "com.sec.android.app.camera",
        "org.codeaurora.snapcam" -> NerdGlyph.CAMERA
        "com.android.chrome", "com.chrome.beta" -> "\uF268"
        "org.mozilla.firefox", "org.mozilla.fenix" -> "\uF269"
        "com.whatsapp", "com.whatsapp.w4b" -> "\uF232"
        "com.brave.browser", "com.microsoft.emmx", "com.sec.android.app.sbrowser",
        "com.android.browser", "com.opera.browser", "com.opera.gx",
        "com.duckduckgo.mobile.android" -> "\uF0AC"
        "org.telegram.messenger" -> "\uF2C6"
        "org.thoughtcrime.securesms", "com.google.android.gm", "com.microsoft.office.outlook",
        "com.readdle.spark" -> "\uF0E0"
        "com.discord" -> "\uF1FF"
        "com.instagram.android" -> "\uF16D"
        "com.facebook.katana" -> "\uF09A"
        "com.facebook.orca" -> "\uF25F"
        "com.twitter.android" -> "\uDB82\uDF05"
        "com.reddit.frontpage" -> "\uF281"
        "com.linkedin.android" -> "\uF0E1"
        "com.Slack" -> "\uF198"
        "com.spotify.music" -> "\uF1BC"
        "com.google.android.youtube", "com.google.android.apps.youtube.creator" -> "\uF16A"
        "tv.twitch.android.app" -> "\uF1E8"
        "com.netflix.mediaclient", "com.disney.disneyplus", "com.amazon.avod.thirdpartyclient",
        "com.plexapp.android", "is.xyz.mpv", "com.kmplayer" -> "\uF008"
        "com.google.android.apps.maps" -> "\uF041"
        "com.waze" -> "\uEF9E"
        "com.google.android.calendar" -> "\uF073"
        "com.google.android.apps.nbu.files", "com.alphainventor.filemanager",
        "com.mi.android.globalFileexplorer" -> "\uF07B"
        "com.termux", "com.sonelli.juicessh", "com.server.auditor.ssh.client" -> "\uF120"
        "com.google.android.keep", "com.miui.notes", "md.obsidian" -> "\uF249"
        "com.google.android.calculator", "com.miui.calculator" -> "\uF1EC"
        "com.github.android" -> "\uF09B"
        "com.google.android.apps.docs" -> "\uF2DF"
        "com.google.android.apps.docs.editors.docs" -> "\uF15C"
        "com.google.android.apps.docs.editors.sheets" -> "\uF1C3"
        "com.google.android.apps.docs.editors.slides" -> "\uF1C4"
        "com.google.android.apps.authenticator2" -> "\uF084"
        "com.google.android.apps.photos", "com.miui.gallery", "com.niksoftware.snapseed" -> "\uF03E"
        "com.android.settings", "com.miui.securitycenter" -> "\uF013"
        "com.miui.weather2", "com.accuweather.android" -> "\uF0C2"
        "com.google.android.apps.meetings", "us.zoom.videomeetings",
        "com.microsoft.teams" -> "\uF03D"
        "com.vrem.wifianalyzer", "com.farproc.wifi.analyzer" -> "\uF1EB"
        "com.adobe.reader", "com.foxit.mobile.pdf.lite" -> "\uF1C1"
        "com.amazon.mShop.android.shopping", "com.mercadolibre" -> "\uF07A"
        else -> null
    }
}

/** Theme-colored Nerd Font icon with a compact surface, used only for mapped packages. */
internal class AppNerdIconDrawable(context: Context, private val glyph: String) : Drawable() {
    private val face = NerdFont.load(context)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = RectF()
    private var opacity = 255
    private var filter: ColorFilter? = null

    override fun draw(canvas: Canvas) {
        val side = min(bounds.width(), bounds.height()).toFloat()
        if (side <= 0f) return
        frame.set(
            bounds.exactCenterX() - side / 2f,
            bounds.exactCenterY() - side / 2f,
            bounds.exactCenterX() + side / 2f,
            bounds.exactCenterY() + side / 2f,
        )
        paint.apply {
            color = OmarchyUiTheme.color("lighter_background", 0xFF24283B.toInt())
            alpha = opacity
            colorFilter = filter
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(frame, side * 0.22f, side * 0.22f, paint)
        paint.apply {
            color = OmarchyUiTheme.color("accent", 0xFF7AA2F7.toInt())
            alpha = opacity
            typeface = face
            textAlign = Paint.Align.CENTER
            textSize = side * 0.55f
        }
        val metrics = paint.fontMetrics
        canvas.drawText(glyph, frame.centerX(), frame.centerY() - (metrics.ascent + metrics.descent) / 2f, paint)
    }

    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { filter = colorFilter; invalidateSelf() }
    override fun getIntrinsicWidth(): Int = 64
    override fun getIntrinsicHeight(): Int = 64
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
