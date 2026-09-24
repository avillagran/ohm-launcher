package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppNerdIconPolicyTest {
    @Test
    fun mapsKnownApplicationsByPackageRatherThanLocalizedLabel() {
        assertEquals("\uF095", AppNerdIconPolicy.glyphFor("com.google.android.dialer"))
        assertEquals("\uF095", AppNerdIconPolicy.glyphFor("com.truecaller"))
        assertEquals("\uF030", AppNerdIconPolicy.glyphFor("com.android.camera"))
        assertEquals("\uF268", AppNerdIconPolicy.glyphFor("com.android.chrome"))
        assertEquals("\uF269", AppNerdIconPolicy.glyphFor("org.mozilla.firefox"))
        assertEquals("\uF0AC", AppNerdIconPolicy.glyphFor("com.brave.browser"))
        assertEquals("\uF232", AppNerdIconPolicy.glyphFor("com.whatsapp"))
        assertEquals("\uF232", AppNerdIconPolicy.glyphFor("com.whatsapp.w4b"))
    }

    @Test
    fun leavesUnmappedAndLookalikePackagesWithTheirOriginalIcon() {
        assertNull(AppNerdIconPolicy.glyphFor("org.example.camera"))
        assertNull(AppNerdIconPolicy.glyphFor("com.whatsapp.fake"))
        assertNull(AppNerdIconPolicy.glyphFor("com.example.app"))
    }

    @Test
    fun coversCommunicationMediaProductivityAndUtilities() {
        val expected = mapOf(
            "org.telegram.messenger" to "\uF2C6",
            "org.thoughtcrime.securesms" to "\uF0E0",
            "com.discord" to "\uF1FF",
            "com.instagram.android" to "\uF16D",
            "com.facebook.orca" to "\uF25F",
            "com.twitter.android" to "\uDB82\uDF05",
            "com.spotify.music" to "\uF1BC",
            "com.google.android.youtube" to "\uF16A",
            "com.netflix.mediaclient" to "\uF008",
            "com.google.android.apps.maps" to "\uF041",
            "com.waze" to "\uEF9E",
            "com.google.android.gm" to "\uF0E0",
            "com.google.android.calendar" to "\uF073",
            "com.google.android.apps.nbu.files" to "\uF07B",
            "com.google.android.apps.docs" to "\uF2DF",
            "com.termux" to "\uF120",
            "com.google.android.keep" to "\uF249",
            "com.google.android.calculator" to "\uF1EC",
            "com.github.android" to "\uF09B",
        )
        expected.forEach { (packageName, glyph) ->
            assertEquals(packageName, glyph, AppNerdIconPolicy.glyphFor(packageName))
        }
        assertNull(AppNerdIconPolicy.glyphFor("com.example.spotify.music"))
        assertNull(AppNerdIconPolicy.glyphFor("com.instagram.barcelona"))
    }

    @Test
    fun glyphsAreDistinctPrivateUseCharacters() {
        val glyphs = listOf("com.truecaller", "com.android.camera", "com.android.chrome", "com.whatsapp")
            .map { checkNotNull(AppNerdIconPolicy.glyphFor(it)) }
        assertEquals(glyphs.size, glyphs.toSet().size)
        assertTrue(glyphs.all { it.single().code in 0xE000..0xF8FF })
    }
}
