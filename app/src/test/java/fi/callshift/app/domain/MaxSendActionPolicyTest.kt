package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxSendActionPolicyTest {
    @Test fun acceptsExplicitLabelsWithoutRequiringResourceId() {
        assertTrue(MaxSendActionPolicy.evidence("Отправить", null, null))
        assertTrue(MaxSendActionPolicy.evidence(null, "Отправить сообщение, кнопка", null))
        assertTrue(MaxSendActionPolicy.evidence("Send message", "Button", null))
        assertTrue(MaxSendActionPolicy.evidence(null, null, "ru.oneme.app:id/chat_input_send_button"))
    }
    @Test fun neverGuessesFromArrowOrNearbyAttachment() {
        for (id in listOf("ru.oneme.app:id/attach", "ru.oneme.app:id/send_voice", "ru.oneme.app:id/send_menu",
            "ru.oneme.app:id/arrow_up", "other.app:id/send_button", "ru.oneme.app:id/unknown"))
            assertFalse(id, MaxSendActionPolicy.evidence(null, null, id))
        assertFalse(MaxSendActionPolicy.evidence("↑", null, null))
        assertFalse(MaxSendActionPolicy.evidence("Отправить", "Прикрепить файл", "ru.oneme.app:id/send_button"))
        assertFalse(MaxSendActionPolicy.evidence(null, "Отправить голосовое сообщение", null))
    }
    @Test fun onlyASingleNestedActionCanBeDeduplicated() {
        assertTrue(MaxSendActionPolicy.redundantParent(1, true))
        assertFalse(MaxSendActionPolicy.redundantParent(1, false))
        assertFalse(MaxSendActionPolicy.redundantParent(2, true))
        assertFalse(MaxSendActionPolicy.redundantParent(0, false))
        assertFalse(MaxSendActionPolicy.conflict(null, null, "ru.oneme.app:id/button_container"))
        assertTrue(MaxSendActionPolicy.conflict(null, null, "ru.oneme.app:id/send_attachment"))
    }
    @Test fun wrapperCannotIncludeEditorOtherButtonsOrMultipleSendLabels() {
        assertTrue(MaxSendActionPolicy.wrapperSafe(0, 0, 1))
        assertFalse(MaxSendActionPolicy.wrapperSafe(1, 0, 1))
        assertFalse(MaxSendActionPolicy.wrapperSafe(0, 1, 1))
        assertFalse(MaxSendActionPolicy.wrapperSafe(0, 0, 2))
        assertFalse(MaxSendActionPolicy.wrapperSafe(0, 0, 0))
    }
}
