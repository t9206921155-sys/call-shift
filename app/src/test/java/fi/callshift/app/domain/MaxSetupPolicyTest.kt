package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxSetupPolicyTest {
    private fun next(phone: Boolean = true, sim: Boolean = true, service: Boolean = true,
        route: Boolean = true, layout: Boolean = true, running: Boolean = false,
        card: Boolean = false, passed: Boolean = false, live: Boolean = false, unavailable: Boolean = false, master: Boolean = true) =
        MaxSetupPolicy.next(phone, sim, service, route, layout, running, card, passed, live, unavailable, master)
    @Test fun showsOnlyTheNextMissingStep() {
        assertEquals(MaxSetupPolicy.Step.PHONE_PERMISSION, next(phone = false, sim = false))
        assertEquals(MaxSetupPolicy.Step.SIM, next(sim = false))
        assertEquals(MaxSetupPolicy.Step.SERVICE, next(service = false))
        assertEquals(MaxSetupPolicy.Step.ROUTE, next(route = false))
        assertEquals(MaxSetupPolicy.Step.LAYOUT, next(layout = false))
        assertEquals(MaxSetupPolicy.Step.CARD, next(card = true))
        assertEquals(MaxSetupPolicy.Step.CHECK, next())
        assertEquals(MaxSetupPolicy.Step.MASTER, next(master = false))
    }
    @Test fun neverTurnsDryCheckOrOldLiveSettingIntoProof() {
        assertEquals(MaxSetupPolicy.Step.CHECK, next(live = true))
        assertEquals(MaxSetupPolicy.Step.ENABLE, next(passed = true))
        assertEquals(MaxSetupPolicy.Step.LIVE, next(passed = true, live = true))
        assertEquals(MaxSetupPolicy.Step.RUNNING, next(running = true, passed = true, live = true))
        assertEquals(MaxSetupPolicy.Step.UNAVAILABLE, next(card = true, unavailable = true))
        assertEquals(MaxSetupPolicy.Step.UNAVAILABLE, next(passed = true, live = true, unavailable = true))
    }
}
