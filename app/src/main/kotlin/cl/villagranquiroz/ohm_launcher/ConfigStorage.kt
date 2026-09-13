package cl.villagranquiroz.ohm_launcher

import java.io.File

class ConfigStorage(
    private val publicRoot: File,
    private val legacyRoot: File,
    private val privateRoot: File,
) {
    fun initialize(canUsePublicRoot: Boolean): File {
        val root = if (canUsePublicRoot) {
            migrateLegacyRoot()
            publicRoot
        } else {
            privateRoot
        }
        root.mkdirs()
        activeRoot = root
        val config = root.resolve(CONFIG_NAME)
        if (!config.exists()) config.writeText(DEFAULT_CONFIG)
        return root
    }

    fun read(root: File): LauncherConfig = LauncherConfig.parse(root.resolve(CONFIG_NAME).readText()).copy(
        favorites = readFavorites(root),
    )

    fun readFavorites(root: File): List<String> = runCatching {
        val file = root.resolve(FAVORITES_NAME)
        if (file.exists()) FavoritesConfigEditor.parse(file.readText()) else emptyList()
    }.getOrDefault(emptyList())

    fun writeFavorites(root: File, favorites: List<String>) {
        replaceAtomically(root.resolve(FAVORITES_NAME), FavoritesConfigEditor.serialize(favorites))
    }

    fun write(root: File, source: String) {
        LauncherConfig.parse(source)
        replaceAtomically(root.resolve(CONFIG_NAME), source)
    }

    private fun replaceAtomically(target: File, source: String) {
        val temporary = checkNotNull(target.parentFile).resolve("${target.name}.tmp")
        temporary.writeText(source)
        check(temporary.renameTo(target) || temporary.copyTo(target, overwrite = true).let { temporary.delete() }) {
            "Could not replace ${target.absolutePath}"
        }
    }

    private fun migrateLegacyRoot() {
        if (publicRoot.exists() || !legacyRoot.exists()) return
        publicRoot.parentFile?.mkdirs()
        if (!legacyRoot.renameTo(publicRoot)) {
            legacyRoot.copyRecursively(publicRoot, overwrite = false)
            legacyRoot.deleteRecursively()
        }
    }

    companion object {
        const val CONFIG_NAME = "widgets_config.json"
        const val FAVORITES_NAME = "favorites.json"
        @Volatile private var activeRoot: File? = null

        @Synchronized
        fun toggleActiveFavorite(key: String): List<String> {
            val root = checkNotNull(activeRoot) { "Config storage has not been initialized" }
            val storage = ConfigStorage(root, root, root)
            val updated = FavoritesConfigEditor.toggle(storage.readFavorites(root), key)
            storage.writeFavorites(root, updated)
            return updated
        }

        @Synchronized
        fun writeActiveFavorites(favorites: List<String>) {
            val root = checkNotNull(activeRoot) { "Config storage has not been initialized" }
            ConfigStorage(root, root, root).writeFavorites(root, favorites)
        }

        val DEFAULT_CONFIG = """
            {
              "wallpaper":"#0B0F14",
              "desktops":[{
                "name":"Inicio",
                "background":"#0B0F14",
                "ttfxBackground":true,
                "ttfxEffect":"matrix",
                "ttfxText":"OHM",
                "ttfxTextSize":3,
                "ttfxTextX":0.5,
                "ttfxTextY":0.5,
                "ttfxAudio":true,
                "ttfxIntensity":5,
                "ttfxSpeed":1.0,
                "ttfxResolution":2,
                "ttfxReactivity":2,
                "widgets":[]
              }]
            }
        """.trimIndent()
    }
}
