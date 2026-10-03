package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class TimedSmsRuleTest {
    @Test fun expiryStopsRuleWithoutAlarmOrSmsTimer() {
        val rule = Rule(validTo = 3_600_000L)
        assertTrue(rule.isActiveAt(3_599_999))
        assertFalse(rule.isActiveAt(3_600_001))
    }
    @Test fun disablingOrFutureStartStillWins() {
        assertFalse(Rule(enabled = false, validTo = 3_600_000L).isActiveAt(100))
        assertFalse(Rule(validFrom = 200, validTo = 3_600_000L).isActiveAt(100))
    }
}
