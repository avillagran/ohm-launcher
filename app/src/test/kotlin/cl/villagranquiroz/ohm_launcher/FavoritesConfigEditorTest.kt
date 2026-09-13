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
