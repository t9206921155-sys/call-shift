package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxProfileCaptureTest {
    private val title = MaxProfileCapture.Header(2, true, false, true)
    private val input = MaxProfileCapture.Input(8, true)
    @Test fun selectsNamedTitleWithoutContactBinding() {
        val found = MaxProfileCapture.select(listOf(title, MaxProfileCapture.Header(3, true, false, false)), listOf(input))
        assertEquals(MaxProfileCapture.Issue.READY, found.issue)
        assertEquals(2, found.headerIndex)
        assertEquals(8, found.inputIndex)
    }
    @Test fun reportsSpecificRefusals() {
        assertEquals(MaxProfileCapture.Issue.NO_INPUT, MaxProfileCapture.select(emptyList(), emptyList()).issue)
        assertEquals(MaxProfileCapture.Issue.NO_HEADER, MaxProfileCapture.select(emptyList(), listOf(input)).issue)
        assertEquals(MaxProfileCapture.Issue.HEADER_ID_MISSING, MaxProfileCapture.select(listOf(title.copy(hasId = false)), listOf(input)).issue)
        assertEquals(MaxProfileCapture.Issue.AMBIGUOUS_HEADER, MaxProfileCapture.select(listOf(title, title.copy(index = 4)), listOf(input)).issue)
        assertEquals(MaxProfileCapture.Issue.INPUT_ID_MISSING, MaxProfileCapture.select(listOf(title), listOf(input.copy(hasId = false))).issue)
        assertEquals(MaxProfileCapture.Issue.AMBIGUOUS_INPUT, MaxProfileCapture.select(listOf(title), listOf(input, input.copy(index = 9, hasId = false))).issue)
    }
    @Test fun sampleLastsTenMinutesButNotAcrossVersionChangesOrClockRollback() {
        assertTrue(MaxProfileCapture.fresh(100_000, 10, 6840, 6840))
        assertFalse(MaxProfileCapture.fresh(600_011, 10, 6840, 6840))
        assertFalse(MaxProfileCapture.fresh(10, 20, 6840, 6840))
        assertFalse(MaxProfileCapture.fresh(100, 10, 6840, 6841))
    }
    @Test fun probeStartAndFailureAreRecordedWithoutMaxEvents() {
        val d = MaxUiDiagnostics { 100 }
        d.start(60_000)
        d.recordCapture(MaxProfileCapture.Issue.WAITING, 6840, true, true)
        d.recordCapture(MaxProfileCapture.Issue.NO_INPUT, 6840, true, true)
        val report = d.report(36, "0.8.4")
        assertTrue(report.contains("Profile capture=NO_INPUT"))
        assertTrue(report.contains("serviceConnected=true"))
        assertFalse(report.contains("frames=0"))
        d.clear()
        assertTrue(d.report(36, "0.8.4").contains("Profile capture=NOT_STARTED"))
    }
}
