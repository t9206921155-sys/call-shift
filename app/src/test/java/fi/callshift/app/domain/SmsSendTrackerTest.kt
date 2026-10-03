package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class SmsSendTrackerTest {
    @Test fun singlePart() { assertEquals(listOf(-1), SmsSendTracker(1).accept(0, -1)) }
    @Test fun allPartsAreRequiredInAnyOrder() {
        val tracker = SmsSendTracker(3)
        assertNull(tracker.accept(2, -1))
        assertNull(tracker.accept(0, -1))
        assertEquals(listOf(-1, -1, -1), tracker.accept(1, -1))
    }
    @Test fun duplicateDoesNotHideFailure() {
        val tracker = SmsSendTracker(2)
        assertNull(tracker.accept(0, 4))
        assertNull(tracker.accept(0, -1))
        assertEquals(listOf(4, -1), tracker.accept(1, -1))
        assertNull(tracker.accept(1, -1))
    }
    @Test fun invalidSegmentsAreIgnored() {
        val tracker = SmsSendTracker(1)
        assertNull(tracker.accept(-1, -1))
        assertNull(tracker.accept(1, -1))
        assertEquals(listOf(-1), tracker.accept(0, -1))
    }
}
