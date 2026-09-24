package fi.callshift.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsAutoReplyPolicyTest {
    private val p = SmsAutoReplyPolicy()
    private val now = 10_000_000_000L

    @Test fun sendsOnReject() {
        val r = p.decide(Verdict.DISALLOW_REJECT, " Перезвоню позже ", "+358401234567", null, now)
        assertEquals(SmsAutoReplyPolicy.Result.Send("+358401234567", "Перезвоню позже"), r)
    }

    @Test fun sendsOnMissed() {
        assertTrue(p.decide(Verdict.DISALLOW_AS_MISSED, "x", "+358401234567", null, now) is SmsAutoReplyPolicy.Result.Send)
    }

    @Test fun skipsWhenPass() = assertSkip("verdict_not_reject", p.decide(Verdict.PASS, "x", "+358401234567", null, now))
    @Test fun skipsWithoutText() = assertSkip("no_text", p.decide(Verdict.DISALLOW_REJECT, "  ", "+358401234567", null, now))
    @Test fun skipsHidden() = assertSkip("unknown_number", p.decide(Verdict.DISALLOW_REJECT, "x", null, null, now))
    @Test fun skipsShort() = assertSkip("short_number", p.decide(Verdict.DISALLOW_REJECT, "x", "900", null, now))

    @Test fun cooldown() {
        assertSkip("cooldown", p.decide(Verdict.DISALLOW_REJECT, "x", "+358401234567", now - 60_000, now))
        val after = now - SmsAutoReplyPolicy.COOLDOWN_MS - 1
        assertTrue(p.decide(Verdict.DISALLOW_REJECT, "x", "+358401234567", after, now) is SmsAutoReplyPolicy.Result.Send)
    }

    @Test fun truncates() {
        val r = p.decide(Verdict.DISALLOW_REJECT, "a".repeat(500), "+358401234567", null, now) as SmsAutoReplyPolicy.Result.Send
        assertEquals(SmsAutoReplyPolicy.MAX_LENGTH, r.text.length)
    }

    private fun assertSkip(reason: String, r: SmsAutoReplyPolicy.Result) =
        assertEquals(SmsAutoReplyPolicy.Result.Skip(reason), r)
}
