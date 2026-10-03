package cl.villagranquiroz.ohm_launcher

import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

class ConfigStorage(
    private val publicRoot: File,
    private val legacyRoot: File,
    private val privateRoot: File,
) {
    fun initialize(canUsePublicRoot: Boolean): File {
        val root = if (canUsePublicRoot) publicRoot else privateRoot
        return withRootLock(root) {
            if (canUsePublicRoot) migrateLegacyRoot()
            check(root.mkdirs() || root.isDirectory) { "Could not create ${root.absolutePath}" }
            activeRoot = root
            if (!root.resolve(CONFIG_NAME).exists()) {
                replaceAtomically(root.resolve(CONFIG_NAME), DEFAULT_CONFIG)
                replaceAtomically(root.resolve(FAVORITES_NAME), DEFAULT_FAVORITES)
            }
            root
        }
    }

    fun read(root: File): LauncherConfig = withRootLock(root) {
        LauncherConfig.parse(root.resolve(CONFIG_NAME).readText()).copy(favorites = readFavorites(root))
    }

    fun readFavorites(root: File): List<String> = withRootLock(root) {
        runCatching {
            val file = root.resolve(FAVORITES_NAME)
            if (file.exists()) FavoritesConfigEditor.parse(file.readText()) else emptyList()
        }.getOrDefault(emptyList())
    }

    fun writeFavorites(root: File, favorites: List<String>) = withRootLock(root) {
        replaceAtomically(root.resolve(FAVORITES_NAME), FavoritesConfigEditor.serialize(favorites))
        Unit
    }

    fun write(root: File, source: String) = withRootLock(root) {
        LauncherConfig.parse(source)
        replaceAtomically(root.resolve(CONFIG_NAME), source)
        Unit
    }

    /**
     * Mutates the latest document under the same process lock as every writer.
     * Call off the main thread. Authority checks must not block or perform IO;
     * logical revocation can occur while staging without waiting for this lock.
     * A rename already entered cannot be undone by a later revocation.
     */
    fun update(
        root: File,
        authority: () -> Boolean,
        transform: (JSONObject) -> JSONObject,
    ): LauncherConfig? {
        if (!authority()) return null
        return withRootLock(root) {
            if (!authority()) return@withRootLock null
            val target = root.resolve(CONFIG_NAME)
            val document = transform(JSONObject(target.readText()))
            val source = document.toString(2)
            val parsed = LauncherConfig.parse(source)
            if (!replaceAtomically(target, source, authority)) return@withRootLock null
            parsed.copy(favorites = readFavorites(root))
        }
    }

    private fun replaceAtomically(
        target: File,
        source: String,
        authority: () -> Boolean = { true },
    ): Boolean {
        if (!authority()) return false
        val temporary = File.createTempFile(".${target.name}-", ".tmp", checkNotNull(target.parentFile))
        try {
            FileOutputStream(temporary).use { output ->
                output.write(source.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!authority()) return false
            // Same-directory rename is the sole commit; no partial-copy fallback.
            check(temporary.renameTo(target)) { "Could not atomically replace ${target.absolutePath}" }
            return true
        } finally {
            temporary.delete()
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
        private val rootLocks = mutableMapOf<String, Any>()

        private fun <T> withRootLock(root: File, action: () -> T): T {
            // Controlled application roots; normalization performs no filesystem IO.
            val key = root.absoluteFile.normalize().path
            val lock = synchronized(rootLocks) { rootLocks.getOrPut(key) { Any() } }
            return synchronized(lock, action)
        }

        fun toggleActiveFavorite(key: String): List<String> {
            val root = checkNotNull(activeRoot) { "Config storage has not been initialized" }
            return withRootLock(root) {
                val storage = ConfigStorage(root, root, root)
                val updated = FavoritesConfigEditor.toggle(storage.readFavorites(root), key)
                storage.writeFavorites(root, updated)
                updated
            }
        }

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
