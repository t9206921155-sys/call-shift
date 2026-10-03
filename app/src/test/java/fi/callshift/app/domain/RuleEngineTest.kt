package fi.callshift.app.domain

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private class FakeRuleStore(private var ruleList: List<Rule> = emptyList()) : RuleStore {
        override suspend fun rules(): List<Rule> = ruleList.sortedBy { it.priority }
        override suspend fun save(rule: Rule): Rule {
            ruleList = ruleList + rule
            return rule
        }
        override suspend fun delete(id: Long) {
            ruleList = ruleList.filterNot { it.id == id }
        }
        override suspend fun setEnabled(id: Long, enabled: Boolean) {
            ruleList = ruleList.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }
        override suspend fun replaceAll(rules: List<Rule>) { ruleList = rules }
        override suspend fun nextId(): Long = (ruleList.maxOfOrNull { it.id } ?: 0L) + 1
    }

    private class FakeSettings(
        override var masterEnabled: Boolean = true,
        override var defaultPolicy: DefaultPolicy = DefaultPolicy(VerdictSpec.PASS, StrategySpec.NONE),
        override var screenOwnAppCalls: Boolean = false,
        override var logLevel: String = "INFO",
        override var maskNumbersInLogs: Boolean = true,
        override var ownerNumbers: Set<String> = emptySet(),
        override var stormMaxDials: Int = 3,
        override var stormWindowMs: Long = 600_000L,
        override var dtmfTransferEnabled: Boolean = false,
    ) : SettingsPort

    private val testDispatcher = StandardTestDispatcher()

    @Test
    fun testSavedSimAliasMatchesOnlyResolvedSameSim() = runTest(testDispatcher) {
        val rule = Rule(
            id = 99, name = "SIM 2 reject", simSelector = "HANDLE:legacy-sim2",
            action = Action(verdict = VerdictSpec.DISALLOW_REJECT),
        )
        val engine = RuleEngine(
            ruleStore = FakeRuleStore(listOf(rule)), contacts = { false },
            settings = FakeSettings(), profileProvider = { PermissionProfile.SCREENING },
            simIndexProvider = { ref -> when (ref?.id) {
                "legacy-sim2", "sim2" -> 1
                "sim1" -> 0
                else -> null
            } }, dispatcher = testDispatcher,
        )
        fun ctx(id: String?) = CallContext(
            e164 = "+79161234567", phoneAccount = id?.let { PhoneAccountRef(id = it, label = "") },
        )
        assertTrue(engine.evaluate(ctx("sim2")).shouldDisallow)
        assertFalse(engine.evaluate(ctx("sim1")).shouldDisallow)
        assertFalse(engine.evaluate(ctx(null)).shouldDisallow)
        assertFalse(engine.evaluate(ctx("unknown")).shouldDisallow)
    }

    @Test
    fun testDialerWithoutScreeningCanRejectByDefaultPolicy() = runTest(testDispatcher) {
        val engine = RuleEngine(
            ruleStore = FakeRuleStore(),
            contacts = { false },
            settings = FakeSettings(defaultPolicy = DefaultPolicy(VerdictSpec.DISALLOW_REJECT, StrategySpec.NONE)),
            profileProvider = { PermissionProfile.DIALER },
            simIndexProvider = { null },
            dispatcher = testDispatcher,
        )
        val decision = engine.evaluate(CallContext(rawHandle = "+79161234567", e164 = "+79161234567"))
        assertEquals(Verdict.DISALLOW_REJECT, decision.verdict)
        assertTrue(decision.shouldDisallow)
    }

    @Test
    fun testEmergencyNumberAlwaysPasses() = runTest(testDispatcher) {
        val engine = RuleEngine(
            ruleStore = FakeRuleStore(),
            contacts = { false },
            settings = FakeSettings(),
            profileProvider = { PermissionProfile.SCREENING },
            simIndexProvider = { null },
            dispatcher = testDispatcher,
        )

        val ctx = CallContext(rawHandle = "112", e164 = "112", isEmergency = true)
        val decision = engine.evaluate(ctx)
        assertEquals(Verdict.PASS, decision.verdict)
        assertEquals("emergency_number", decision.reason)
    }

    @Test
    fun testMasterSwitchOffAlwaysPasses() = runTest(testDispatcher) {
        val settings = FakeSettings(masterEnabled = false)
        val engine = RuleEngine(
            ruleStore = FakeRuleStore(),
            contacts = { false },
            settings = settings,
            profileProvider = { PermissionProfile.SCREENING },
            simIndexProvider = { null },
            dispatcher = testDispatcher,
        )

        val ctx = CallContext(rawHandle = "+358401234567", e164 = "+358401234567")
        val decision = engine.evaluate(ctx)
        assertEquals(Verdict.PASS, decision.verdict)
        assertEquals("master_switch_off", decision.reason)
    }

    @Test
    fun testRuleMatchesNumberPrefix() = runTest(testDispatcher) {
        val rule = Rule(
            id = 1,
            name = "Block Spam",
            priority = 10,
            conditions = ConditionGroup(
                listOf(listOf(ConditionSpec(type = RuleEngine.TYPE_NUMBER_MATCH, pattern = "+35840*"))),
            ),
            action = Action(
                verdict = VerdictSpec.DISALLOW_REJECT,
                strategy = StrategySpec.NONE,
            ),
        )

        val engine = RuleEngine(
            ruleStore = FakeRuleStore(listOf(rule)),
            contacts = { false },
            settings = FakeSettings(),
            profileProvider = { PermissionProfile.SCREENING },
            simIndexProvider = { null },
            dispatcher = testDispatcher,
        )

        val ctx = CallContext(rawHandle = "+358401234567", e164 = "+358401234567")
        val decision = engine.evaluate(ctx)
        assertEquals(Verdict.DISALLOW_REJECT, decision.verdict)
        assertTrue(decision.reason.contains("Block Spam"))
    }

    @Test
    fun testRuleMatchesAnonymousCaller() = runTest(testDispatcher) {
        val rule = Rule(
            id = 2,
            name = "Reject Anonymous",
            priority = 10,
            conditions = ConditionGroup(
                listOf(listOf(ConditionSpec(type = RuleEngine.TYPE_ANONYMOUS, value = true))),
            ),
            action = Action(
                verdict = VerdictSpec.DISALLOW_AS_MISSED,
                strategy = StrategySpec.NONE,
            ),
        )

        val engine = RuleEngine(
            ruleStore = FakeRuleStore(listOf(rule)),
            contacts = { false },
            settings = FakeSettings(),
            profileProvider = { PermissionProfile.SCREENING },
            simIndexProvider = { null },
            dispatcher = testDispatcher,
        )

        val ctx = CallContext(rawHandle = null, e164 = null)
        val decision = engine.evaluate(ctx)
        assertEquals(Verdict.DISALLOW_AS_MISSED, decision.verdict)
    }

    @Test
    fun testPriorityOrderRespected() = runTest(testDispatcher) {
        val ruleLow = Rule(
            id = 1,
            name = "Low Priority Pass",
            priority = 100,
            conditions = ConditionGroup(),
            action = Action(verdict = VerdictSpec.PASS),
        )
        val ruleHigh = Rule(
            id = 2,
            name = "High Priority Reject",
            priority = 10,
            conditions = ConditionGroup(),
            action = Action(verdict = VerdictSpec.DISALLOW_REJECT),
        )

        val engine = RuleEngine(
            ruleStore = FakeRuleStore(listOf(ruleLow, ruleHigh)),
            contacts = { false },
            settings = FakeSettings(),
            profileProvider = { PermissionProfile.SCREENING },
            simIndexProvider = { null },
            dispatcher = testDispatcher,
        )

        val ctx = CallContext(rawHandle = "+358401234567", e164 = "+358401234567")
        val decision = engine.evaluate(ctx)
        assertEquals(Verdict.DISALLOW_REJECT, decision.verdict)
        assertTrue(decision.reason.contains("High Priority Reject"))
    }

    @Test
    fun testFallbackToDefaultPolicy() = runTest(testDispatcher) {
        val settings = FakeSettings(
            defaultPolicy = DefaultPolicy(verdict = VerdictSpec.SILENCE, strategy = StrategySpec.NONE),
        )
        val engine = RuleEngine(
            ruleStore = FakeRuleStore(emptyList()),
            contacts = { false },
            settings = settings,
            profileProvider = { PermissionProfile.SCREENING },
            simIndexProvider = { null },
            dispatcher = testDispatcher,
        )

        val ctx = CallContext(rawHandle = "+358401234567", e164 = "+358401234567")
        val decision = engine.evaluate(ctx)
        assertEquals(Verdict.SILENCE, decision.verdict)
        assertEquals("default_policy", decision.reason)
    }

    @Test
    fun testDisabledRuleIsSkipped() = runTest(testDispatcher) {
        val rule = Rule(
            id = 1,
            name = "Disabled",
            enabled = false,
            conditions = ConditionGroup(),
            action = Action(verdict = VerdictSpec.DISALLOW_REJECT),
        )

        val engine = RuleEngine(
            ruleStore = FakeRuleStore(listOf(rule)),
            contacts = { false },
            settings = FakeSettings(),
            profileProvider = { PermissionProfile.SCREENING },
            simIndexProvider = { null },
            dispatcher = testDispatcher,
        )

        val ctx = CallContext(rawHandle = "+358401234567", e164 = "+358401234567")
        val decision = engine.evaluate(ctx)
        assertEquals(Verdict.PASS, decision.verdict)
        assertEquals("default_policy", decision.reason)
    }
}
