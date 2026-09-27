package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxProfileActionPolicyTest {
    @Test fun declaredAccessibilityActionCountsEvenWithoutClickableFlag() {
        assertTrue(MaxProfileActionPolicy.supportsClick(false, true))
        assertTrue(MaxProfileActionPolicy.supportsClick(true, false))
        assertFalse(MaxProfileActionPolicy.supportsClick(false, false))
    }
    @Test fun explicitProfileAndAvatarOnly() {
        assertEquals(0, MaxProfileActionPolicy.priority(null, "Открыть профиль"))
        assertEquals(1, MaxProfileActionPolicy.priority(null, "Фото профиля"))
        assertNull(MaxProfileActionPolicy.priority("Видеозвонок", "Фото профиля"))
        assertNull(MaxProfileActionPolicy.priority(null, "Меню"))
        assertNull(MaxProfileActionPolicy.priority("Тестовый контакт", null))
        assertNull(MaxProfileActionPolicy.priority(null, "Иконка"))
    }
    @Test fun openerReportContainsOnlyFlagsAndCountsAndClears() {
        val d = MaxUiDiagnostics { 100 }
        d.start(1000)
        d.profileOpener(false, true, 2, true)
        assertTrue(d.report(36, "0.8.7").contains("targetFound)=0,1,2,1"))
        d.clear()
        assertTrue(d.report(36, "0.8.7").contains("targetFound)=NOT_CHECKED"))
    }
    @Test fun priorityIsNotScreenOrderAndTiesBlock() {
        assertEquals(1, MaxProfileActionPolicy.choose(listOf(1, 0, null)))
        assertEquals(0, MaxProfileActionPolicy.choose(listOf(0, 1, null)))
        assertNull(MaxProfileActionPolicy.choose(listOf(0, 0, 1)))
        assertNull(MaxProfileActionPolicy.choose(listOf(null, null)))
    }
}
