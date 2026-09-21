package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the notify/expose decision behind the TTFX live
 * wallpaper color reporting. The engine delegates every gate to
 * [TtfxWallpaperColorPolicy] so the dedup behavior is testable without a
 * framework: API level, synchronization toggle, fingerprint comparison
 * against the last REPORTED fingerprint, and palette validity.
 */
class TtfxWallpaperColorPolicyTest {

    private fun colors(primary: Int, darkTheme: Boolean = true) = OmarchySystemThemeColors(
        primary = primary,
        secondary = 0xFF222222.toInt(),
        tertiary = 0xFF333333.toInt(),
        background = 0xFF111111.toInt(),
        darkTheme = darkTheme,
    )

    @Test
    fun firstLoadNotifiesOnApi27AndUpWhenSyncEnabled() {
        val next = colors(0xFF66E0FF.toInt())

        val report = TtfxWallpaperColorPolicy.evaluate(
            api = 27,
            syncEnabled = true,
            previousFingerprint = null,
            next = next,
        )

        assertTrue(report.notify)
        assertSame(next, report.colors)
    }

    @Test
    fun notifiesOnNewerApisWhenSyncEnabled() {
        val next = colors(0xFF66E0FF.toInt())

        val report = TtfxWallpaperColorPolicy.evaluate(
            api = 34,
            syncEnabled = true,
            previousFingerprint = null,
            next = next,
        )

        assertTrue(report.notify)
        assertSame(next, report.colors)
    }

    @Test
    fun identicalReloadDoesNotNotify() {
        val next = colors(0xFF66E0FF.toInt())

        val report = TtfxWallpaperColorPolicy.evaluate(
            api = 27,
            syncEnabled = true,
            previousFingerprint = next.fingerprint,
            next = next,
        )

        assertFalse(report.notify)
        assertSame(next, report.colors)
    }

    @Test
    fun changedPaletteNotifies() {
        val previous = colors(0xFF66E0FF.toInt())
        val next = colors(0xFFFF0000.toInt())

        val report = TtfxWallpaperColorPolicy.evaluate(
            api = 27,
            syncEnabled = true,
            previousFingerprint = previous.fingerprint,
            next = next,
        )

        assertTrue(report.notify)
        assertSame(next, report.colors)
        assertTrue(next.fingerprint != previous.fingerprint)
    }

    @Test
    fun disabledSyncExposesNullColorsAndDoesNotNotify() {
        val next = colors(0xFF66E0FF.toInt())

        val report = TtfxWallpaperColorPolicy.evaluate(
            api = 27,
            syncEnabled = false,
            previousFingerprint = null,
            next = next,
        )

        assertFalse(report.notify)
        assertNull(report.colors)
    }

    @Test
    fun reEnabledSyncAfterDisabledNotifiesAgainstLastReportedFingerprint() {
        val first = colors(0xFF66E0FF.toInt())
        val changedWhileDisabled = colors(0xFF00FF00.toInt())

        // Disabled period: palette changed but no fingerprint was recorded.
        val whileDisabled = TtfxWallpaperColorPolicy.evaluate(
            api = 27,
            syncEnabled = false,
            previousFingerprint = first.fingerprint,
            next = changedWhileDisabled,
        )
        assertFalse(whileDisabled.notify)
        assertNull(whileDisabled.colors)

        // Re-enable: comparison still runs against the last REPORTED
        // fingerprint (first), so the change is picked up and notified.
        val reEnabled = TtfxWallpaperColorPolicy.evaluate(
            api = 27,
            syncEnabled = true,
            previousFingerprint = first.fingerprint,
            next = changedWhileDisabled,
        )
        assertTrue(reEnabled.notify)
        assertSame(changedWhileDisabled, reEnabled.colors)
    }

    @Test
    fun reEnabledSyncWithUnchangedPaletteDoesNotNotify() {
        val first = colors(0xFF66E0FF.toInt())

        val report = TtfxWallpaperColorPolicy.evaluate(
            api = 27,
            syncEnabled = true,
            previousFingerprint = first.fingerprint,
            next = first,
        )

        assertFalse(report.notify)
    }

    @Test
    fun api26NeverNotifiesAndExposesNullColors() {
        val next = colors(0xFF66E0FF.toInt())

        val report = TtfxWallpaperColorPolicy.evaluate(
            api = 26,
            syncEnabled = true,
            previousFingerprint = null,
            next = next,
        )

        assertFalse(report.notify)
        assertNull(report.colors)
    }

    @Test
    fun missingPaletteNeverNotifies() {
        val report = TtfxWallpaperColorPolicy.evaluate(
            api = 27,
            syncEnabled = true,
            previousFingerprint = null,
            next = null,
        )

        assertFalse(report.notify)
        assertNull(report.colors)
    }

    @Test
    fun fingerprintCoversAllColorRolesAndThemeFlag() {
        val base = colors(0xFF66E0FF.toInt())
        val variants = listOf(
            base.copy(secondary = 0xFFAAAAAA.toInt()),
            base.copy(tertiary = 0xFFBBBBBB.toInt()),
            base.copy(background = 0xFFCCCCCC.toInt()),
            base.copy(darkTheme = false),
        )

        for (variant in variants) {
            assertTrue(
                "expected distinct fingerprint for $variant",
                variant.fingerprint != base.fingerprint,
            )
        }
        assertEquals(base.fingerprint, colors(0xFF66E0FF.toInt()).fingerprint)
    }
}
