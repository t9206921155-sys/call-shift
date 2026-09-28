package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxOpenSearchTest {
    @Test fun actualGlobalHintAndSectionLabels() {
        assertTrue(MaxSearchPolicy.isSearchLabel("Люди, чаты и сообщения"))
        assertTrue(MaxSearchPolicy.isGlobalSearchLabel("  Люди, чаты\nи сообщения "))
        assertTrue(MaxSearchPolicy.isGlobalSearchSection("НЕДАВНО ИСКАЛИ"))
        assertTrue(MaxSearchPolicy.isGlobalSearchSection("ВСЕ КОНТАКТЫ"))
        assertFalse(MaxSearchPolicy.isGlobalSearchLabel("Поиск"))
        assertFalse(MaxSearchPolicy.isGlobalSearchLabel("Поиск в чате"))
        assertFalse(MaxSearchPolicy.isGlobalSearchSection("Тестовый контакт"))
    }
    @Test fun noSelectedTabNeededButOtherGuardsRemain() {
        assertTrue(MaxChatListPolicy.openSearch(0, 0, 1, 1, true, 0))
        assertFalse(MaxChatListPolicy.openSearch(1, 0, 1, 1, true, 0))
        assertFalse(MaxChatListPolicy.openSearch(0, 1, 1, 1, true, 0))
        assertFalse(MaxChatListPolicy.openSearch(0, 0, 2, 1, true, 0))
        assertFalse(MaxChatListPolicy.openSearch(0, 0, 1, 0, true, 0))
        assertFalse(MaxChatListPolicy.openSearch(0, 0, 1, 2, true, 0))
        assertFalse(MaxChatListPolicy.openSearch(0, 0, 1, 1, false, 0))
        assertFalse(MaxChatListPolicy.openSearch(0, 0, 1, 1, true, 1))
    }
    @Test fun hintAndUserQueryAreNotInterchangeable() {
        assertEquals("", MaxUiPolicy.editableText("Люди, чаты и сообщения", "Люди, чаты и сообщения", true))
        assertEquals("Люди, чаты и сообщения", MaxUiPolicy.editableText("Люди, чаты и сообщения", "Люди, чаты и сообщения", false))
        assertEquals("Чужой запрос", MaxUiPolicy.editableText("Чужой запрос", "Люди, чаты и сообщения", false))
    }
    @Test fun reportHasCountsNotScreenText() {
        val d = MaxUiDiagnostics { 100 }
        d.start(1000)
        d.globalSearchCheck(1, 1, true, true)
        assertTrue(d.report(36, "0.8.10").contains("section,confirmed)=1,1,1,1"))
        d.clear()
        assertTrue(d.report(36, "0.8.10").contains("section,confirmed)=NOT_CHECKED"))
    }
}
