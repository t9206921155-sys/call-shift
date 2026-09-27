package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxRoutePolicyTest {
    private val picker = MaxRoutePolicy.Picker("system.example", 7, "Window", listOf("MAX", "MAX (копия)"), "structure")
    @Test fun simIdentityNotLabelOrSlotSelectsRoute() {
        val main = MaxRoutePolicy.Route(picker, "MAX")
        val clone = MaxRoutePolicy.Route(picker, "MAX (копия)")
        val routes = mapOf("account-a" to main, "account-b" to clone)
        assertEquals(clone, MaxRoutePolicy.resolve("account-b", setOf("account-b", "account-a"), routes))
        assertNull(MaxRoutePolicy.resolve(null, routes.keys, routes))
        assertNull(MaxRoutePolicy.resolve("account-a", setOf("account-b"), routes))
        assertNull(MaxRoutePolicy.resolve("SIM 1", routes.keys, routes))
    }
    @Test fun noOrderOrManufacturerDependency() {
        assertTrue(MaxRoutePolicy.matches(picker, picker.copy(labels = picker.labels.reversed()), "MAX (копия)"))
        assertTrue(MaxRoutePolicy.chooserTitle("Выберите приложение для открытия"))
        assertTrue(MaxRoutePolicy.chooserTitle("Open with"))
        assertFalse(MaxRoutePolicy.chooserTitle("Settings"))
    }
    @Test fun refusesChangedOrAmbiguousPicker() {
        assertFalse(MaxRoutePolicy.matches(picker, picker.copy(version = 8), "MAX"))
        assertFalse(MaxRoutePolicy.matches(picker, picker.copy(packageName = "untrusted"), "MAX"))
        assertFalse(MaxRoutePolicy.matches(picker, picker.copy(shape = "other screen"), "MAX"))
        assertFalse(MaxRoutePolicy.matches(picker, picker, "Other app"))
        assertFalse(MaxRoutePolicy.validLabels(listOf("MAX", "MAX")))
        assertFalse(MaxRoutePolicy.validLabels(listOf("MAX", "MAX (копия)", "MAX (ещё)")))
        assertFalse(MaxRoutePolicy.candidateLabel("MAX chat private message"))
    }
    @Test fun stopReasonDoesNotExportMessageOrSenderMetadata() {
        val d = MaxUiDiagnostics { 100 }
        d.start(1000)
        d.stopReason("MAX: SIM звонка не определена")
        assertTrue(d.report(36, "0.8.5").contains("Stop reason=SIM_UNKNOWN"))
        d.stopReason("private@example.org")
        assertFalse(d.report(36, "0.8.5").contains("private@example.org"))
        d.clear()
        assertTrue(d.report(36, "0.8.5").contains("Stop reason=NONE"))
    }
}
