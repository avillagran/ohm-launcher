package cl.villagranquiroz.ohm_launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class OmarchyNotifyPresenterTest {
    @Test
    fun showsNewestMessagesFirstAndHonorsWidgetLimit() {
        val messages = (1..4).map { index ->
            OmarchyNotification(
                id = index.toString(),
                title = "Title $index",
                message = "Message $index",
                source = "test",
                channel = "notifications",
                level = "info",
                timestamp = index.toLong(),
            )
        }

        assertEquals(listOf("4", "3"), OmarchyNotifyPresenter.visible(messages, 2).map(OmarchyNotification::id))
    }
}
