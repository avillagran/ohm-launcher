package cl.villagranquiroz.ohm_launcher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.File

/** Keeps Android's home wallpaper aligned with the launcher's selected background. */
internal object SystemWallpaperSync {
    fun desiredKey(backgroundImage: String, fallbackColor: String): String {
        val file = resolveFile(backgroundImage)
        return if (file != null) {
            "file:${file.absolutePath}:${file.length()}:${file.lastModified()}"
        } else {
            "color:${fallbackColor.lowercase()}"
        }
    }

    fun appliedKey(backgroundImage: String, fallbackColor: String, imageApplied: Boolean): String =
        if (imageApplied) desiredKey(backgroundImage, fallbackColor)
        else desiredKey("", fallbackColor)

    fun apply(context: Context, backgroundImage: String): Boolean {
        val file = resolveFile(backgroundImage) ?: return false
        val bitmap = decodePreview(file) ?: return false
        return applyBitmap(context, bitmap)
    }

    fun applyColor(context: Context, color: String): Boolean {
        val parsed = runCatching { Color.parseColor(color) }.getOrNull() ?: return false
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(parsed) }
        return applyBitmap(context, bitmap)
    }

    internal fun isVideo(file: File): Boolean =
        file.extension.lowercase() in setOf("mp4", "webm", "mkv", "m4v", "mov", "avi")

    private fun resolveFile(value: String): File? {
        if (value.isBlank()) return null
        val uri = Uri.parse(value)
        if (uri.scheme != null && uri.scheme != "file") return null
        return File(uri.path ?: value).takeIf(File::isFile)
    }

    private fun decodePreview(file: File): Bitmap? = runCatching {
        if (isVideo(file)) {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(file.absolutePath)
                    frameAtTime
                } finally {
                    release()
                }
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 2160 || bounds.outHeight / sample > 4800) sample *= 2
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }
    }.getOrNull()

    private fun applyBitmap(context: Context, bitmap: Bitmap): Boolean = try {
        val manager = WallpaperManager.getInstance(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
        } else {
            @Suppress("DEPRECATION")
            manager.setBitmap(bitmap)
        }
        true
    } catch (_: Exception) {
        false
    } finally {
        bitmap.recycle()
    }
}
