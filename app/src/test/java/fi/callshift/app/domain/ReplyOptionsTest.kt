package fi.callshift.app.domain

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReplyOptionsTest {
    @Test fun legacyRuleKeepsItsOnlyChannelAndThirtyMinutes() {
        val old = Json.decodeFromString<Action>("""{"replyChannel":"TELEGRAM","autoReplySms":"Занят"}""")
        assertEquals(listOf("TELEGRAM"), ReplyOptions.channels(old.replyChannel, old.replyChannels))
        assertEquals(30, old.replyCooldownMinutes)
    }
    @Test fun selectionRoundTripAndEmptyDoesNotTurnIntoSms() {
        val a = Action(replyChannels = listOf("SMS", "TELEGRAM_ACCOUNT"), replyCooldownMinutes = 5)
        assertEquals(a, Json.decodeFromString<Action>(Json.encodeToString(a)))
        assertTrue(ReplyOptions.channels("SMS", emptyList()).isEmpty())
        assertEquals(listOf("SMS"), ReplyOptions.channels("OTHER", listOf("SMS", "SMS")))
    }
    @Test fun cooldownBoundaryAndEveryCall() {
        val policy = SmsAutoReplyPolicy()
        fun decide(elapsed: Long, minutes: Int) = policy.decide(Verdict.DISALLOW_REJECT, "x", "+79161234567", 1000, 1000 + elapsed, ReplyOptions.cooldownMs(minutes))
        assertTrue(decide(0, 0) is SmsAutoReplyPolicy.Result.Send)
        assertEquals(SmsAutoReplyPolicy.Result.Skip("cooldown"), decide(299999, 5))
        assertTrue(decide(300000, 5) is SmsAutoReplyPolicy.Result.Send)
        assertEquals(SmsAutoReplyPolicy.Result.Skip("cooldown"), decide(3600000, 1440))
    }
    @Test fun passNeverSendsEvenWithoutCooldown() {
        assertEquals(SmsAutoReplyPolicy.Result.Skip("verdict_not_reject"), SmsAutoReplyPolicy().decide(Verdict.PASS, "x", "+79161234567", null, 1, 0))
    }
    @Test fun allChosenChannelsRunOnceAndFailuresAreIndependent() = runTest {
        val attempted = mutableListOf<String>()
        val failures = mutableListOf<String>()
        ReplyFanout.run(listOf("TELEGRAM_ACCOUNT", "SMS", "SMS"), { channel, _ -> failures += channel }) { channel ->
            attempted += channel
            if (channel == "TELEGRAM_ACCOUNT") { delay(1000); error("not connected") }
        }
        assertEquals(setOf("SMS", "TELEGRAM_ACCOUNT"), attempted.toSet())
        assertEquals(2, attempted.size)
        assertEquals(listOf("TELEGRAM_ACCOUNT"), failures)
    }
    @Test fun noSelectionMeansNoAttempt() = runTest {
        ReplyFanout.run(emptyList(), { _, _ -> fail("unexpected error") }) { fail("unexpected send") }
    }
}
