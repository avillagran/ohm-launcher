package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.json.JSONTokener

/** Data-only selection protocol. Direct edition only; caller owns pairing, transport and applying themes. */
internal class FluxThemeSelection<Session : Any>(private val directEdition: Boolean) {
    companion object {
        const val MAX_FRAME_BYTES = 8192
        const val SELECT = "flux.omarchy_theme.select"
        const val SELECTED = "flux.omarchy_theme.selected"
        private val requestIdPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val themeIdPattern = Regex("[A-Za-z0-9](?:[A-Za-z0-9._ -]{0,62}[A-Za-z0-9])?")

        fun validThemeId(id: String): Boolean = themeIdPattern.matches(id) && ".." !in id
        fun validRequestId(id: String): Boolean = requestIdPattern.matches(id)
    }

    data class Result(val requestId: String, val ok: Boolean, val current: String)
    private data class Entry<Session>(val session: Session, val paired: Boolean, val pending: String? = null)
    private val entries = mutableMapOf<String, Entry<Session>>()
    private val usedIds = mutableSetOf<String>()

    @Synchronized fun bind(peer: String, session: Session, paired: Boolean) {
        entries[peer] = Entry(session, paired)
    }

    @Synchronized fun cancel(peer: String, session: Session) {
        val entry = entries[peer] ?: return
        if (entry.session === session) entries[peer] = entry.copy(pending = null)
    }

    @Synchronized fun revoke(peer: String, session: Session) {
        if (entries[peer]?.session === session) entries.remove(peer)
    }

    @Synchronized fun begin(
        peer: String, session: Session, paired: Boolean, installed: Set<String>,
        id: String, requestId: String, packetId: Long,
    ): String? {
        val entry = entries[peer] ?: return null
        if (!directEdition || entry.session !== session || !entry.paired || !paired || entry.pending != null ||
            !validThemeId(id) || id !in installed || !validRequestId(requestId) || requestId in usedIds ||
            packetId < 0) return null
        val frame = JSONObject().put("id", packetId).put("type", SELECT)
            .put("body", JSONObject().put("version", 1).put("requestId", requestId).put("id", id))
            .toString() + "\n"
        if (frame.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) return null
        usedIds.add(requestId)
        entries[peer] = entry.copy(pending = requestId)
        return frame
    }

    @Synchronized fun accept(
        peer: String, session: Session, paired: Boolean, installed: Set<String>, frame: String,
    ): Result? {
        val entry = entries[peer] ?: return null
        if (!directEdition || entry.session !== session || !entry.paired || !paired || entry.pending == null ||
            !frame.endsWith("\n") || frame.dropLast(1).contains('\n') ||
            frame.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) return null
        val message = runCatching {
            val tokenizer = JSONTokener(frame.dropLast(1))
            val value = tokenizer.nextValue() as? JSONObject ?: return null
            if (tokenizer.nextClean() != '\u0000') return null
            value
        }.getOrNull() ?: return null
        if (message.keys().asSequence().toSet() != setOf("id", "type", "body") ||
            message.opt("id") !is Int && message.opt("id") !is Long ||
            (message.opt("id") as Number).toLong() < 0 || message.opt("type") != SELECTED) return null
        val body = message.opt("body") as? JSONObject ?: return null
        if (body.keys().asSequence().toSet() != setOf("version", "requestId", "ok", "current") ||
            body.opt("version") !is Int || body.optInt("version") != 1 ||
            body.opt("requestId") != entry.pending || body.opt("ok") !is Boolean) return null
        val current = body.opt("current") as? String ?: return null
        if (!validThemeId(current) || current !in installed) return null
        entries[peer] = entry.copy(pending = null)
        return Result(entry.pending, body.getBoolean("ok"), current)
    }
}
