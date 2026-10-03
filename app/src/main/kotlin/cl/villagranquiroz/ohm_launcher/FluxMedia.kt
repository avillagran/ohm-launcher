package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** All media state is ephemeral, owned by one authenticated control session. */
internal data class FluxPlayer(
    val name: String, val title: String = "", val artist: String = "", val album: String = "",
    val nowPlaying: String = "", val playing: Boolean = false,
    val position: Long = 0, val length: Long = 0,
    val canPlay: Boolean = false, val canPause: Boolean = false,
    val canGoNext: Boolean = false, val canGoPrevious: Boolean = false,
    val canSeek: Boolean = false, val volume: Int? = null,
)

internal class FluxMediaState {
    private val items = linkedMapOf<String, FluxPlayer>()
    @Synchronized fun players(): List<FluxPlayer> = items.values.toList()
    @Synchronized fun clear() = items.clear()

    /** Reject an entire malformed frame, rather than partially changing the displayed state. */
    @Synchronized fun receive(body: JSONObject): Boolean {
        if (body.toString().toByteArray(Charsets.UTF_8).size > 16 * 1024) return false
        return runCatching {
            val updated = LinkedHashMap(items)
            if (body.has("playerList")) {
                val names = body.getJSONArray("playerList")
                require(names.length() <= 32)
                val selected = (0 until names.length()).map {
                    require(names.get(it) is String)
                    names.getString(it).also(::validName)
                }
                require(selected.distinct().size == selected.size)
                updated.keys.retainAll(selected.toSet())
                val ordered = linkedMapOf<String, FluxPlayer>()
                selected.forEach { ordered[it] = updated[it] ?: FluxPlayer(it) }
                updated.clear(); updated.putAll(ordered)
            }
            if (body.has("player")) {
                require(body.get("player") is String)
                val name = body.getString("player").also(::validName)
                val prior = updated[name] ?: FluxPlayer(name)
                fun text(key: String, old: String): String = if (body.has(key))
                    body.getString(key).also { require(body.get(key) is String && it.length <= 512) } else old
                fun flag(key: String, old: Boolean): Boolean = if (body.has(key))
                    body.getBoolean(key).also { require(body.get(key) is Boolean) } else old
                fun millis(key: String, old: Long): Long = if (body.has(key)) {
                    val raw = body.get(key)
                    require(raw is Number)
                    raw.toString().toLongOrNull()?.also { require(it >= 0) }
                        ?: throw IllegalArgumentException("Invalid media timestamp")
                } else old
                val volume = if (body.has("volume")) body.getInt("volume").also {
                    require(body.get("volume") is Number && it in 0..100)
                } else if (body.has("title") && body.has("canSeek")) null else prior.volume
                updated[name] = prior.copy(title = text("title", prior.title), artist = text("artist", prior.artist),
                    album = text("album", prior.album), nowPlaying = text("nowPlaying", prior.nowPlaying),
                    playing = flag("isPlaying", prior.playing), position = millis("pos", prior.position),
                    length = millis("length", prior.length), canPlay = flag("canPlay", prior.canPlay),
                    canPause = flag("canPause", prior.canPause), canGoNext = flag("canGoNext", prior.canGoNext),
                    canGoPrevious = flag("canGoPrevious", prior.canGoPrevious), canSeek = flag("canSeek", prior.canSeek),
                    volume = volume)
            }
            require(updated.size <= 32 && (body.has("player") || body.has("playerList")))
            items.clear(); items.putAll(updated)
            true
        }.getOrDefault(false)
    }

    private fun validName(name: String) { require(name.isNotBlank() && name.length <= 128 && '\n' !in name) }
}

internal object FluxMediaProtocol {
    const val STATE = "kdeconnect.mpris"
    const val REQUEST = "kdeconnect.mpris.request"
    private fun player(name: String): JSONObject {
        require(name.isNotBlank() && name.length <= 128 && '\n' !in name)
        return JSONObject().put("player", name)
    }
    fun playerList(): JSONObject = JSONObject().put("requestPlayerList", true)
    fun refresh(name: String): JSONObject = player(name).put("requestNowPlaying", true).put("requestVolume", true)
    fun action(name: String, command: String): JSONObject {
        require(command in setOf("PlayPause", "Play", "Pause", "Next", "Previous", "Stop"))
        return player(name).put("action", command)
    }
    fun position(name: String, millis: Long): JSONObject {
        require(millis >= 0)
        return player(name).put("SetPosition", millis)
    }
    fun volume(name: String, percent: Int): JSONObject {
        require(percent in 0..100)
        return player(name).put("setVolume", percent)
    }
}

internal object FluxMediaControls {
    fun needsRefresh(body: JSONObject): Boolean =
        body.has("action") || body.has("SetPosition") || body.has("setVolume")

    /** Keep milliseconds through slider interpolation, without multiplying the full duration. */
    fun positionAtProgress(lengthMillis: Long, progress: Int, maximum: Int): Long {
        require(lengthMillis >= 0 && maximum > 0)
        val bounded = progress.coerceIn(0, maximum).toLong()
        return (lengthMillis / maximum) * bounded + (lengthMillis % maximum) * bounded / maximum
    }

    fun seek(state: FluxMediaState, name: String, millis: Long): JSONObject {
        val player = state.players().firstOrNull { it.name == name }
        require(player != null && player.canSeek && player.length > 0)
        return FluxMediaProtocol.position(name, millis.coerceIn(0, minOf(player.length, Long.MAX_VALUE / 1000)))
    }

    fun volume(state: FluxMediaState, name: String, percent: Int): JSONObject {
        val player = state.players().firstOrNull { it.name == name }
        require(player?.volume != null)
        return FluxMediaProtocol.volume(name, percent.coerceIn(0, 100))
    }
}

/** Map monitor protects receive mutation and short authorization checks, never network I/O. */
internal object FluxMediaGate {
    fun <T : Any> withCurrent(peers: ConcurrentHashMap<String, T>, id: String, session: T,
                              pairedOpen: Boolean, playStore: Boolean, consented: Boolean = true, action: () -> Unit) {
        synchronized(peers) {
            check(!playStore && pairedOpen && consented && peers[id] === session) { "Media session is no longer authorized" }
            action()
        }
    }
}

/** A queued response from a closed view cannot enter a reopened view. */
internal class FluxMediaConsent {
    @Volatile private var generation = 0L
    @Volatile var enabled = false
        private set
    @Synchronized fun enable(): Long { generation++; enabled = true; return generation }
    @Synchronized fun disable() { generation++; enabled = false }
    fun token(): Long = generation
    fun accepts(token: Long): Boolean = enabled && token == generation
}

/** Lock order: socket serializer -> peer map -> consent. Never hold the map during a write. */
internal class FluxMediaWriter<T : Any>(
    private val peers: ConcurrentHashMap<String, T>, private val id: String, private val session: T,
    private val serializer: Any, private val consent: FluxMediaConsent,
    private val eligible: () -> Boolean, private val abort: () -> Unit,
) {
    private val writing = AtomicBoolean(false)

    fun send(token: Long, write: () -> Unit) {
        synchronized(serializer) {
            writing.set(true)
            try {
                synchronized(peers) {
                    check(peers[id] === session && eligible() && consent.accepts(token)) {
                        "Media session is no longer authorized"
                    }
                }
                write()
            } finally { writing.set(false) }
        }
    }

    /** Safe under the peer-map monitor; raw transport shutdown must occur afterward. */
    fun withdraw() = consent.disable()
    fun abortActive() { if (writing.get()) abort() }
    fun disable() { withdraw(); abortActive() }
}
