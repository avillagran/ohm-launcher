package cl.villagranquiroz.ohm_launcher

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class LauncherVerticalAction {
    NONE,
    OPEN_DRAWER,
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
            deltaY < 0f && startedInLowerHalf -> LauncherVerticalAction.OPEN_DRAWER
            deltaY > 0f && !startedInLowerHalf -> LauncherVerticalAction.OPEN_QUAKE
            else -> LauncherVerticalAction.NONE
        }
    }
}

object DrawerTapPolicy {
    fun mayLaunchApp(verticalDragDistance: Float, touchSlop: Float): Boolean =
        abs(verticalDragDistance) <= touchSlop
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
    fun height(screenHeight: Int, statusBar: Int, imeHeight: Int, desiredFraction: Double = 0.68): Int {
        if (screenHeight <= 0) return 0
        val desired = (screenHeight * desiredFraction).toInt()
        val available = (screenHeight - statusBar.coerceAtLeast(0) - imeHeight.coerceAtLeast(0)).coerceAtLeast(0)
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

data class OrbitalMenuPoint(val x: Float, val y: Float, val ring: Int)

object OrbitalMenuGeometry {
    fun positions(count: Int, width: Int, height: Int): List<OrbitalMenuPoint> {
        if (count <= 0 || width <= 0 || height <= 0) return emptyList()
        val centerX = width / 2f
        val centerY = height * .46f
        val base = min(width, height).toFloat()
        return (0 until count).map { index ->
            val ring = if (index < 8) 0 else 1
            val ringIndex = if (ring == 0) index else index - 8
            val ringCount = if (ring == 0) min(count, 8) else count - 8
            val radius = base * if (ring == 0) .27f else .43f
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
