package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxTestSessionPolicyTest {
    @Test fun endToEndPermissionAndOneAttemptAreDistinctGates() {
        assertEquals(MaxSetupPolicy.Step.CHECK, MaxSetupPolicy.next(true, true, true, true, true, false, false, false, false))
        val checked = MaxTestSessionPolicy.checked("UI_CHECKED", true, "SIM1:clone1:layout", "SIM1:clone1:layout")
        assertFalse(MaxTestSessionPolicy.canStartLive(checked, true, false, false))
        assertTrue(MaxTestSessionPolicy.canStartLive(checked, true, true, false))
        // Persisted reservation survives recreation and blocks another send even if the journal is pending.
        assertFalse(MaxTestSessionPolicy.canStartLive(checked, true, true, true))
        assertFalse(MaxTestSessionPolicy.canStartLive(checked, false, true, false))
        val otherSim = MaxTestSessionPolicy.checked("UI_CHECKED", true, "SIM1:clone1:layout", "SIM2:clone2:layout")
        assertFalse(MaxTestSessionPolicy.canStartLive(otherSim, true, true, false))
    }
    @Test fun cooldownIsShownWithoutBypassingItOrSchedulingASend() {
        assertEquals(0L, MaxTestSessionPolicy.waitMillis(1000, -1))
        assertEquals(60000L, MaxTestSessionPolicy.waitMillis(1000, 1000))
        assertEquals(1L, MaxTestSessionPolicy.waitMillis(60999, 1000))
        assertEquals(0L, MaxTestSessionPolicy.waitMillis(61000, 1000))
        assertEquals(0L, MaxTestSessionPolicy.waitMillis(90000, 1000))
        assertTrue(MaxTestSessionPolicy.waitMillis(500, 1000) > 60000)
    }
    @Test fun onlyExplicitProfileResourceIdsAreUsable() {
        for (name in listOf("avatar", "contact_avatar", "profile_picture", "toolbar_profile"))
            assertTrue(MaxProfileActionPolicy.profileResource("ru.oneme.app:id/$name"))
        for (id in listOf("other:id/avatar", "ru.oneme.app:id/button42", "ru.oneme.app:id/edit_profile",
            "ru.oneme.app:id/profile_call", "ru.oneme.app:id/avatar_menu", "ru.oneme.app:id/delete_profile"))
            assertFalse(MaxProfileActionPolicy.profileResource(id))
    }
    @Test fun onlyCurrentDryProofEnablesLiveTest() {
        assertTrue(MaxTestSessionPolicy.checked("UI_CHECKED", true, "current", "current"))
        for (result in listOf(null, "BLOCKED", "UI_UNKNOWN", "UI_PENDING"))
            assertFalse(MaxTestSessionPolicy.checked(result, true, "current", "current"))
        assertFalse(MaxTestSessionPolicy.checked("UI_CHECKED", false, "current", "current"))
        assertFalse(MaxTestSessionPolicy.checked("UI_CHECKED", true, "old", "current"))
        assertFalse(MaxTestSessionPolicy.checked("UI_CHECKED", true, null, "current"))
    }
    @Test fun clickDoesNotClaimDeliveryAndPendingDoesNotSuggestRetry() {
        assertTrue(MaxTestSessionPolicy.result("UI_UNKNOWN", "Кнопка нажата").contains("Доставка не подтверждена"))
        assertTrue(MaxTestSessionPolicy.result(null, null).contains("Не запускайте"))
        assertTrue(MaxTestSessionPolicy.result("UI_CHECKED", null).contains("не отправлялось"))
        assertEquals("Лимит", MaxTestSessionPolicy.result("BLOCKED", "Лимит"))
    }
}
