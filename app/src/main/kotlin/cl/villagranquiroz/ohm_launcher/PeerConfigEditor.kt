package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject

/** Persists peers at settings.json's root and reads the temporary nested native shape. */
object PeerConfigEditor {
    fun read(source: String): OmarchyPeer? = runCatching {
        val root = JSONObject(source)
        val peer = root.optJSONObject("omarchyPeer")
            ?: if (!root.has("omarchyPeer")) root.optJSONObject("settings")?.optJSONObject("omarchyPeer") else null
            ?: return null
        LauncherSettings.parse(JSONObject().put("omarchyPeer", peer)).omarchyPeer
    }.getOrNull()

    fun store(source: String, peer: OmarchyPeer?): String {
        val root = JSONObject(source)
        val nestedSettings = root.optJSONObject("settings")
        val previous = root.optJSONObject("omarchyPeer")
            ?: nestedSettings?.optJSONObject("omarchyPeer")
        nestedSettings?.remove("omarchyPeer")

        if (peer == null) {
            root.put("omarchyPeer", JSONObject.NULL)
        } else {
            val peerJson = previous?.let { JSONObject(it.toString()) } ?: JSONObject()
            peerJson.put("ip", peer.host)
            peerJson.put("port", peer.port)
            peerJson.put("id", peer.id)
            root.put("omarchyPeer", peerJson)
        }
        return root.toString(2)
    }
}
