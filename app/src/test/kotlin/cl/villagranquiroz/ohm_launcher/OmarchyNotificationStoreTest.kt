package cl.villagranquiroz.ohm_launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OmarchyNotificationStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun persistsBoundedMessagesAndReplacesDuplicateIds() {
        val file = temporary.newFile("notifications.json")
        val store = OmarchyNotificationStore(file, maxMessages = 2, clock = { 1234L })

        store.receive(JSONObject("""{"id":"one","title":"Hermes","message":"First","source":"omarchy-hermes"}"""))
        store.receive(JSONObject("""{"id":"two","message":"Second","channel":"alerts","level":"warning"}"""))
        store.receive(JSONObject("""{"id":"one","message":"Updated"}"""))

        val loaded = OmarchyNotificationStore(file, maxMessages = 2).load()
        assertEquals(listOf("two", "one"), loaded.map(OmarchyNotification::id))
        assertEquals("Updated", loaded.last().message)
        assertEquals(1234L, loaded.last().timestamp)
        assertEquals("notifications", loaded.last().channel)
    }

    @Test
    fun rejectsEmptyAndOversizedMessages() {
        val store = OmarchyNotificationStore(temporary.newFile("notifications.json"))

        assertTrue(runCatching { store.receive(JSONObject("{}")) }.isFailure)
        assertTrue(runCatching {
            store.receive(JSONObject().put("message", "x".repeat(4001)))
        }.isFailure)
    }

    @Test
    fun acceptsIsoTimestampForwardedByOmarchyLink() {
        val notification = OmarchyNotification.fromJson(
            JSONObject("""{"message":"Done","timestamp":"2026-09-13T12:00:00Z"}"""),
            now = 1L,
        )

        assertTrue(notification.timestamp > 1L)
    }
}
