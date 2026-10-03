package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FluxTouchpadViewTest {
    private class Fixture {
        var authorized = true
        val actions = mutableListOf<JSONObject>()
        val policy = FluxTouchpadGesturePolicy({ authorized }) { action ->
            actions += JSONObject(FluxRemoteInputProtocol.packet(action, 1).trim()).getJSONObject("body")
        }
        fun fields() = actions.map { it.keys().asSequence().toSet() }
    }

    @Test fun motionUsesRelativeBoundedDeltasAndTapClicksOnlyWithoutTravel() {
        val f = Fixture()
        f.policy.down(10f, 10f)
        f.policy.move(23f, 12f)
        f.policy.up(23f, 12f)
        assertEquals(listOf(setOf("dx", "dy")), f.fields())
        assertEquals(13.0, f.actions[0].getDouble("dx"), 0.0)
        f.policy.down(0f, 0f)
        f.policy.up(0f, 0f)
        assertEquals(setOf("singleclick"), f.fields().last())
    }

    @Test fun dragHoldsExactlyOnceAndAlwaysReleasesOnUpOrCancel() {
        val f = Fixture()
        f.policy.down(0f, 0f)
        f.policy.hold()
        f.policy.hold()
        f.policy.move(20f, 0f)
        f.policy.up(20f, 0f)
        assertEquals(listOf(setOf("singlehold"), setOf("dx", "dy"), setOf("singlerelease")), f.fields())
        f.policy.down(0f, 0f)
        f.policy.hold()
        f.policy.cancel()
        assertEquals(setOf("singlerelease"), f.fields().last())
    }

    @Test fun explicitCloseReleasesBeforeClosureAndPreventsFurtherCommands() {
        val f = Fixture()
        f.policy.down(0f, 0f)
        f.policy.hold()
        f.policy.close()
        f.policy.close()
        f.policy.up(0f, 0f)
        f.policy.text("a")
        assertEquals(listOf(setOf("singlehold"), setOf("singlerelease")), f.fields())
    }

    @Test fun revokedAuthorityAndInvalidMotionNeverEmit() {
        val f = Fixture()
        f.policy.down(0f, 0f)
        f.authorized = false
        f.policy.hold()
        f.policy.move(100f, 0f)
        f.policy.up(100f, 0f)
        f.policy.click(FluxRemoteInputProtocol.Click.RIGHT)
        f.policy.text("private")
        f.policy.close()
        assertTrue(f.actions.isEmpty())
        val g = Fixture()
        g.policy.down(Float.NaN, 0f)
        g.policy.move(Float.POSITIVE_INFINITY, 10f)
        g.policy.up(0f, 0f)
        assertTrue(g.actions.isEmpty())
    }

    @Test fun scrollClicksAndKeyboardUseOnlyValidatedProtocolActions() {
        val f = Fixture()
        f.policy.scroll(0f, -4f)
        f.policy.click(FluxRemoteInputProtocol.Click.DOUBLE)
        f.policy.special(FluxRemoteInputProtocol.SpecialKey.ENTER)
        f.policy.text("🙂")
        f.policy.text("bad\n")
        assertEquals(listOf(setOf("dx", "dy", "scroll"), setOf("doubleclick"),
            setOf("specialKey"), setOf("key")), f.fields())
        assertEquals("🙂", f.actions.last().getString("key"))
    }
}
