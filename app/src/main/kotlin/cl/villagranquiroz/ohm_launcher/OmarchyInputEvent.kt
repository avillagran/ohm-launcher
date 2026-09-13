package cl.villagranquiroz.ohm_launcher

/** Validated remote-control event from REST or WebSocket payloads. */
sealed class OmarchyInputEvent {
    data class Tap(val x: Double, val y: Double) : OmarchyInputEvent()

    data class Swipe(
        val x1: Double,
        val y1: Double,
        val x2: Double,
        val y2: Double,
        val durationMs: Int = DEFAULT_SWIPE_DURATION_MS,
    ) : OmarchyInputEvent()

    data class Key(val key: OmarchyRemoteKey) : OmarchyInputEvent()

    fun toPayload(): Map<String, Any> = when (this) {
        is Tap -> mapOf("action" to "tap", "x" to x, "y" to y)
        is Swipe -> mapOf(
            "action" to "swipe",
            "x1" to x1,
            "y1" to y1,
            "x2" to x2,
            "y2" to y2,
            "durationMs" to durationMs,
        )
        is Key -> mapOf("action" to "key", "key" to key.wireName)
    }

    companion object {
        const val DEFAULT_SWIPE_DURATION_MS = 300
        private const val MAX_SWIPE_DURATION_MS = 10_000

        fun parse(payload: Map<String, *>): InputEventParseResult {
            return when (payload["action"] as? String) {
                "tap" -> {
                    val x = payload.coordinate("x")
                    val y = payload.coordinate("y")
                    if (x == null || y == null) invalid("invalid_coordinates")
                    else InputEventParseResult.Success(Tap(x, y))
                }
                "swipe" -> {
                    val x1 = payload.coordinate("x1")
                    val y1 = payload.coordinate("y1")
                    val x2 = payload.coordinate("x2")
                    val y2 = payload.coordinate("y2")
                    val duration = when (val raw = payload["durationMs"]) {
                        null -> DEFAULT_SWIPE_DURATION_MS
                        is Number -> raw.toLong().takeIf {
                            raw.toDouble().isFinite() && raw.toDouble() == it.toDouble() && it in 1..MAX_SWIPE_DURATION_MS
                        }?.toInt()
                        else -> null
                    }
                    if (x1 == null || y1 == null || x2 == null || y2 == null) invalid("invalid_coordinates")
                    else if (duration == null) invalid("invalid_duration")
                    else InputEventParseResult.Success(Swipe(x1, y1, x2, y2, duration))
                }
                "key" -> {
                    val key = OmarchyRemoteKey.fromWireName(payload["key"] as? String)
                    if (key == null) invalid("invalid_key") else InputEventParseResult.Success(Key(key))
                }
                else -> invalid("unknown_action")
            }
        }

        private fun invalid(code: String) = InputEventParseResult.Error(code)
    }
}

sealed class InputEventParseResult {
    data class Success(val event: OmarchyInputEvent) : InputEventParseResult()
    data class Error(val code: String) : InputEventParseResult()
}

enum class OmarchyRemoteKey(val wireName: String) {
    BACK("back"),
    HOME("home"),
    RECENTS("recents");

    companion object {
        fun fromWireName(value: String?): OmarchyRemoteKey? = entries.firstOrNull { it.wireName == value }
    }
}

private fun Map<String, *>.coordinate(name: String): Double? {
    val value = (this[name] as? Number)?.toDouble() ?: return null
    return value.takeIf { it.isFinite() && it >= 0.0 }
}
