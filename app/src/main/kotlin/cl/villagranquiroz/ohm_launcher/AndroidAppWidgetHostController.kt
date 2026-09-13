package cl.villagranquiroz.ohm_launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.view.ViewGroup
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/** Result of a bind request plus an intent when Android authorization is required. */
data class AndroidAppWidgetBindingLaunch(
    val request: AppWidgetBindingRequest,
    val permissionIntent: Intent? = null,
)

/**
 * Reusable Android AppWidget host.
 *
 * An owner may register this object as a lifecycle observer or call
 * [startListening] and [stopListening] directly.
 */
class AndroidAppWidgetHostController(
    context: Context,
    val hostId: Int = APP_WIDGET_HOST_ID,
) : DefaultLifecycleObserver {
    private val applicationContext = context.applicationContext
    private val manager = AppWidgetManager.getInstance(applicationContext)
    private val host = AppWidgetHost(applicationContext, hostId)
    private val listeningLifecycle = AppWidgetListeningLifecycle(
        object : AppWidgetListeningHost {
            override fun startListening() = host.startListening()
            override fun stopListening() = host.stopListening()
        },
    )
    private val bindingCoordinator = AppWidgetBindingCoordinator(
        host = object : AppWidgetIdHost {
            override fun allocateAppWidgetId(): Int = host.allocateAppWidgetId()
            override fun deleteAppWidgetId(appWidgetId: Int) = host.deleteAppWidgetId(appWidgetId)
        },
        binder = object : AppWidgetIdBinder {
            override fun bindIfAllowed(
                appWidgetId: Int,
                provider: AppWidgetProviderConfig,
            ): Boolean = manager.bindAppWidgetIdIfAllowed(appWidgetId, provider.toComponentName())

            override fun isBound(
                appWidgetId: Int,
                provider: AppWidgetProviderConfig,
            ): Boolean = manager.getAppWidgetInfo(appWidgetId)?.provider == provider.toComponentName()
        },
    )

    fun queryInstalledProviders(): List<AppWidgetProviderDto> =
        manager.installedProviders
            .map { info ->
                val provider = info.provider
                val fallbackLabel = provider.packageName
                val label = runCatching {
                    info.loadLabel(applicationContext.packageManager)?.toString()
                }.getOrNull().orEmpty().ifBlank { fallbackLabel }
                AppWidgetProviderDto(
                    label = label,
                    packageName = provider.packageName,
                    provider = provider.flattenToString(),
                    minWidth = info.minWidth,
                    minHeight = info.minHeight,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })

    fun requestBinding(provider: String): AndroidAppWidgetBindingLaunch {
        val request = bindingCoordinator.request(provider)
        val intent = if (request is AppWidgetBindingRequest.PermissionRequired) {
            val config = checkNotNull(AppWidgetProviderConfig.parse(request.provider))
            Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, request.appWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, config.toComponentName())
            }
        } else {
            null
        }
        return AndroidAppWidgetBindingLaunch(request, intent)
    }

    /** Completes the request identified by the ID returned from [requestBinding]. */
    fun completeBinding(
        appWidgetId: Int,
        approved: Boolean,
    ): AppWidgetBindingCompletion = bindingCoordinator.complete(appWidgetId, approved)

    fun deleteAppWidgetId(appWidgetId: Int) {
        bindingCoordinator.delete(appWidgetId)
    }

    fun createHostView(
        context: Context,
        appWidgetId: Int,
        size: AppWidgetHostViewSize,
    ): AppWidgetHostView? {
        val providerInfo = manager.getAppWidgetInfo(appWidgetId) ?: return null
        return host.createView(context, appWidgetId, providerInfo).also { view ->
            sizeHostView(view, size)
        }
    }

    fun createHostView(
        context: Context,
        appWidgetId: Int,
        sizeValues: Map<*, *>,
    ): AppWidgetHostView? {
        val size = AppWidgetHostViewSize.fromPlatformMap(
            sizeValues,
            context.resources.displayMetrics.density,
        ) ?: return null
        return createHostView(context, appWidgetId, size)
    }

    fun sizeHostView(view: AppWidgetHostView, size: AppWidgetHostViewSize) {
        view.layoutParams = ViewGroup.LayoutParams(size.widthPx, size.heightPx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.updateAppWidgetSize(
                Bundle.EMPTY,
                listOf(SizeF(size.widthDp.toFloat(), size.heightDp.toFloat())),
            )
        } else {
            updateLegacyAppWidgetSize(view, size)
        }
    }

    @Suppress("DEPRECATION")
    private fun updateLegacyAppWidgetSize(
        view: AppWidgetHostView,
        size: AppWidgetHostViewSize,
    ) {
        view.updateAppWidgetSize(
            Bundle.EMPTY,
            size.widthDp,
            size.heightDp,
            size.widthDp,
            size.heightDp,
        )
    }

    fun startListening() {
        listeningLifecycle.start()
    }

    fun stopListening() {
        listeningLifecycle.stop()
    }

    override fun onStart(owner: LifecycleOwner) {
        startListening()
    }

    override fun onStop(owner: LifecycleOwner) {
        stopListening()
    }

    companion object {
        const val APP_WIDGET_HOST_ID: Int = 0x0A0B0C
    }
}

private fun AppWidgetProviderConfig.toComponentName(): ComponentName =
    ComponentName(packageName, className)
