package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class SettingsBackupTest {
    private val rule = Rule(id = 1, name = "Отпуск", simSelector = "HANDLE:sim-2", action = Action(
        verdict = VerdictSpec.DISALLOW_REJECT, autoReplySms = "Перезвоню позже", replyCooldownMinutes = 60))
    @Test fun roundTripAndDisabledRestore() {
        val original = SettingsBackup(rules = listOf(rule), autoReply = AutoReplySettings(enabled = true), smsLimit = 10, perSimCooldown = false)
        val decoded = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(original, decoded)
        val safe = BackupCodec.safeRestore(decoded)
        assertFalse(safe.rules.single().enabled)
        assertFalse(safe.autoReply.enabled)
        assertEquals(rule.simSelector, safe.rules.single().simSelector)
        assertEquals(rule.action, safe.rules.single().action)
        assertTrue(original.rules.single().enabled)
    }
    @Test fun endpointsAreNeverExported() {
        val b = SettingsBackup(rules = listOf(rule.copy(action = rule.action.copy(endpoint = "secret-token"))))
        val encoded = BackupCodec.encode(b)
        assertFalse(encoded.contains("secret-token"))
        assertNull(BackupCodec.decode(encoded).rules.single().action.endpoint)
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsDuplicateIds() {
        BackupCodec.encode(SettingsBackup(rules = listOf(rule, rule)))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownVersion() {
        BackupCodec.decode("""{"version":2}""")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidLimit() {
        BackupCodec.decode("""{"smsLimit":0}""")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidTime() {
        BackupCodec.encode(SettingsBackup(rules = listOf(rule.copy(schedule = ScheduleSpec(from = "25:00", to = "12:00")))))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversize() {
        BackupCodec.decode(" ".repeat(BackupCodec.MAX_BYTES + 1))
    }
    @Test fun simpleEditorDoesNotFlattenAdvancedRules() {
        assertTrue(SimpleSmsRule.supports(rule))
        assertFalse(SimpleSmsRule.supports(rule.copy(action = rule.action.copy(strategy = StrategySpec.CALLBACK_DIAL))))
        assertFalse(SimpleSmsRule.supports(rule.copy(action = rule.action.copy(replyChannels = listOf("SMS", "MAX")))))
        assertFalse(SimpleSmsRule.supports(rule.copy(conditions = ConditionGroup(listOf(emptyList(), emptyList())))))
    }
}
