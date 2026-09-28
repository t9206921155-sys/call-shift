package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxDiagnosticSessionTest {
    private var now = 1L
    private val d = MaxUiDiagnostics { now }
    @Test fun recordingLiveRunDoesNotConvertItToDry() {
        d.observe(100)
        assertTrue(d.active())
        assertFalse(d.forcesDry())
        d.attempt(false)
        d.record(MaxUiDiagnostics.Stage.CLICK, 10, true, true, outcome = "UI_UNKNOWN")
        val saved = d.report(36, "0.8.17")
        assertTrue(saved.contains("Attempt=LIVE"))
        assertTrue(saved.contains("CLICK"))
        assertTrue(saved.contains("UI_UNKNOWN"))
        d.clear()
        assertFalse(d.forcesDry())
        assertTrue(saved.contains("CLICK")) // immutable snapshot independent of later memory reset
    }
    @Test fun observationCannotOverrideExplicitDrySessionOrExtendItsExpiry() {
        d.start(10)
        d.observe(1000)
        assertTrue(d.forcesDry())
        now = 11
        assertFalse(d.active())
        assertFalse(d.forcesDry())
        d.observe(100)
        assertTrue(d.active())
        assertFalse(d.forcesDry())
    }
    @Test fun newAttemptResetsOldRecoveryAndOutcome() {
        d.observe(100)
        d.sourceRecovery(MaxCardLearningPolicy.RecoveryReason.AMBIGUOUS_CLASS)
        d.attempt(false)
        d.record(MaxUiDiagnostics.Stage.CLICK, 10, true, true, outcome = "UI_UNKNOWN")
        d.observe(100)
        val report = d.report(36, "0.8.17")
        assertTrue(report.contains("Source recovery=NOT_ATTEMPTED"))
        assertTrue(report.contains("Attempt=NOT_STARTED"))
        assertFalse(report.contains("UI_UNKNOWN"))
    }
    @Test fun missingSourceReasonsAreClosedCodesNotUiContents() {
        val rule = MaxCardLearningPolicy.Rule(10, "header", "input", "ru.oneme.app:id/avatar", "View", "shape")
        fun recover(time: Long = 100, window: Int = 7, cls: String? = "View", rules: List<MaxCardLearningPolicy.Rule> = listOf(rule)) =
            MaxCardLearningPolicy.recovery(time, 100, 7, window, cls, rules).reason
        assertEquals(MaxCardLearningPolicy.RecoveryReason.MATCH, recover())
        assertEquals(MaxCardLearningPolicy.RecoveryReason.STALE, recover(time = 1601))
        assertEquals(MaxCardLearningPolicy.RecoveryReason.WINDOW_MISMATCH, recover(window = 8))
        assertEquals(MaxCardLearningPolicy.RecoveryReason.CLASS_MISSING, recover(cls = null))
        assertEquals(MaxCardLearningPolicy.RecoveryReason.NO_CLASS_MATCH, recover(cls = "secret name 79991234567"))
        assertEquals(MaxCardLearningPolicy.RecoveryReason.AMBIGUOUS_CLASS, recover(rules = listOf(rule, rule.copy(targetId = "other"))))
        assertEquals(MaxCardLearningPolicy.RecoveryReason.UNSAFE_ID, recover(rules = listOf(rule.copy(targetId = "private 79991234567"))))
        d.observe(100)
        d.sourceRecovery(recover(cls = "secret name 79991234567"))
        val report = d.report(36, "0.8.17")
        assertTrue(report.contains("Source recovery=NO_CLASS_MATCH"))
        assertFalse(report.contains("79991234567"))
        assertFalse(report.contains("secret name"))
    }
}
