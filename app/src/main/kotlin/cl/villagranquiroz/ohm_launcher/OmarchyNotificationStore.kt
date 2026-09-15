package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

data class OmarchyNotification(
    val id: String,
    val title: String,
    val message: String,
    val source: String,
    val channel: String,
    val level: String,
    val timestamp: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("message", message)
        .put("source", source)
        .put("channel", channel)
        .put("level", level)
        .put("timestamp", timestamp)

    companion object {
        private val LEVELS = setOf("info", "success", "warning", "error")

        fun fromJson(payload: JSONObject, now: Long = System.currentTimeMillis()): OmarchyNotification {
            fun bounded(name: String, fallback: String, maxLength: Int): String {
                val value = (payload.opt(name) as? String)?.trim().orEmpty().ifEmpty { fallback }
                require(value.length <= maxLength) { "${name}_too_long" }
                return value
            }

            val message = bounded("message", "", 4_000)
            require(message.isNotEmpty()) { "missing_message" }
            val source = bounded("source", "omarchy-link", 80)
            val level = bounded("level", "info", 16).lowercase()
            require(level in LEVELS) { "invalid_level" }
            val suppliedTimestamp = when (val raw = payload.opt("timestamp")) {
                is Number -> raw.toLong().takeIf { it > 0 }
                is String -> {
                    require(raw.length <= 64) { "timestamp_too_long" }
                    runCatching {
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).parse(raw)?.time
                    }.getOrNull()
                }
                else -> null
            }
            return OmarchyNotification(
                id = bounded("id", UUID.randomUUID().toString(), 120),
                title = bounded("title", source, 120),
                message = message,
                source = source,
                channel = bounded("channel", "notifications", 80),
                level = level,
                timestamp = suppliedTimestamp ?: now,
            )
        }
    }
}

interface OmarchyNotificationChannel {
    fun receive(payload: JSONObject): OmarchyNotification
    fun load(): List<OmarchyNotification>
}

class OmarchyNotificationStore(
    private val file: File,
    private val maxMessages: Int = 50,
    private val clock: () -> Long = System::currentTimeMillis,
) : OmarchyNotificationChannel {
    init {
        require(maxMessages > 0)
    }

    @Synchronized
    override fun load(): List<OmarchyNotification> = runCatching {
        if (!file.isFile || file.length() == 0L) return emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                runCatching { OmarchyNotification.fromJson(item) }.getOrNull()?.let(::add)
            }
        }.takeLast(maxMessages)
    }.getOrDefault(emptyList())

    @Synchronized
    override fun receive(payload: JSONObject): OmarchyNotification {
        val notification = OmarchyNotification.fromJson(payload, clock())
        val updated = (load().filterNot { it.id == notification.id } + notification).takeLast(maxMessages)
        write(updated)
        return notification
    }

    private fun write(messages: List<OmarchyNotification>) {
        val parent = file.parentFile ?: File(".")
        parent.mkdirs()
        val temporary = parent.resolve("${file.name}.tmp")
        temporary.writeText(JSONArray(messages.map(OmarchyNotification::toJson)).toString(2))
        check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = true).let { temporary.delete() })
    }
}
