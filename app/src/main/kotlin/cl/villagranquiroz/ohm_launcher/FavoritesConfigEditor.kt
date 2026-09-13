package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray

object FavoritesConfigEditor {
    fun parse(source: String): List<String> {
        val values = JSONArray(source)
        return buildList {
            for (index in 0 until values.length()) {
                if (values.opt(index) is String) add(values.getString(index))
            }
        }
    }

    fun serialize(favorites: List<String>): String = JSONArray(favorites).toString(2)

    fun toggle(favorites: List<String>, key: String): List<String> = favorites.toMutableList().apply {
        if (contains(key)) remove(key) else add(key)
    }

    fun resolve(favorites: List<String>, apps: List<InstalledApp>): List<InstalledApp> {
        val appsByKey = apps.associateBy { "${it.packageName}/${it.activityName}" }
        return favorites.mapNotNull(appsByKey::get)
    }
}
