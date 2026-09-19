package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OmarchyMenuEnterPolicyTest {
    @Test
    fun enterWithEmptyVisibleListDoesNothing() {
        assertNull(OmarchyMenuEnterPolicy.firstVisible(emptyList()))
    }

    @Test
    fun enterTargetsTheFirstVisibleEntry() {
        val first = OmarchyMenuEntry(icon = "a", label = "first", action = {})
        val second = OmarchyMenuEntry(icon = "b", label = "second", action = {})
        assertEquals(first, OmarchyMenuEnterPolicy.firstVisible(listOf(first, second)))
    }

    @Test
    fun enterOpensSubmenuWhenFirstEntryHasChildren() {
        val entry = OmarchyMenuEntry(
            icon = "a",
            label = "submenu",
            children = listOf(OmarchyMenuEntry(icon = "b", label = "child", action = {})),
        )
        assertTrue(OmarchyMenuEnterPolicy.shouldOpen(entry))
        assertFalse(OmarchyMenuEnterPolicy.shouldRun(entry))
    }

    @Test
    fun enterRunsActionWhenFirstEntryIsExecutable() {
        val entry = OmarchyMenuEntry(icon = "a", label = "app", action = {})
        assertTrue(OmarchyMenuEnterPolicy.shouldRun(entry))
        assertFalse(OmarchyMenuEnterPolicy.shouldOpen(entry))
    }
}
