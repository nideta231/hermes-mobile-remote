package io.github.nideta231.hermesremote

import io.github.nideta231.hermesremote.data.Notifier
import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalNotificationTest {
    @Test fun buttonsFollowTheOfferedChoicesInOrder() {
        assertEquals(listOf("deny", "once", "session"),
            Notifier.approvalActions(listOf("once", "session", "always", "deny")).map { it.first })
        assertEquals(listOf("Deny", "Allow"), Notifier.approvalActions(listOf("once", "deny")).map { it.second })
    }

    @Test fun alwaysIsNeverOfferedOnTheNotification() {
        assertEquals(emptyList<String>(), Notifier.approvalActions(listOf("always")).map { it.first })
    }

    @Test fun outcomeTextPerChoice() {
        assertEquals("Denied", Notifier.answeredText("deny"))
        assertEquals("Allowed once", Notifier.answeredText("once"))
        assertEquals("Allowed for this session", Notifier.answeredText("session"))
    }
}
