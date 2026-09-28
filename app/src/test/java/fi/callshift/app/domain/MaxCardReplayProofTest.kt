package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxCardReplayProofTest {
    @Test fun realSequenceRequiresBothCardsAndBothReturns() {
        val p = MaxCardReplayProof()
        assertFalse(p.canSave())
        assertTrue(p.manualCard("8 (999) 123-45-67"))
        assertFalse(p.canSave())
        assertTrue(p.returned())
        assertFalse(p.canSave())
        assertTrue(p.claimReplay())
        assertFalse(p.canSave())
        assertTrue(p.replayCard("+7 999 123 45 67"))
        assertFalse(p.canSave())
        assertTrue(p.returned())
        assertTrue(p.canSave())
        p.reset()
        assertFalse(p.canSave())
    }
    @Test fun cannotSkipManualProofReturnOrReplayAndFailureStaysClosed() {
        for (first in listOf<(MaxCardReplayProof) -> Boolean>(
            { it.returned() }, { it.claimReplay() }, { it.replayCard("+79991234567") })) {
            val p = MaxCardReplayProof()
            assertFalse(first(p))
            assertFalse(p.manualCard("+79991234567"))
            assertFalse(p.canSave())
        }
        val p = MaxCardReplayProof()
        assertTrue(p.manualCard("+79991234567"))
        assertFalse(p.claimReplay())
        assertFalse(p.returned())
        assertFalse(p.canSave())
    }
    @Test fun wrongPhoneOrSecondClickCannotBeSavedOrRetried() {
        val p = MaxCardReplayProof()
        p.manualCard("+79991234567"); p.returned(); p.claimReplay()
        assertFalse(p.replayCard("+79991234568"))
        assertFalse(p.returned())
        assertFalse(p.claimReplay())
        assertFalse(p.canSave())
        p.reset(); p.manualCard("+79991234567"); p.returned()
        assertTrue(p.claimReplay())
        assertFalse(p.claimReplay())
        assertFalse(p.replayCard("+79991234567"))
        assertFalse(p.canSave())
    }
    @Test fun phoneFieldReaderIsSharedByTrainingAndActualVerification() {
        val field = MaxProfilePolicy.readPhoneField("Номер телефона", listOf("Номер телефона", "8 (999) 123-45-67", "+7 999 123 45 67"))
        assertTrue(field.found)
        assertEquals("+79991234567", field.phone)
        val p = MaxCardReplayProof()
        assertTrue(p.manualCard(field.phone!!))
        assertEquals(field.phone, MaxProfilePolicy.verifiedPhone("Номер телефона", listOf(field.phone!!), "+79991234567"))
        val ambiguous = MaxProfilePolicy.readPhoneField("Номер телефона", listOf("+79991234567", "+79991234568"))
        assertTrue(ambiguous.found)
        assertNull(ambiguous.phone)
        assertNull(MaxProfilePolicy.readPhoneField("Биография", listOf("+79991234567")).phone)
        assertFalse(MaxCardReplayProof().manualCard("Одинаковое имя"))
    }
}
