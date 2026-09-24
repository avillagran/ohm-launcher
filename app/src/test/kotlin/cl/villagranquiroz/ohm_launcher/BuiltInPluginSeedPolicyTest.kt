package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInPluginSeedPolicyTest {
    @Test
    fun hashesAssetBytesAsSha256Hex() {
        assertEquals(
            "c49fea7425fa7f8699897a97c159c6690267d9003bb78c53fafa8fc15c325d84",
            BuiltInPluginSeedPolicy.sha256("legacy".toByteArray()),
        )
    }

    @Test
    fun seedsMissingPluginFiles() {
        assertTrue(BuiltInPluginSeedPolicy.shouldReplace(null, null, "new", emptySet()))
    }

    @Test
    fun updatesFilesThatStillMatchThePreviousBundle() {
        assertTrue(BuiltInPluginSeedPolicy.shouldReplace("old", "old", "new", emptySet()))
    }

    @Test
    fun updatesKnownLegacyFilesWithoutASeedMarker() {
        assertTrue(BuiltInPluginSeedPolicy.shouldReplace("legacy", null, "new", setOf("legacy")))
    }

    @Test
    fun preservesFilesCustomizedByTheUser() {
        assertFalse(BuiltInPluginSeedPolicy.shouldReplace("custom", "old", "new", setOf("legacy")))
    }

    @Test
    fun leavesFilesAlreadyAtTheCurrentBundleUnchanged() {
        assertFalse(BuiltInPluginSeedPolicy.shouldReplace("new", "new", "new", emptySet()))
    }
}
