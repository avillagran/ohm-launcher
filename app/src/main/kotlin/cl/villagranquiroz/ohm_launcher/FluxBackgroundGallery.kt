package cl.villagranquiroz.ohm_launcher

import android.graphics.BitmapFactory
import org.json.JSONObject
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A bounded, session-owned album. Preview bytes never become wallpaper originals. */
internal class FluxBackgroundGallery<S : Any>(
    private val allowed: (S) -> Boolean,
    private val send: (S, JSONObject, () -> Boolean) -> Unit,
    private val onCatalog: (S, Catalog) -> Unit,
    private val onSelected: (S, Boolean, () -> Boolean) -> Unit,
    private val onError: (S, () -> Boolean) -> Unit,
    private val now: () -> Long = System::nanoTime,
) {
    class Item(val id: String, val label: String, preview: ByteArray) {
        private val image = preview.copyOf()
        val preview: ByteArray get() = image.copyOf()
    }

    class Catalog(val theme: String, val revision: String, val current: String, items: List<Item>) {
        val items: List<Item> = Collections.unmodifiableList(items.toList())
    }

    companion object {
        const val MAX_ITEMS = 1024
        const val MAX_PREVIEW_BYTES = 16 * 1024
        const val MAX_TOTAL_BYTES = 16 * 1024 * 1024
        const val TIMEOUT_NANOS = 30_000_000_000L
        private const val MAX_OPERATIONS = 4096
        private val operationPattern = Regex("[0-9a-f]{32}")
        private val hashPattern = Regex("[0-9a-f]{64}")
        private val themePattern = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        private val requestKinds = setOf("gallery_begin", "gallery_item", "gallery_end")
        private fun newOperation() = UUID.randomUUID().toString().replace("-", "")
    }

    private open class Operation<S>(val session: S, val id: String, val started: Long)
    private data class Header(val theme: String, val revision: String, val current: String, val count: Int)
    private class Request<S>(session: S, id: String, started: Long) : Operation<S>(session, id, started) {
        var header: Header? = null
        val items = mutableListOf<Item>()
        val ids = mutableSetOf<String>()
        var bytes = 0
    }
    private class Owned<S>(val session: S, val catalog: Catalog, val request: Request<S>)
    private class Selection<S>(session: S, id: String, started: Long, val owner: Owned<S>) :
        Operation<S>(session, id, started) {
        var result: Boolean? = null
    }
    private var request: Request<S>? = null
    private var owner: Owned<S>? = null
    private var selection: Selection<S>? = null
    private var lastError: Operation<S>? = null
    private var deadline: ScheduledFuture<*>? = null
    private val usedOperations = mutableSetOf<String>()

    fun request(session: S, operation: String = newOperation()): Boolean {
        expire()
        if (!allowed(session) || !operationPattern.matches(operation)) return false
        val token = synchronized(this) {
            if (!allowed(session) || !reserve(operation)) return false
            cancelDeadline()
            owner = null
            selection = null
            lastError = null
            Request(session, operation, now()).also { request = it }
        }
        watch(token)
        val sent = emit(session, JSONObject().put("kind", "gallery_request").put("operation", operation)) {
            synchronized(this) { request === token && allowed(session) && !expired(token) }
        }
        return synchronized(this) {
            if (!sent && request === token) { request = null; cancelDeadline() }
            sent && allowed(session) && (request === token || owner?.request === token)
        }
    }

    fun select(session: S, catalog: Catalog, id: String, operation: String = newOperation()): Boolean {
        expire()
        if (!allowed(session) || !operationPattern.matches(operation) || !hashPattern.matches(id)) return false
        val token = synchronized(this) {
            val current = owner ?: return false
            if (current.session !== session || current.catalog !== catalog || !allowed(session) ||
                catalog.items.none { it.id == id } || selection?.let { it.result == null } == true ||
                !reserve(operation)) return false
            lastError = null
            Selection(session, operation, now(), current).also { selection = it }
        }
        watch(token)
        val body = JSONObject().put("kind", "gallery_select").put("operation", operation)
            .put("theme", catalog.theme).put("revision", catalog.revision).put("id", id)
        val sent = emit(session, body) {
            synchronized(this) { selection === token && token.result == null && owner === token.owner &&
                allowed(session) && !expired(token) }
        }
        return synchronized(this) {
            if (!sent && selection === token && token.result == null) { selection = null; cancelDeadline() }
            sent && selection === token && owner === token.owner && allowed(session)
        }
    }

    @Synchronized fun isCurrent(session: S, catalog: Catalog): Boolean =
        owner?.session === session && owner?.catalog === catalog && allowed(session)

    /** Consume every gallery kind, including malformed or unsupported ones, without an echo. */
    fun receive(session: S, body: JSONObject): Boolean {
        val kind = body.opt("kind") as? String ?: return false
        if (!kind.startsWith("gallery_")) return false
        expire()
        val operation = body.opt("operation") as? String ?: return true
        if (!operationPattern.matches(operation) || !allowed(session)) return true
        val callback = synchronized(this) {
            val receiving = request?.takeIf { it.session === session && it.id == operation }
            val choosing = selection?.takeIf { it.session === session && it.id == operation &&
                it.result == null && owner === it.owner }
            when {
                kind in requestKinds && receiving != null -> try {
                    receiveCatalog(receiving, kind, body)
                } catch (_: Exception) { failRequest(receiving) }
                kind == "gallery_selected" && choosing != null -> try {
                    keys(body, setOf("kind", "operation", "ok"))
                    val ok = body.opt("ok") as? Boolean ?: throw IllegalArgumentException()
                    finishSelection(choosing, ok)
                } catch (_: Exception) { finishSelection(choosing, false) }
                kind == "gallery_error" && (receiving != null || choosing != null) -> {
                    // Error frames cannot carry paths, commands or opaque error descriptions.
                    runCatching { keys(body, setOf("kind", "operation")) }
                    if (receiving != null) failRequest(receiving)
                    else finishSelection(checkNotNull(choosing), false)
                }
                else -> null
            }
        }
        callback?.invoke()
        return true
    }

    @Synchronized fun revoke(session: S) {
        if (request?.session === session) { request = null; cancelDeadline() }
        if (owner?.session === session) owner = null
        if (selection?.session === session) { selection = null; cancelDeadline() }
        if (lastError?.session === session) lastError = null
    }

    fun expire() {
        val callback = synchronized(this) {
            request?.takeIf(::expired)?.let { return@synchronized failRequest(it) }
            selection?.takeIf { it.result == null && expired(it) }?.let { finishSelection(it, false) }
        }
        callback?.invoke()
    }

    private fun receiveCatalog(token: Request<S>, kind: String, body: JSONObject): (() -> Unit)? {
        when (kind) {
            "gallery_begin" -> {
                keys(body, setOf("kind", "operation", "theme", "revision", "current", "count"))
                check(token.header == null)
                val theme = text(body, "theme")
                val revision = text(body, "revision")
                val current = text(body, "current")
                val count = body.opt("count") as? Int ?: throw IllegalArgumentException()
                require(themePattern.matches(theme) && ".." !in theme && hashPattern.matches(revision) &&
                    (current.isEmpty() || hashPattern.matches(current)) && count in 0..MAX_ITEMS)
                token.header = Header(theme, revision, current, count)
            }
            "gallery_item" -> {
                keys(body, setOf("kind", "operation", "id", "label", "preview"))
                val header = checkNotNull(token.header)
                require(token.items.size < header.count)
                val id = text(body, "id")
                val label = text(body, "label")
                require(hashPattern.matches(id) && token.ids.add(id) && label.isNotBlank() &&
                    label.codePointCount(0, label.length) <= 160 && label.none { it.isISOControl() })
                val image = preview(text(body, "preview"))
                require(token.bytes + image.size <= MAX_TOTAL_BYTES)
                token.bytes += image.size
                token.items.add(Item(id, label, image))
            }
            "gallery_end" -> {
                keys(body, setOf("kind", "operation"))
                val header = checkNotNull(token.header)
                check(token.items.size == header.count)
                val catalog = Catalog(header.theme, header.revision, header.current, token.items)
                token.items.clear()
                token.ids.clear()
                owner = Owned(token.session, catalog, token)
                request = null
                cancelDeadline()
                return { if (isCurrent(token.session, catalog)) onCatalog(token.session, catalog) }
            }
        }
        return null
    }

    private fun preview(encoded: String): ByteArray {
        require(encoded.length <= ((MAX_PREVIEW_BYTES + 2) / 3) * 4)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size in 1..MAX_PREVIEW_BYTES && Base64.getEncoder().encodeToString(bytes) == encoded &&
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte())
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..320 && bounds.outHeight in 1..180 &&
            bounds.outWidth.toLong() * bounds.outHeight <= 1_000_000)
        return bytes
    }

    private fun failRequest(token: Request<S>): () -> Unit {
        if (request === token) request = null
        lastError = token
        cancelDeadline()
        return {
            val authority = { synchronized(this) { lastError === token && allowed(token.session) } }
            if (authority()) onError(token.session, authority)
        }
    }

    private fun finishSelection(token: Selection<S>, ok: Boolean): () -> Unit {
        token.result = ok
        cancelDeadline()
        return {
            val authority = { synchronized(this) { selection === token && owner === token.owner &&
                token.result == ok && allowed(token.session) } }
            if (authority()) onSelected(token.session, ok, authority)
        }
    }

    private fun reserve(operation: String): Boolean =
        usedOperations.size < MAX_OPERATIONS && usedOperations.add(operation)

    private fun expired(token: Operation<S>): Boolean = now() - token.started >= TIMEOUT_NANOS
    private fun cancelDeadline() { deadline?.cancel(false); deadline = null }
    private fun watch(token: Operation<S>) {
        val future = FluxWallpaper.deadlines.schedule({ expire() }, 30, TimeUnit.SECONDS)
        synchronized(this) {
            if (request === token || selection === token && selection?.result == null) deadline = future
            else future.cancel(false)
        }
    }
    private fun emit(session: S, body: JSONObject, authority: () -> Boolean): Boolean =
        runCatching { check(authority()); send(session, body, authority); true }.getOrDefault(false)
    private fun keys(body: JSONObject, expected: Set<String>) { require(body.keys().asSequence().toSet() == expected) }
    private fun text(body: JSONObject, key: String): String = body.opt(key) as? String ?: throw IllegalArgumentException()
}
