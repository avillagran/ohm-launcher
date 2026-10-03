package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/** Data only: callers decide whether and how to display it. No automatic actions. */
internal sealed interface FluxIncomingShare {
    data class Text(val value: String) : FluxIncomingShare
    data class Url(val value: String) : FluxIncomingShare
}

/** Never deliver a desktop share before exact-session authorization. */
internal object FluxIncomingShareGate {
    fun deliver(line: String, paired: Boolean, live: Boolean, onShare: (FluxIncomingShare) -> Unit) {
        if (!paired || !live) return
        FluxIncomingShareParser.parse(line)?.let(onShare)
    }
}

/** Parses a single authenticated desktop share frame; never reads payload sockets. */
internal object FluxIncomingShareParser {
    private const val TYPE = "kdeconnect.share.request"
    private const val MAX_FRAME_BYTES = 72 * 1024
    private const val MAX_TEXT_BYTES = 64 * 1024
    private const val MAX_URL_BYTES = 2048

    fun parse(line: String): FluxIncomingShare? {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) return null
        return runCatching {
            val tokens = JSONTokener(line)
            val packet = tokens.nextValue() as? JSONObject ?: return null
            if (tokens.nextClean() != '\u0000') return null
            if (packet.opt("id") !is Number || packet.optString("type") != TYPE ||
                packet.has("payloadSize") || packet.has("payloadTransferInfo")) return null
            val body = packet.opt("body") as? JSONObject ?: return null
            val keys = body.keys()
            while (keys.hasNext()) if (keys.next() !in setOf("text", "url", "mime")) return null
            val hasText = body.has("text")
            val hasUrl = body.has("url")
            if (hasText == hasUrl) return null
            val key = if (hasText) "text" else "url"
            val value = body.opt(key) as? String ?: return null
            val mime = if (body.has("mime")) body.opt("mime") as? String ?: return null else null
            if (mime != null && mime != if (hasText) "text/plain" else "text/uri-list") return null
            if (hasText) {
                if (value.isBlank() || value.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES ||
                    !Charsets.UTF_8.newEncoder().canEncode(value) ||
                    value.any { it.isISOControl() && it !in "\t\n\r" }) return null
                FluxIncomingShare.Text(value)
            } else {
                if (!validUrl(value)) return null
                FluxIncomingShare.Url(value)
            }
        }.getOrNull()
    }

    private fun validUrl(value: String): Boolean {
        if (value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > MAX_URL_BYTES ||
            !value.all { it.code in 33..126 } || !Charsets.UTF_8.newEncoder().canEncode(value)) return false
        val url = URI(value)
        if (url.scheme != "http" && url.scheme != "https") return false
        if (url.isOpaque || url.host.isNullOrEmpty() || url.rawUserInfo != null || url.rawFragment != null) return false
        if (url.port > 65535 || url.port == 0) return false
        // Require the authority to match the parsed host/port exactly; URI may tolerate a dangling colon.
        val suffix = if (url.port >= 0) ":${url.port}" else ""
        return url.rawAuthority == url.host + suffix || url.rawAuthority == "[${url.host}]$suffix"
    }
}
