package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject

/** Sends only a caller-selected notification snapshot; never accesses the Android listener. */
internal class FluxNotificationActions(
    private val isPaired: () -> Boolean,
    private val send: (String, JSONObject) -> Unit,
) {
    /** A successful TLS write is not an acknowledgement that the desktop displayed it. */
    fun sendNotification(id: String, appName: String, title: String, text: String, timeMillis: Long) {
        check(isPaired())
        require(id.isNotBlank() && id.toByteArray(Charsets.UTF_8).size <= 256)
        require(appName.isNotBlank() && appName.toByteArray(Charsets.UTF_8).size <= 128)
        require(title.toByteArray(Charsets.UTF_8).size <= 512)
        require(text.toByteArray(Charsets.UTF_8).size <= 4096)
        require((title.isNotBlank() || text.isNotBlank()) && timeMillis > 0)
        // Construct a fresh allowlisted body: never forward actions, replies, icons, or extras.
        val body = JSONObject().put("id", id).put("appName", appName)
            .put("title", title).put("text", text).put("time", timeMillis.toString())
        require(FluxWire.packet(FluxWire.NOTIFICATION, body).toByteArray(Charsets.UTF_8).size <= 8192) {
            "Notification packet exceeds control frame limit"
        }
        send(FluxWire.NOTIFICATION, body)
    }
}
