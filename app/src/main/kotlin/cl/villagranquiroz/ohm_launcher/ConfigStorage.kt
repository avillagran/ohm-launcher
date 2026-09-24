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
        val freshInstall = !config.exists()
        if (freshInstall) {
            config.writeText(DEFAULT_CONFIG)
            root.resolve(FAVORITES_NAME).writeText(DEFAULT_FAVORITES)
        }
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
              "wallpaper":"#0A1A12",
              "desktops":[{
                "name":"Inicio",
                "background":"#0A1A12",
                "fontFamily":"Raleway",
                "titleFont":"Oswald",
                "gridColumns":10,
                "gridRows":17,
                "ttfxBackground":true,
                "ttfxEffect":"decrypt",
                "ttfxText":"Omarchy",
                "ttfxTextSize":7,
                "ttfxTextX":0.5,
                "ttfxTextY":0.4,
                "ttfxAudio":false,
                "ttfxIntensity":2,
                "ttfxSpeed":4.7,
                "ttfxResolution":3,
                "ttfxReactivity":2,
                "widgets":[
                  {
                    "type":"clock",
                    "style":"particles",
                    "format":"HH:mm:ss",
                    "fontSize":64,
                    "color":"#66E0FF",
                    "fontWeight":"w300",
                    "density":3,
                    "particleSize":1.6,
                    "x":0,
                    "y":2,
                    "w":9,
                    "h":4
                  },
                  {
                    "type":"plugin_widget",
                    "pluginId":"io.github.ohm.demo.weather",
                    "kind":"bar-widget",
                    "x":0,
                    "y":14,
                    "w":5,
                    "h":3
                  }
                ]
              }],
              "edgeBoxes":[{
                "id":"default-right",
                "name":"Basecamp + X",
                "edge":"right",
                "direction":"vertical",
                "visible":true,
                "showTitle":false,
                "compact":false,
                "showExpandButton":true,
                "color":"#7EE787",
                "items":[
                  {
                    "type":"app",
                    "package":"com.basecamp.bc3",
                    "activity":"com.basecamp.bc4.app.main.MainActivity",
                    "label":"Basecamp"
                  },
                  {
                    "type":"app",
                    "package":"com.twitter.android",
                    "activity":"com.x.android.main.MainActivity",
                    "label":"X"
                  }
                ]
              }]
            }
        """.trimIndent()

        val DEFAULT_FAVORITES = """
            [
              "com.truecaller/com.truecaller.ui.TruecallerInit",
              "com.android.chrome/com.google.android.apps.chrome.Main",
              "com.termux/com.termux.app.TermuxActivity",
              "com.waze/com.waze.FreeMapAppActivity",
              "com.android.camera/com.android.camera.Camera",
              "com.whatsapp/com.whatsapp.Main"
            ]
        """.trimIndent()
    }
}
