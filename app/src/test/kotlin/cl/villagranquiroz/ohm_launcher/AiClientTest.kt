package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiClientTest {
    @Test
    fun extractsQmlWidgetBlockFromAssistantText() {
        val response = AiWidgetParser.parse("Ready\n```qml\nText { text: \"ok\" }\n```\nDone")

        assertEquals("Ready\n\nDone", response.text)
        assertEquals("Text { text: \"ok\" }", response.widgetSource)
        assertEquals("qml", response.widgetFormat)
    }

    @Test
    fun buildsOpenAiCompatibleRequestAndParsesItsFirstChoice() {
        var requestedUrl = ""
        var requestedKey = ""
        var requestedBody = JSONObject()
        val transport = AiHttpTransport { url, apiKey, body ->
            requestedUrl = url
            requestedKey = apiKey
            requestedBody = body
            JSONObject("""{"choices":[{"message":{"content":"hello"}}]}""")
        }
        val client = OpenAiChatClient(
            baseUrl = "http://127.0.0.1:11434/v1/",
            apiKey = "",
            model = "local-model",
            systemPrompt = "system",
            transport = transport,
        )

        val response = client.chat("next", listOf(AiMessage("assistant", "prior")))

        assertTrue(client.configured)
        assertEquals("http://127.0.0.1:11434/v1/chat/completions", requestedUrl)
        assertEquals("", requestedKey)
        assertEquals("local-model", requestedBody.getString("model"))
        assertEquals(listOf("system", "assistant", "user"), requestedBody.getJSONArray("messages").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("role") }
        })
        assertEquals("hello", response.text)
    }

    @Test
    fun remoteEndpointRequiresAnApiKeyButLocalEndpointDoesNot() {
        assertFalse(OpenAiChatClient("https://example.com/v1", "", "model", "system").configured)
        assertTrue(OpenAiChatClient("http://localhost:11434/v1", "", "model", "system").configured)
    }
}
