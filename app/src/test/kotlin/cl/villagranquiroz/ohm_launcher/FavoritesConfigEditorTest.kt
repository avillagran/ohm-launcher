package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesConfigEditorTest {
    @Test
    fun parsesFlutterFavoritesArrayInUserDefinedOrder() {
        val source = """["one.pkg/.Main","two.pkg/two.pkg.Home"]"""

        assertEquals(
            listOf("one.pkg/.Main", "two.pkg/two.pkg.Home"),
            FavoritesConfigEditor.parse(source),
        )
    }

    @Test
    fun serializesFavoritesAsFlutterCompatibleJsonArray() {
        val source = FavoritesConfigEditor.serialize(listOf("one.pkg/.Main", "two.pkg/.Home"))

        assertEquals(listOf("one.pkg/.Main", "two.pkg/.Home"), FavoritesConfigEditor.parse(source))
    }

    @Test
    fun togglesFavoriteUsingFlutterPackageActivityKey() {
        val existing = listOf("one.pkg/.Main")

        assertEquals(emptyList<String>(), FavoritesConfigEditor.toggle(existing, "one.pkg/.Main"))
        assertEquals(
            listOf("one.pkg/.Main", "two.pkg/.Home"),
            FavoritesConfigEditor.toggle(existing, "two.pkg/.Home"),
        )
    }

    @Test
    fun reordersVisibleFavoritesWhileKeepingMissingAppsInPlace() {
        val favorites = listOf("one/.Main", "missing/.Main", "two/.Main", "three/.Main")

        assertEquals(
            listOf("three/.Main", "missing/.Main", "one/.Main", "two/.Main"),
            FavoritesConfigEditor.reorderVisible(
                favorites,
                listOf("three/.Main", "one/.Main", "two/.Main"),
            ),
        )
    }

    @Test
    fun rejectsIncompleteOrDuplicateReorderPayloads() {
        val favorites = listOf("one/.Main", "two/.Main")

        assertEquals(favorites, FavoritesConfigEditor.reorderVisible(favorites, listOf("unknown/.Main")))
        assertEquals(favorites, FavoritesConfigEditor.reorderVisible(favorites, listOf("one/.Main", "one/.Main")))
    }

    @Test
    fun resolvesInstalledFavoritesInPersistedOrderAndSkipsMissingApps() {
        val one = InstalledApp("One", "one.pkg", ".Main")
        val two = InstalledApp("Two", "two.pkg", "two.pkg.Home")

        assertEquals(
            listOf(two, one),
            FavoritesConfigEditor.resolve(
                listOf("two.pkg/two.pkg.Home", "missing/.Main", "one.pkg/.Main"),
                listOf(one, two),
            ),
        )
    }
}
