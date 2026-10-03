package cl.villagranquiroz.ohm_launcher

import java.net.URI
import org.json.JSONObject

/** Explicit one-shot phone-to-desktop actions; never reads or monitors the phone clipboard. */
internal class FluxDesktopActions(
    private val isPaired: () -> Boolean,
    private val send: (String, JSONObject) -> Unit,
) {
    fun sendPing(message: String = "") {
        check(isPaired())
        require(message.toByteArray(Charsets.UTF_8).size <= 256)
        val body = JSONObject()
        if (message.isNotEmpty()) body.put("message", message)
        send(FluxWire.PING, body)
    }

    fun sendClipboard(text: String) {
        check(isPaired())
        require(text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
        send(FluxWire.CLIPBOARD, JSONObject().put("content", text))
    }

    fun sendUrl(url: String) {
        check(isPaired())
        require(url.isNotEmpty() && url == url.trim() && url.toByteArray(Charsets.UTF_8).size <= 2048)
        val parsed = runCatching { URI(url) }.getOrNull()
        require(parsed != null)
        require(parsed.scheme.equals("http", ignoreCase = true) || parsed.scheme.equals("https", ignoreCase = true))
        require(!parsed.host.isNullOrEmpty() && parsed.rawUserInfo == null && parsed.port in -1..65535)
        send(FluxWire.SHARE, JSONObject().put("url", url))
    }

    fun sendBattery(charge: Int, charging: Boolean) {
        check(isPaired())
        require(charge in 0..100)
        send(FluxWire.BATTERY, JSONObject()
            .put("currentCharge", charge).put("isCharging", charging)
            .put("thresholdEvent", if (charge <= 15 && !charging) 1 else 0))
    }
}
