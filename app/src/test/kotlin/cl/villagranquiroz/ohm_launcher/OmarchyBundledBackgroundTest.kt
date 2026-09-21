package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyBundledBackgroundTest {
    @Test
    fun reusesMaterializedFileWhenContentIsIdentical() {
        val asset = byteArrayOf(1, 2, 3, 4)
        assertTrue(OmarchyBundledBackground.shouldReuse(existing = byteArrayOf(1, 2, 3, 4), asset = asset))
    }

    @Test
    fun rewritesWhenNoMaterializedFileExists() {
        assertFalse(OmarchyBundledBackground.shouldReuse(existing = null, asset = byteArrayOf(1, 2)))
    }

    @Test
    fun rewritesWhenContentDiffersEvenAtEqualSize() {
        val asset = byteArrayOf(1, 2, 3, 4)
        assertFalse(OmarchyBundledBackground.shouldReuse(existing = byteArrayOf(4, 3, 2, 1), asset = asset))
    }

    @Test
    fun rewritesWhenSizeDiffers() {
        assertFalse(OmarchyBundledBackground.shouldReuse(existing = byteArrayOf(1, 2), asset = byteArrayOf(1, 2, 3)))
    }
}
