package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class ReplyTestPolicyTest {
    @Test fun testsAreNotLoggedAsRealCalls() {
        assertEquals("TEST", ReplyTestPolicy.eventDirection("manual_channel_test:example", "INCOMING"))
        assertEquals("INCOMING", ReplyTestPolicy.eventDirection("matched_rule", "INCOMING"))
    }
    @Test fun explicitActiveSimRequiredForEveryChannel() {
        for (channel in ReplyChannel.labels.keys) {
            assertNull(ReplyTestPolicy.error(channel, "+79991234567", "Тест", "account-b", setOf("account-a", "account-b")))
            assertNotNull(ReplyTestPolicy.error(channel, "+79991234567", "Тест", null, setOf("account-a")))
            assertNotNull(ReplyTestPolicy.error(channel, "+79991234567", "Тест", "removed", setOf("account-a")))
        }
    }
    @Test fun noInvalidRecipientOrSilentTruncation() {
        assertNotNull(ReplyTestPolicy.error("SMS", "112", "Тест", "a", setOf("a")))
        assertNotNull(ReplyTestPolicy.error("SMS", "+79991234567", " ", "a", setOf("a")))
        assertNotNull(ReplyTestPolicy.error("SMS", "+79991234567", "а".repeat(202), "a", setOf("a")))
        assertNotNull(ReplyTestPolicy.error("UNKNOWN", "+79991234567", "Тест", "a", setOf("a")))
    }
    @Test fun testIsSingleChannelWithCooldownAndNationalEquivalence() {
        for (channel in ReplyChannel.labels.keys) {
            val action = ReplyTestPolicy.action(channel, "Тест")
            assertEquals(listOf(channel), action.replyChannels)
            assertEquals(channel, action.replyChannel)
            assertEquals(1, action.replyCooldownMinutes)
        }
        val n = PhoneNumberNormalizer("FI")
        assertEquals(n.normalize("+7 999 123-45-67").e164, n.normalize("8 (999) 123-45-67").e164)
    }
}
