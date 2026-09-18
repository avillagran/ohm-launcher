package cl.villagranquiroz.ohm_launcher

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal object ScreenSharePermissionPolicy {
    fun shouldPrompt(started: Boolean, accessibilityEnabled: Boolean): Boolean =
        started && !accessibilityEnabled
}

internal object LauncherSystemBarPolicy {
    fun navigationBarColor(): Int = 0x00000000
}

internal class LauncherBarDragState {
    private var dragging = false

    fun update(displacement: Float): Boolean {
        if (displacement >= DRAG_SLOP_PX) dragging = true
        return dragging
    }

    companion object {
        const val DRAG_SLOP_PX = 16f
    }
}

internal object SharedEdgeLayout {
    fun centerOffsets(sizes: List<Int>, spacing: Int): List<Float> {
        if (sizes.isEmpty()) return emptyList()
        val total = sizes.sum() + spacing.coerceAtLeast(0) * (sizes.size - 1)
        var cursor = -total / 2f
        return sizes.map { size ->
            val center = cursor + size / 2f
            cursor += size + spacing.coerceAtLeast(0)
            center
        }
    }
}

enum class LauncherVerticalAction {
    NONE,
    OPEN_OMARCHY_MENU,
    CLOSE_DRAWER,
    OPEN_QUAKE,
    CLOSE_QUAKE,
}

object LauncherGesturePolicy {
    fun verticalAction(
        quakeVisible: Boolean,
        drawerVisible: Boolean,
        deltaY: Float,
        startedInLowerHalf: Boolean,
    ): LauncherVerticalAction {
        if (abs(deltaY) < 100f) return LauncherVerticalAction.NONE
        if (quakeVisible) {
            return if (deltaY < 0f) LauncherVerticalAction.CLOSE_QUAKE else LauncherVerticalAction.NONE
        }
        if (drawerVisible) {
            return if (deltaY > 0f) LauncherVerticalAction.CLOSE_DRAWER else LauncherVerticalAction.NONE
        }
        return when {
            deltaY < 0f && startedInLowerHalf -> LauncherVerticalAction.OPEN_OMARCHY_MENU
            deltaY > 0f && !startedInLowerHalf -> LauncherVerticalAction.OPEN_QUAKE
            else -> LauncherVerticalAction.NONE
        }
    }
}

object DrawerTapPolicy {
    fun mayLaunchApp(verticalDragDistance: Float, touchSlop: Float): Boolean =
        abs(verticalDragDistance) <= touchSlop
}

object CommandBarFocusPolicy {
    fun shouldDismiss(hasFocus: Boolean, backgroundPressed: Boolean): Boolean =
        hasFocus && backgroundPressed
}

enum class BackgroundDoubleTapAction { OPEN_OMARCHY_MENU, EXIT_EDIT_AND_OPEN_OMARCHY_MENU }

object BackgroundTapPolicy {
    fun onDoubleTap(editing: Boolean): BackgroundDoubleTapAction =
        if (editing) BackgroundDoubleTapAction.EXIT_EDIT_AND_OPEN_OMARCHY_MENU
        else BackgroundDoubleTapAction.OPEN_OMARCHY_MENU

    fun onLongPress(editing: Boolean): BackgroundDoubleTapAction = onDoubleTap(editing)
}

object WidgetEditExitPolicy {
    fun consumeBack(editing: Boolean): Boolean = editing
}

object LauncherGestureGate {
    fun routeToDesktop(widgetEditing: Boolean, selectorVisible: Boolean): Boolean =
        !widgetEditing && !selectorVisible
}

data class BarInsets(val left: Int, val top: Int, val right: Int, val bottom: Int)

object OmarchyBarInsets {
    @Suppress("UNUSED_PARAMETER")
    fun forEdge(edge: LauncherEdge, spacing: Int): BarInsets = BarInsets(0, 0, 0, 0)
}

data class SearchableApp(
    val key: String,
    val label: String,
    val packageName: String,
) {
    internal val normalizedLabel = normalizeSearchText(label)
    internal val normalizedPackage = normalizeSearchText(packageName)
}

class AppSearchIndex(entries: List<SearchableApp>) {
    private val entries = entries.toList()

    fun search(rawQuery: String, limit: Int): List<SearchableApp> {
        val query = normalizeSearchText(rawQuery)
        if (query.isEmpty() || limit <= 0) return emptyList()
        return entries.asSequence()
            .mapNotNull { entry ->
                val score = when {
                    entry.normalizedLabel == query -> 0
                    entry.normalizedLabel.startsWith(query) -> 1
                    entry.normalizedPackage.startsWith(query) -> 2
                    entry.normalizedLabel.contains(query) -> 3
                    entry.normalizedPackage.contains(query) -> 4
                    else -> return@mapNotNull null
                }
                Triple(score, entry.normalizedLabel.length, entry)
            }
            .sortedWith(compareBy<Triple<Int, Int, SearchableApp>> { it.first }.thenBy { it.second }.thenBy { it.third.normalizedLabel })
            .take(limit)
            .map { it.third }
            .toList()
    }
}

private fun normalizeSearchText(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace("\\p{M}+".toRegex(), "")
    .lowercase()
    .trim()

object QuakePanelGeometry {
    @Suppress("UNUSED_PARAMETER")
    fun height(screenHeight: Int, statusBar: Int, imeHeight: Int, desiredFraction: Double = 0.68): Int {
        if (screenHeight <= 0) return 0
        val desired = (screenHeight * desiredFraction).toInt()
        // The status bar is already represented by the panel's top padding.
        val available = (screenHeight - imeHeight.coerceAtLeast(0)).coerceAtLeast(0)
        return min(desired, available)
    }
}

object DesktopTransitionPolicy {
    fun entryDirection(previous: Int, next: Int): Float = when {
        next > previous -> 1f
        next < previous -> -1f
        else -> 0f
    }
}

object DesktopTitlePolicy {
    fun text(name: String, index: Int, desktopCount: Int): String? =
        if (desktopCount > 1) "$name  ${index + 1}/$desktopCount" else null
}

enum class OmarchyMenuAction(val label: String) {
    BLUETOOTH("Bluetooth"),
    SHOW_QR("Mostrar QR"),
    READ_QR("Leer QR"),
}

data class OrbitalMenuPoint(val x: Float, val y: Float, val ring: Int)

object OrbitalMenuMotion {
    const val CLOSE_REVEAL_DELAY_MILLIS = 1_000L

    fun actionDelayMillis(index: Int): Long = index.coerceAtLeast(0) * 70L

    fun closeCenter(
        releaseX: Float,
        releaseY: Float,
        width: Int,
        height: Int,
        closeWidth: Int,
        closeHeight: Int,
    ): OrbitalMenuPoint {
        val halfWidth = closeWidth.coerceAtLeast(0) / 2f
        val halfHeight = closeHeight.coerceAtLeast(0) / 2f
        return OrbitalMenuPoint(
            releaseX.coerceIn(halfWidth, (width - halfWidth).coerceAtLeast(halfWidth)),
            releaseY.coerceIn(halfHeight, (height - halfHeight).coerceAtLeast(halfHeight)),
            0,
        )
    }
}

object OrbitalMenuGeometry {
    fun positions(
        count: Int,
        width: Int,
        height: Int,
        anchorX: Float? = null,
        anchorY: Float? = null,
        itemWidth: Int = 0,
        itemHeight: Int = 0,
    ): List<OrbitalMenuPoint> {
        if (count <= 0 || width <= 0 || height <= 0) return emptyList()
        val base = min(width, height).toFloat()
        val firstRingCount = if (count <= 9) count else min(count, 8)
        val maximumRadius = base * if (count > firstRingCount) .44f else if (count == 9) .37f else .30f
        val halfItemWidth = itemWidth.coerceAtLeast(0) / 2f
        val halfItemHeight = itemHeight.coerceAtLeast(0) / 2f
        val centerX = anchorX?.coerceIn(
            halfItemWidth + maximumRadius,
            (width - halfItemWidth - maximumRadius).coerceAtLeast(halfItemWidth + maximumRadius),
        ) ?: width / 2f
        val centerY = anchorY?.coerceIn(
            halfItemHeight + maximumRadius,
            (height - halfItemHeight - maximumRadius).coerceAtLeast(halfItemHeight + maximumRadius),
        ) ?: height * .46f
        return (0 until count).map { index ->
            val ring = if (index < firstRingCount) 0 else 1
            val ringIndex = if (ring == 0) index else index - firstRingCount
            val ringCount = if (ring == 0) firstRingCount else count - firstRingCount
            val radius = base * when {
                ring == 1 -> .44f
                count == 9 -> .37f
                else -> .30f
            }
            val angle = -Math.PI / 2 + 2 * Math.PI * ringIndex / ringCount.coerceAtLeast(1)
            OrbitalMenuPoint(
                x = centerX + cos(angle).toFloat() * radius,
                y = centerY + sin(angle).toFloat() * radius,
                ring = ring,
            )
        }
    }
}

object ClockStylePolicy {
    fun isParticle(style: String): Boolean =
        style.lowercase() in setOf("particles", "particle", "arrival", "hourglass", "sand")
}
