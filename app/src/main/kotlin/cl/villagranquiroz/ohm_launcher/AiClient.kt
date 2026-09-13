package cl.villagranquiroz.ohm_launcher

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets

fun interface AiHttpTransport {
    fun post(url: String, apiKey: String, body: JSONObject): JSONObject
}

internal object AiWidgetParser {
    private val block = Regex("```(?:json|qml|widget)?\\s*\\n(.*?)```", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun parse(content: String): AiResponse {
        val match = block.find(content) ?: return AiResponse(content.trim())
        val opening = match.value.take(12).lowercase()
        val format = if (opening.contains("qml")) "qml" else "json"
        val text = listOf(content.substring(0, match.range.first), content.substring(match.range.last + 1))
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString("\n\n")
            .ifEmpty { "(componente generado)" }
        return AiResponse(text, match.groupValues[1].trim(), format)
    }
}

class OpenAiChatClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val systemPrompt: String,
    private val transport: AiHttpTransport = UrlConnectionAiTransport(),
) {
    val configured: Boolean
        get() {
            val uri = runCatching { URI(baseUrl) }.getOrNull() ?: return false
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || model.isBlank()) return false
            val needsKey = !baseUrl.contains("localhost") && !baseUrl.contains("127.0.0.1")
            return !needsKey || apiKey.isNotBlank()
        }

    fun chat(prompt: String, history: List<AiMessage>? = null): AiResponse {
        require(configured) { "AI is not configured" }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
        history.orEmpty().forEach { message ->
            messages.put(JSONObject().put("role", message.role).put("content", message.content))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val request = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.7)
        return try {
            val response = transport.post(baseUrl.trimEnd('/') + "/chat/completions", apiKey, request)
            val content = response.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            AiWidgetParser.parse(content)
        } catch (error: Exception) {
            AiResponse("ai_client_error: ${error.message ?: error.javaClass.simpleName}")
        }
    }
}

class UrlConnectionAiTransport : AiHttpTransport {
    override fun post(url: String, apiKey: String, body: JSONObject): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > MAX_RESPONSE_BYTES) error("AI response is too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            val text = bytes.toString(StandardCharsets.UTF_8)
            if (code !in 200..299) error("HTTP $code: $text")
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
    }
}
