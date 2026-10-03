package fi.callshift.app

import fi.callshift.app.forward.CallEvent
import fi.callshift.app.ui.EventView
import org.junit.Assert.assertEquals
import org.junit.Test

class MessengerEventTest {
    private val draft = CallEvent(
        ts = 0, direction = "INCOMING", numberE164 = null, numberMasked = "unknown",
        sim = "—", ruleId = null, ruleName = null, strategy = "MESSENGER_DRAFT",
        target = null, result = "PENDING_USER", errorCode = null,
        errorMessage = "Ответ подготовлен, не отправлен", reason = "rule",
        screeningMs = 0, forwardMs = 0, totalMs = 0,
    )

    @Test fun draftIsNotCountedAsSentOrError() {
        assertEquals(EventView.Kind.REPLY_DRAFT, EventView.kind(draft))
        val stats = EventView.stats(listOf(draft))
        assertEquals(0, stats.sms)
        assertEquals(0, stats.forwarded)
        assertEquals(0, stats.errors)
    }

    @Test fun smsIsCountedOnlyAfterConfirmation() {
        for (status in listOf("SUBMITTED", "UNKNOWN", "OK", "FAILED")) {
            assertEquals(0, EventView.stats(listOf(draft.copy(strategy = "SMS_REPLY", result = status))).sms)
        }
        assertEquals(1, EventView.stats(listOf(draft.copy(strategy = "SMS_REPLY", result = "SENT"))).sms)
    }

    @Test fun blockedNotificationIsAnErrorNotDelivery() {
        assertEquals(EventView.Kind.ERROR, EventView.kind(draft.copy(result = "FAILED")))
    }
}
