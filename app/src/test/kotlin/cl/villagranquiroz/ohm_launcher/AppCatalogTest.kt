package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCatalogTest {
    @Test
    fun sortsAppsCaseInsensitivelyAndDeduplicatesActivities() {
        val apps = listOf(
            InstalledApp("Zeta", "z.pkg", "z.Activity"),
            InstalledApp("alpha", "a.pkg", "a.Activity"),
            InstalledApp("Alpha duplicate", "a.pkg", "a.Activity"),
        )

        assertEquals(
            listOf("alpha", "Zeta"),
            AppCatalog.normalize(apps).map { it.label },
        )
    }
}
