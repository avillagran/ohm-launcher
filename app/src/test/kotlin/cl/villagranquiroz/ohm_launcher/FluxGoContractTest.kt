package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class FluxGoContractTest {
    @Test fun nativeAndLegacyGoIdentityCapabilitiesAndVerificationKeysMatch() {
        for ((name, dialect) in listOf("native" to FluxDialect.FLUX, "legacy" to FluxDialect.LEGACY_KDE)) {
            val fixture = javaClass.getResourceAsStream("/flux/identity-$name-go.json")!!.use {
                JSONObject(it.readBytes().toString(Charsets.UTF_8))
            }
            val frame = JSONObject().put("type", fixture.getString("identityType"))
                .put("id", 1).put("body", fixture.getJSONObject("identity")).toString()
            assertEquals("Flux interoperability test", FluxWire.parseIdentity(frame, fixture.getString("goId"), dialect))
            assertTrue(FluxWire.supportsMedia(frame))
            assertTrue(FluxWire.supportsRemoteInput(frame))
            assertTrue(FluxWire.supportsInputApproval(frame))
            assertTrue(FluxWire.supportsThemeSelection(frame))
            assertTrue(FluxWire.supportsWallpaper(frame))
            val go = Base64.getDecoder().decode(fixture.getString("goSpki"))
            val kotlin = Base64.getDecoder().decode(fixture.getString("kotlinSpki"))
            assertEquals(fixture.getString("verificationKey"), FluxWire.verificationKey(go, kotlin, fixture.getLong("timestamp"), dialect))
            assertEquals(fixture.getString("verificationKey"), FluxWire.verificationKey(kotlin, go, fixture.getLong("timestamp"), dialect))
            assertEquals(FluxWire.type(FluxWire.PAIR, dialect), fixture.getString("pairType"))
        }
    }
}
