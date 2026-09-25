package fi.callshift.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneAccountIdsTest {
    private val accounts = listOf(
        PhoneAccountIds.Account("sim-a", "component, sim-a, UserHandle{0}"),
        PhoneAccountIds.Account("sim-b", "component, sim-b, UserHandle{0}"),
    )

    @Test fun publicIdAndOldSavedIdResolveToSameSim() {
        assertEquals("sim-b", PhoneAccountIds.resolve("sim-b", accounts))
        assertEquals("sim-b", PhoneAccountIds.resolve(accounts[1].legacyRepresentation, accounts))
    }

    @Test fun orderDoesNotChangeSelectedSim() {
        assertEquals("sim-b", PhoneAccountIds.resolve(accounts[1].legacyRepresentation, accounts.reversed()))
    }

    @Test fun unknownOrRemovedSimIsNotReplacedWithAnotherSim() {
        assertNull(PhoneAccountIds.resolve("removed", accounts))
        assertNull(PhoneAccountIds.resolve(null, accounts))
        assertNull(PhoneAccountIds.resolve("sim-b", accounts.take(1)))
    }

    @Test fun ambiguousLegacyFragmentsAreNotGuessed() {
        val ambiguous = listOf(
            PhoneAccountIds.Account("sim-a", "[same-component] sim-a"),
            PhoneAccountIds.Account("sim-b", "[same-component] sim-b"),
        )
        assertNull(PhoneAccountIds.resolve("same-component", ambiguous))
        assertEquals("sim-b", PhoneAccountIds.resolve("sim-b", ambiguous))
    }
}
