package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxUiDiagnosticsTest {
    private var time = 1L
    private val d = MaxUiDiagnostics { time }
    private fun node(value: String, password: Boolean = false) = MaxUiDiagnostics.Node(true, false, false, true, true, true,
        MaxUiDiagnostics.label(password, value, value, value))
    @Test fun optInExpiryAndClear() {
        d.record(MaxUiDiagnostics.Stage.OBSERVE, 10, true, false, listOf(node("Поиск")))
        assertTrue(d.report(28, "0.8.2").contains("frames=0"))
        d.start(10)
        d.record(MaxUiDiagnostics.Stage.OBSERVE, 10, true, false, listOf(node("Поиск")))
        time = 11
        assertFalse(d.active())
        d.record(MaxUiDiagnostics.Stage.STOP, 10, true, false)
        assertTrue(d.report(28, "0.8.2").contains("frames=1"))
        d.clear()
        assertTrue(d.report(28, "0.8.2").contains("frames=0"))
    }
    @Test fun rawUiDataNeverEntersReport() {
        d.start(100)
        val secrets = listOf("+79991234567", "8 (999) 123-45-67", "Иван Петров", "Пароль 834296", "Личная переписка", "ru.oneme.app:id/user_79991234567")
        d.record(MaxUiDiagnostics.Stage.SEARCH, 123, true, true, secrets.map { node(it) } + node("secretPassword", true), "Личная переписка")
        val report = d.report(35, "Личная переписка")
        for (secret in secrets + "secretPassword") assertFalse(report.contains(secret))
        assertEquals(MaxUiDiagnostics.Label.PASSWORD, node("+79991234567", true).label)
        assertEquals(MaxUiDiagnostics.Label.SEARCH, node("Поиск").label)
    }
    @Test fun repeatedFramesAreDeduplicatedAndBounded() {
        d.start(100)
        repeat(10) { d.record(MaxUiDiagnostics.Stage.OBSERVE, 1, true, false) }
        assertTrue(d.report(35, "0.8.2").contains("frames=1"))
        repeat(100) { d.record(MaxUiDiagnostics.Stage.OBSERVE, it.toLong(), true, false) }
        assertTrue(d.report(35, "0.8.2").contains("frames=24"))
    }
}
