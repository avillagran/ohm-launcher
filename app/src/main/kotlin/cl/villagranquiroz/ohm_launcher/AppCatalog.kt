package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache

data class InstalledApp(
    val label: String,
    val packageName: String,
    val activityName: String,
)

object AppCatalog {
    private val iconCache = LruCache<String, Drawable.ConstantState>(128)

    fun query(context: Context): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val flags = if (android.os.Build.VERSION.SDK_INT >= 33) {
            PackageManager.ResolveInfoFlags.of(0)
        } else {
            @Suppress("DEPRECATION")
            0
        }
        val resolved = if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(intent, flags as PackageManager.ResolveInfoFlags)
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, flags as Int)
        }
        return normalize(
            resolved.map {
                InstalledApp(
                    label = it.loadLabel(context.packageManager).toString(),
                    packageName = it.activityInfo.packageName,
                    activityName = it.activityInfo.name,
                )
            }.filterNot { it.packageName == context.packageName },
        )
    }

    fun normalize(apps: List<InstalledApp>): List<InstalledApp> =
        apps.distinctBy { "${it.packageName}/${it.activityName}" }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    fun launch(context: Context, app: InstalledApp) {
        val intent = Intent().setClassName(app.packageName, app.activityName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(intent)
    }

    fun icon(context: Context, app: InstalledApp): Drawable? {
        val key = "${app.packageName}/${app.activityName}"
        iconCache.get(key)?.let { return it.newDrawable(context.resources) }
        val icon = runCatching {
            context.packageManager.getActivityIcon(ComponentName(app.packageName, app.activityName))
        }.recoverCatching {
            context.packageManager.getApplicationIcon(app.packageName)
        }.getOrNull()
        icon?.constantState?.let { iconCache.put(key, it) }
        return icon
    }
}
