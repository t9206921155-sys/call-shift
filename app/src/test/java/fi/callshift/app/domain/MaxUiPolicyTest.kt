package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxUiPolicyTest {
    private val ok = MaxUiPolicy.Check(true, true, "ru.oneme.app", true, 1, "+79991234567", "+79991234567", 1, "")
    @Test fun explicitHintIsNotDraftButMatchingUserTextIsPreserved() {
        assertEquals("", MaxUiPolicy.editableText("Сообщение", "Сообщение", true))
        assertEquals("Сообщение", MaxUiPolicy.editableText("Сообщение", "Сообщение", false))
        assertEquals("Черновик", MaxUiPolicy.editableText("Черновик", "Сообщение", true))
        assertEquals("", MaxUiPolicy.editableText("Сообщение", null, true))
        assertEquals("", MaxUiPolicy.editableText("Message", "", true))
        assertEquals("Сообщение", MaxUiPolicy.editableText("Сообщение", null, false))
        assertEquals("Любой настоящий текст", MaxUiPolicy.editableText("Любой настоящий текст", null, false))
    }
    @Test fun editorReportContainsOnlyFlagsAndResets() {
        val d = MaxUiDiagnostics { 100 }
        d.start(1000)
        d.editorCheck(true, false, false, true, false, false, true)
        assertTrue(d.report(36, "0.8.12").contains("interpretedEmpty)=1,0,0,1,0,0,1"))
        d.clear()
        assertTrue(d.report(36, "0.8.12").contains("interpretedEmpty)=NOT_CHECKED"))
    }
    @Test fun strictPhoneIdentityNotDisplayNames() {
        assertEquals("+79991234567", MaxUiPolicy.phone("8 (999) 123-45-67"))
        assertEquals("+79991234567", MaxUiPolicy.phone("+7 999 123 45 67"))
        for (s in listOf("Рабочий", "Позвони +79991234567", "112", "+79991234567 / +78881234567")) assertNull(s, MaxUiPolicy.phone(s))
    }
    @Test fun onlyFullyVerifiedStatePasses() {
        assertNull(MaxUiPolicy.block(ok))
        for (blocked in listOf(ok.copy(enabled = false), ok.copy(unlocked = false),
            ok.copy(packageName = "other.app"), ok.copy(versionMatches = false),
            ok.copy(headerCount = 0), ok.copy(headerCount = 2), ok.copy(headerPhone = null),
            ok.copy(headerPhone = "+78881234567"), ok.copy(inputCount = 0),
            ok.copy(inputCount = 2), ok.copy(draft = "Чужой черновик"))) assertNotNull(MaxUiPolicy.block(blocked))
    }
    @Test fun newModeDoesNotChangeLegacyMaxOrBecomeManualDraft() {
        assertTrue(ReplyChannel.isManual("MAX"))
        assertTrue(ReplyChannel.supports(MaxUiPolicy.CHANNEL))
        assertFalse(ReplyChannel.isManual(MaxUiPolicy.CHANNEL))
        assertFalse(ManualReply.valid(MaxUiPolicy.CHANNEL, "+79991234567", "text"))
    }
}
