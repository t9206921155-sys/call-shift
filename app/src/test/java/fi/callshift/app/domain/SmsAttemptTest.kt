package fi.callshift.app.domain

import fi.callshift.app.forward.CallEvent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SmsAttemptTest {
    private fun attempt(count: Int = 2) = SmsAttempt(CallEvent(
        ts = 1, direction = "INCOMING", numberE164 = "+79161234567", numberMasked = "***567",
        sim = "test SIM", ruleId = 1, ruleName = "reject", strategy = "SMS_REPLY", target = null,
        result = "SUBMITTED", errorCode = null, errorMessage = null, reason = "rule",
        screeningMs = 0, forwardMs = 0, totalMs = 0, eventId = "attempt-1",
    ), List(count) { null }, List(count) { null })

    @Test fun fullLifecycle() {
        var a = attempt()
        assertEquals("SUBMITTED", a.status())
        a = a.sentResult(1, -1).sentResult(0, -1)
        assertEquals("SENT", a.status())
        a = a.deliveryResult(0, 0)
        assertEquals("SENT", a.status())
        a = a.deliveryResult(1, 0)
        assertEquals("DELIVERED", a.status())
    }
    @Test fun timeoutsNeverClaimFailureOrDelivery() {
        assertEquals("UNKNOWN", attempt().copy(sendTimedOut = true).status())
        val sent = attempt(1).sentResult(0, -1).copy(deliveryTimedOut = true)
        assertEquals("DELIVERY_UNCONFIRMED", sent.status())
        assertEquals("DELIVERED", sent.deliveryResult(0, 0).status())
    }
    @Test fun deliveryBeforeSentAndLateDuplicateCannotRegress() {
        val a = attempt(1).deliveryResult(0, 0)
        assertEquals("DELIVERED", a.sentResult(0, 4).copy(sendTimedOut = true).status())
        assertEquals(a, a.deliveryResult(0, 64))
    }
    @Test fun temporaryReportCanBecomeFinal() {
        val a = attempt(1).sentResult(0, -1).deliveryResult(0, 32)
        assertEquals("SENT", a.status())
        assertEquals("DELIVERED", a.deliveryResult(0, 0).status())
        assertEquals("DELIVERY_FAILED", a.deliveryResult(0, 64).status())
    }
    @Test fun partialFailureNeverCountsAsSuccess() {
        assertEquals("FAILED", attempt().sentResult(0, -1).sentResult(1, 4).status())
        assertEquals("DELIVERY_FAILED", attempt().deliveryResult(0, 0).deliveryResult(1, 64).status())
    }
    @Test fun invalidAndDuplicateCallbacksAreIgnored() {
        val a = attempt().sentResult(0, 4)
        assertEquals(a, a.sentResult(-1, -1).sentResult(2, -1).sentResult(0, -1))
        assertEquals(a, a.deliveryResult(-1, 0).deliveryResult(0, -1))
    }
    @Test fun persistentRoundTripPreservesPartialReceiptAndIdentity() {
        val a = attempt().sentResult(1, -1).deliveryResult(1, 0)
        val restored = Json.decodeFromString<SmsAttempt>(Json.encodeToString(a))
        assertEquals(a, restored)
        assertEquals("attempt-1", restored.event.eventId)
        assertEquals("DELIVERED", restored.deliveryResult(0, 0).status())
    }
    @Test fun oldJournalWithoutAttemptIdRemainsReadable() {
        val encoded = Json.encodeToString(attempt().event.copy(eventId = null))
        assertNull(Json.decodeFromString<CallEvent>(encoded).eventId)
    }
}
