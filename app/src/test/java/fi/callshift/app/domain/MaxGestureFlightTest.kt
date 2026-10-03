package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxGestureFlightTest {
    @Test fun onlyMatchingAcknowledgementUnblocksNextAction() {
        val flight = MaxGestureFlight()
        val first = flight.begin(100)!!
        assertTrue(flight.busy)
        assertNull(flight.begin(101))
        assertFalse(flight.resolve(first + 1))
        assertTrue(flight.busy)
        assertTrue(flight.resolve(first))
        assertFalse(flight.busy)
        val second = flight.begin(200)!!
        assertNotEquals(first, second)
        assertFalse(flight.resolve(first))
        assertTrue(flight.busy)
        assertTrue(flight.resolve(second))
        assertFalse(flight.resolve(second))
    }
    @Test fun timeoutStopsScenarioButDoesNotPretendAndroidFinishedGesture() {
        val flight = MaxGestureFlight()
        val token = flight.begin(100)!!
        assertFalse(flight.overdue(2099))
        assertTrue(flight.overdue(2100))
        assertNull(flight.begin(9000))
        assertTrue(flight.busy)
        assertTrue(flight.resolve(token)) // late callback releases gate but cannot revive caller's dead scenario
        assertFalse(flight.overdue(10000))
        assertNotNull(flight.begin(10000))
    }
    @Test fun backwardsClockFailsClosed() {
        val flight = MaxGestureFlight()
        flight.begin(100)
        assertTrue(flight.overdue(99))
        assertTrue(flight.busy)
    }
}
