package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SmsSafetyTest {
    @Test fun partsNotMessagesAndExactCap() {
        val first = SmsSafety.reserve(emptyList(), 100, 3, 5)!!
        assertEquals(3, SmsSafety.used(first, 101))
        val full = SmsSafety.reserve(first, 101, 2, 5)!!
        assertNull(SmsSafety.reserve(full, 102, 1, 5))
        assertNull(SmsSafety.reserve(full, 103, 1, 2)) // lowering limit doesn't clear ledger
    }
    @Test fun rollingBoundaryAndClockRollback() {
        val items = listOf(SmsSafety.Reservation(100, 20))
        assertEquals(20, SmsSafety.used(items, 50))
        assertEquals(20, SmsSafety.used(items, 100 + SmsSafety.WINDOW_MS - 1))
        assertEquals(0, SmsSafety.used(items, 100 + SmsSafety.WINDOW_MS))
    }
    @Test fun perSimAndLegacyKeys() {
        val n = "+79991234567"
        assertNotEquals(SmsSafety.cooldownKey("SMS", n, "a", true), SmsSafety.cooldownKey("SMS", n, "b", true))
        assertEquals(n, SmsSafety.cooldownKey("SMS", n, "a", false))
        assertEquals(SmsSafety.cooldownKey("SMS", n, "a", false), SmsSafety.cooldownKey("SMS", n, "b", false))
        assertNotEquals(SmsSafety.cooldownKey("SMS", n, "a:b", true), SmsSafety.cooldownKey("SMS", n, "b", true))
    }
    @Test fun serializedReservationsCannotOverspend() = runBlocking {
        val lock = Mutex()
        var ledger = emptyList<SmsSafety.Reservation>()
        coroutineScope {
            repeat(100) { launch(Dispatchers.Default) {
                lock.withLock { SmsSafety.reserve(ledger, 100, 1, 20)?.let { ledger = it } }
            } }
        }
        assertEquals(20, SmsSafety.used(ledger, 100))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsCorruptLedger() {
        SmsSafety.reserve(listOf(SmsSafety.Reservation(100, -1)), 100, 1, 20)
    }
}
