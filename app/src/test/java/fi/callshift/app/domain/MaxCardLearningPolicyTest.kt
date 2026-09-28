package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MaxCardLearningPolicyTest {
    private val rule = MaxCardLearningPolicy.Rule(6840, "header", "editor", "target", "View", "hash")
    @Test fun onlyExactVersionLayoutAndActionMatches() {
        assertTrue(MaxCardLearningPolicy.matches(rule, rule.copy()))
        for (changed in listOf(rule.copy(version = 6841), rule.copy(header = "other"),
            rule.copy(input = "other"), rule.copy(targetId = "call"), rule.copy(targetClass = "other"), rule.copy(shape = "changed"))) {
            assertFalse(MaxCardLearningPolicy.matches(rule, changed))
        }
        assertFalse(MaxCardLearningPolicy.matches(rule.copy(shape = ""), rule.copy(shape = "")))
    }
    @Test fun sourceMustBelongToRecentSnapshot() {
        assertTrue(MaxCardLearningPolicy.fresh(2000, 500))
        assertFalse(MaxCardLearningPolicy.fresh(2001, 500))
        assertFalse(MaxCardLearningPolicy.fresh(499, 500))
    }
    @Test fun onlyStructuralMetadataIsSerializedAndReportUsesEnum() {
        assertEquals(rule, Json.decodeFromString<MaxCardLearningPolicy.Rule>(Json.encodeToString(rule)))
        val d = MaxUiDiagnostics { 100 }
        d.start(1000)
        d.cardLearning(MaxCardLearningPolicy.Status.SOURCE_MISSING)
        assertTrue(d.report(36, "0.8.13").contains("Card learning=SOURCE_MISSING"))
        assertFalse(d.report(36, "0.8.13").contains("targetId"))
        d.clear()
        assertTrue(d.report(36, "0.8.13").contains("Card learning=IDLE"))
    }
}
