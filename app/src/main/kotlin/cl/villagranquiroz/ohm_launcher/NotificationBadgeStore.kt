package cl.villagranquiroz.ohm_launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Per-package notification counts fed by [OhmNotificationListenerService].
 * Views register themselves and are refreshed automatically when the active
 * notification set changes.
 */
object NotificationBadgeStore {
    private val counts = ConcurrentHashMap<String, Int>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun updateAll(next: Map<String, Int>) {
        counts.clear()
        counts.putAll(next)
        listeners.forEach { it.invoke() }
    }

    fun countFor(packageName: String): Int = counts[packageName] ?: 0

    fun register(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }
}

/** Wraps an app icon so a neon badge with the pending notification count can
 *  sit on its top-end corner. Hidden automatically when there is nothing to
 *  show; capped at 99+ like the stock launchers. The badge listens for store
 *  changes only while the wrapped view is attached to the window. */
internal class AppIconWithBadge private constructor(
    val root: FrameLayout,
    private val badge: TextView,
    private val packageName: String,
) {
    private var unregister: (() -> Unit)? = null

    private fun refresh() {
        val count = NotificationBadgeStore.countFor(packageName)
        if (count <= 0) {
            badge.visibility = View.GONE
            return
        }
        badge.visibility = View.VISIBLE
        badge.text = if (count > 99) "99+" else count.toString()
        badge.contentDescription = badge.resources.getString(R.string.notification_badge, count)
    }

    companion object {
        fun wrap(
            context: Context,
            icon: ImageView,
            packageName: String,
            iconSizeDp: Int,
        ): AppIconWithBadge {
            val metrics = context.resources.displayMetrics
            fun dp(value: Int) = (value * metrics.density).toInt()
            val root = FrameLayout(context)
            val badge = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xFFFF4757.toInt())
                    setStroke(dp(1), 0x33FFFFFF)
                }
                minWidth = dp(18)
                minHeight = dp(18)
                visibility = View.GONE
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            root.addView(icon, FrameLayout.LayoutParams(dp(iconSizeDp), dp(iconSizeDp)))
            root.addView(
                badge,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END,
                ).apply {
                    val inset = dp(iconSizeDp / 5)
                    setMargins(inset, inset / 2, 0, 0)
                },
            )
            val wrapper = AppIconWithBadge(root, badge, packageName)
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    wrapper.unregister = NotificationBadgeStore.register(wrapper::refresh)
                    wrapper.refresh()
                }

                override fun onViewDetachedFromWindow(view: View) {
                    wrapper.unregister?.invoke()
                    wrapper.unregister = null
                }
            })
            return wrapper
        }
    }
}
