package fi.callshift.app.domain

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Автоответчик, белый список, повторный звонок, срок действия правила. */
class AutoReplyEngineTest {

    private class Store(private val list: List<Rule>) : RuleStore {
        override suspend fun rules() = list.sortedBy { it.priority }
        override suspend fun save(rule: Rule) = rule
        override suspend fun delete(id: Long) = Unit
        override suspend fun setEnabled(id: Long, enabled: Boolean) = Unit
        override suspend fun replaceAll(rules: List<Rule>) = Unit
        override suspend fun nextId() = 1L
    }

    private class Settings(
        override val autoReply: AutoReplySettings = AutoReplySettings(),
        override val whitelist: Set<String> = emptySet(),
        override val repeatCallWindowMs: Long = 0,
        val rejected: Map<String, Long> = emptyMap(),
    ) : SettingsPort {
        override val masterEnabled = true
        override val defaultPolicy = DefaultPolicy()
        override val screenOwnAppCalls = false
        override val logLevel = "INFO"
        override val maskNumbersInLogs = true
        override val ownerNumbers = emptySet<String>()
        override val stormMaxDials = 3
        override val stormWindowMs = 600_000L
        override val dtmfTransferEnabled = false
        override fun lastRejectedAt(e164: String) = rejected[e164]
    }

    private val d = StandardTestDispatcher()
    private val now = 1_800_000_000_000L
    private val number = "+79001234567"
    private val ctx = CallContext(rawHandle = number, e164 = number)
    private val rejectAll = Rule(id = 1, name = "Все", action = Action(verdict = VerdictSpec.DISALLOW_REJECT))

    private fun engine(s: Settings, rules: List<Rule> = emptyList(), inContacts: Boolean? = false) = RuleEngine(
        ruleStore = Store(rules), contacts = { inContacts }, settings = s,
        profileProvider = { PermissionProfile.SCREENING }, simIndexProvider = { null },
        dispatcher = d, clock = { now },
    )

    @Test fun autoReplyRejectsWithSms() = runTest(d) {
        val dec = engine(Settings(AutoReplySettings(enabled = true, text = "В отпуске"))).evaluate(ctx)
        assertEquals(Verdict.DISALLOW_REJECT, dec.verdict)
        assertEquals("В отпуске", dec.matchedAction?.autoReplySms)
        assertEquals(RuleEngine.REASON_AUTO_REPLY, dec.reason)
    }

    @Test fun autoReplyExpired() = runTest(d) {
        val dec = engine(Settings(AutoReplySettings(enabled = true, text = "x", untilMs = now - 1))).evaluate(ctx)
        assertEquals(Verdict.PASS, dec.verdict)
    }

    @Test fun autoReplyUnknownOnlySkipsContacts() = runTest(d) {
        val s = Settings(AutoReplySettings(enabled = true, text = "x", scope = AutoReplySettings.SCOPE_UNKNOWN))
        assertEquals(Verdict.PASS, engine(s, inContacts = true).evaluate(ctx).verdict)
        assertEquals(Verdict.DISALLOW_REJECT, engine(s, inContacts = false).evaluate(ctx).verdict)
        assertEquals(Verdict.PASS, engine(s, inContacts = null).evaluate(ctx).verdict)
    }

    @Test fun whitelistNeverRejected() = runTest(d) {
        val dec = engine(Settings(whitelist = setOf(number)), listOf(rejectAll)).evaluate(ctx)
        assertEquals(Verdict.PASS, dec.verdict)
        assertEquals(RuleEngine.REASON_WHITELIST, dec.reason)
    }

    @Test fun repeatCallPasses() = runTest(d) {
        val s = Settings(repeatCallWindowMs = 180_000, rejected = mapOf(number to now - 60_000))
        assertEquals(RuleEngine.REASON_REPEAT_CALL, engine(s, listOf(rejectAll)).evaluate(ctx).reason)
        val old = Settings(repeatCallWindowMs = 180_000, rejected = mapOf(number to now - 600_000))
        assertEquals(Verdict.DISALLOW_REJECT, engine(old, listOf(rejectAll)).evaluate(ctx).verdict)
    }

    @Test fun ruleValidityPeriod() = runTest(d) {
        val future = rejectAll.copy(validFrom = now + 1000)
        assertEquals(Verdict.PASS, engine(Settings(), listOf(future)).evaluate(ctx).verdict)
        val active = rejectAll.copy(validFrom = now - 1000, validTo = now + 1000)
        assertEquals(Verdict.DISALLOW_REJECT, engine(Settings(), listOf(active)).evaluate(ctx).verdict)
    }

    @Test fun simRuleWithUnknownSimExplainsSkip() = runTest(d) {
        val rule = rejectAll.copy(simSelector = SimSelector.HANDLE_PREFIX + "89701")
        val dec = engine(Settings(), listOf(rule)).evaluate(ctx)
        assertEquals(Verdict.PASS, dec.verdict)
        assertEquals(1, dec.skipped.size)
        org.junit.Assert.assertTrue(dec.skipped[0].contains("не удалось определить SIM"))
    }

    @Test fun simRuleMatchesBySlotWhenIdsDiffer() = runTest(d) {
        val rule = rejectAll.copy(simSelector = SimSelector.HANDLE_PREFIX + "89701")
        val e = RuleEngine(
            ruleStore = Store(listOf(rule)), contacts = { false }, settings = Settings(),
            profileProvider = { PermissionProfile.SCREENING },
            simIndexProvider = { ref -> if (ref?.id == "89701" || ref?.id == "1") 0 else null },
            dispatcher = d, clock = { now },
        )
        val c = ctx.copy(phoneAccount = PhoneAccountRef(id = "1"))
        assertEquals(Verdict.DISALLOW_REJECT, e.evaluate(c).verdict)
    }

    @Test fun disabledRuleExplained() = runTest(d) {
        val dec = engine(Settings(), listOf(rejectAll.copy(enabled = false))).evaluate(ctx)
        assertEquals("«Все» — выключено", dec.skipped.single())
    }
}
