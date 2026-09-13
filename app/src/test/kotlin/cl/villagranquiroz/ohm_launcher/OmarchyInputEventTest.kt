package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyInputEventTest {
    @Test
    fun parsesTapSwipeAndSupportedKeys() {
        assertEquals(
            InputEventParseResult.Success(OmarchyInputEvent.Tap(540.0, 1200.5)),
            OmarchyInputEvent.parse(mapOf("action" to "tap", "x" to 540, "y" to 1200.5)),
        )
        assertEquals(
            InputEventParseResult.Success(OmarchyInputEvent.Swipe(1.0, 2.0, 3.0, 4.0, 300)),
            OmarchyInputEvent.parse(
                mapOf("action" to "swipe", "x1" to 1, "y1" to 2, "x2" to 3, "y2" to 4),
            ),
        )
        assertEquals(
            InputEventParseResult.Success(OmarchyInputEvent.Key(OmarchyRemoteKey.RECENTS)),
            OmarchyInputEvent.parse(mapOf("action" to "key", "key" to "recents")),
        )
    }

    @Test
    fun rejectsUnknownIncompleteAndUnsafeInputEvents() {
        val invalid = listOf(
            mapOf<String, Any?>("action" to "teleport"),
            mapOf<String, Any?>("action" to "tap", "x" to 1),
            mapOf<String, Any?>("action" to "tap", "x" to -1, "y" to 2),
            mapOf<String, Any?>("action" to "tap", "x" to Double.NaN, "y" to 2),
            mapOf<String, Any?>("action" to "swipe", "x1" to 1, "y1" to 2, "x2" to 3, "y2" to 4, "durationMs" to 0),
            mapOf<String, Any?>("action" to "swipe", "x1" to 1, "y1" to 2, "x2" to 3, "y2" to 4, "durationMs" to 10_001),
            mapOf<String, Any?>("action" to "key", "key" to "power"),
        )

        invalid.forEach { assertTrue(it.toString(), OmarchyInputEvent.parse(it) is InputEventParseResult.Error) }
    }

    @Test
    fun serializesInputEventsUsingWireContractNames() {
        assertEquals(
            mapOf("action" to "tap", "x" to 3.0, "y" to 4.0),
            OmarchyInputEvent.Tap(3.0, 4.0).toPayload(),
        )
        assertEquals(
            mapOf("action" to "key", "key" to "home"),
            OmarchyInputEvent.Key(OmarchyRemoteKey.HOME).toPayload(),
        )
    }
}
