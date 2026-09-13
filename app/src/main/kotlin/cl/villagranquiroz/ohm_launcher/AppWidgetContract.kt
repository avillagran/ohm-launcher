package cl.villagranquiroz.ohm_launcher

import kotlin.math.roundToInt

/** Validated, Android-independent representation of an AppWidget provider component. */
data class AppWidgetProviderConfig(
    val packageName: String,
    val className: String,
    val flattenedName: String,
) {
    companion object {
        private val qualifiedName = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

        fun parse(value: String): AppWidgetProviderConfig? {
            if (value.isBlank() || value.count { it == '/' } != 1) return null
            val (packageName, encodedClassName) = value.split('/', limit = 2)
            if (!qualifiedName.matches(packageName)) return null

            val className = when {
                encodedClassName.startsWith('.') -> packageName + encodedClassName
                qualifiedName.matches(encodedClassName) -> encodedClassName
                else -> return null
            }
            if (!qualifiedName.matches(className)) return null
            return AppWidgetProviderConfig(packageName, className, value)
        }
    }
}

/** Stable provider payload shared with launcher UI adapters. Dimensions are pixels. */
data class AppWidgetProviderDto(
    val label: String,
    val packageName: String,
    val provider: String,
    val minWidth: Int,
    val minHeight: Int,
) {
    fun toPlatformMap(): Map<String, Any> = mapOf(
        "label" to label,
        "package" to packageName,
        "provider" to provider,
        "minWidth" to minWidth,
        "minHeight" to minHeight,
    )
}

/** Pixel layout and density-independent options for an AppWidget host view. */
class AppWidgetHostViewSize private constructor(
    val widthPx: Int,
    val heightPx: Int,
    val widthDp: Int,
    val heightDp: Int,
) {
    companion object {
        fun fromPlatformMap(values: Map<*, *>, density: Float): AppWidgetHostViewSize? {
            if (!density.isFinite() || density <= 0f) return null
            val widthPx = positiveInt(values["width"]) ?: return null
            val heightPx = positiveInt(values["height"]) ?: return null
            return AppWidgetHostViewSize(
                widthPx = widthPx,
                heightPx = heightPx,
                widthDp = (widthPx / density).roundToInt().coerceAtLeast(1),
                heightDp = (heightPx / density).roundToInt().coerceAtLeast(1),
            )
        }

        private fun positiveInt(value: Any?): Int? {
            val number = value as? Number ?: return null
            val doubleValue = number.toDouble()
            if (!doubleValue.isFinite() || doubleValue <= 0 || doubleValue > Int.MAX_VALUE) return null
            val intValue = doubleValue.toInt()
            return intValue.takeIf { it.toDouble() == doubleValue }
        }
    }
}

interface AppWidgetListeningHost {
    fun startListening()
    fun stopListening()
}

/** Makes host listening safe to wire to repeated owner lifecycle callbacks. */
class AppWidgetListeningLifecycle(
    private val host: AppWidgetListeningHost,
) {
    private var listening = false

    @Synchronized
    fun start() {
        if (listening) return
        host.startListening()
        listening = true
    }

    @Synchronized
    fun stop() {
        if (!listening) return
        host.stopListening()
        listening = false
    }
}

interface AppWidgetIdHost {
    fun allocateAppWidgetId(): Int
    fun deleteAppWidgetId(appWidgetId: Int)
}

interface AppWidgetIdBinder {
    fun bindIfAllowed(appWidgetId: Int, provider: AppWidgetProviderConfig): Boolean
    fun isBound(appWidgetId: Int, provider: AppWidgetProviderConfig): Boolean
}

sealed interface AppWidgetBindingRequest {
    data class Bound(val appWidgetId: Int, val provider: String) : AppWidgetBindingRequest
    data class PermissionRequired(val appWidgetId: Int, val provider: String) : AppWidgetBindingRequest
    data class InvalidProvider(val provider: String) : AppWidgetBindingRequest
}

sealed interface AppWidgetBindingCompletion {
    data class Bound(val appWidgetId: Int, val provider: String) : AppWidgetBindingCompletion
    data class Failed(val appWidgetId: Int, val provider: String) : AppWidgetBindingCompletion
    data class UnknownRequest(val appWidgetId: Int) : AppWidgetBindingCompletion
}

/** Owns allocation and cleanup for the request/permission/completion binding contract. */
class AppWidgetBindingCoordinator(
    private val host: AppWidgetIdHost,
    private val binder: AppWidgetIdBinder,
) {
    private val pending = mutableMapOf<Int, AppWidgetProviderConfig>()

    @Synchronized
    fun request(provider: String): AppWidgetBindingRequest {
        val config = AppWidgetProviderConfig.parse(provider)
            ?: return AppWidgetBindingRequest.InvalidProvider(provider)
        val appWidgetId = host.allocateAppWidgetId()
        return try {
            if (binder.bindIfAllowed(appWidgetId, config)) {
                AppWidgetBindingRequest.Bound(appWidgetId, config.flattenedName)
            } else {
                pending[appWidgetId] = config
                AppWidgetBindingRequest.PermissionRequired(appWidgetId, config.flattenedName)
            }
        } catch (error: RuntimeException) {
            runCatching { host.deleteAppWidgetId(appWidgetId) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    @Synchronized
    fun complete(appWidgetId: Int, approved: Boolean): AppWidgetBindingCompletion {
        val config = pending.remove(appWidgetId)
            ?: return AppWidgetBindingCompletion.UnknownRequest(appWidgetId)
        val bound = try {
            approved && (
                binder.isBound(appWidgetId, config) || binder.bindIfAllowed(appWidgetId, config)
            )
        } catch (error: RuntimeException) {
            runCatching { host.deleteAppWidgetId(appWidgetId) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
        return if (bound) {
            AppWidgetBindingCompletion.Bound(appWidgetId, config.flattenedName)
        } else {
            host.deleteAppWidgetId(appWidgetId)
            AppWidgetBindingCompletion.Failed(appWidgetId, config.flattenedName)
        }
    }

    @Synchronized
    fun delete(appWidgetId: Int) {
        pending.remove(appWidgetId)
        host.deleteAppWidgetId(appWidgetId)
    }
}
