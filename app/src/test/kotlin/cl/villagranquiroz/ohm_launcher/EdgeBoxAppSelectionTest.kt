package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeBoxAppSelectionTest {
    @Test
    fun checksMultipleAppsAndReturnsThemInCatalogOrder() {
        val alpha = InstalledApp("Alpha", "alpha.pkg", "Alpha")
        val beta = InstalledApp("Beta", "beta.pkg", "Beta")
        val selection = EdgeBoxAppSelection(listOf(alpha, beta))

        selection.toggle(beta)
        selection.toggle(alpha)

        assertTrue(selection.isSelected(alpha))
        assertEquals(listOf(alpha, beta), selection.selectedApps())
        selection.toggle(alpha)
        assertFalse(selection.isSelected(alpha))
    }
}