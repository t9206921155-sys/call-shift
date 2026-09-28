package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxChatListPolicyTest {
    @Test fun requiresSelectedChatsAndCollectionWithoutComposerOrCard() {
        assertTrue(MaxChatListPolicy.confirmed(0, 0, 1, true, 0, 0))
        assertTrue(MaxChatListPolicy.confirmed(0, 0, 1, true, 1, 1))
        assertFalse(MaxChatListPolicy.confirmed(1, 0, 1, true, 1, 0))
        assertFalse(MaxChatListPolicy.confirmed(0, 1, 1, true, 0, 0))
        assertFalse(MaxChatListPolicy.confirmed(0, 0, 0, true, 0, 0))
        assertFalse(MaxChatListPolicy.confirmed(0, 0, 1, false, 0, 0))
        assertFalse(MaxChatListPolicy.confirmed(0, 0, 1, true, 1, 0))
        assertFalse(MaxChatListPolicy.confirmed(0, 0, 1, true, 2, 2))
    }
    @Test fun exactChatsLabelIsNotIdentity() {
        assertTrue(MaxChatListPolicy.chatsLabel(" Чаты "))
        assertTrue(MaxChatListPolicy.chatsLabel("Chats"))
        assertFalse(MaxChatListPolicy.chatsLabel("Поиск в чате"))
        assertFalse(MaxChatListPolicy.chatsLabel("Тестовый контакт"))
    }
    @Test fun refusalExportIsAClosedCode() {
        val d = MaxUiDiagnostics { 100 }
        d.start(1000)
        d.stopReason("MAX: после возврата общий список чатов не подтверждён")
        assertTrue(d.report(36, "0.8.8").contains("Stop reason=CHAT_LIST_UNCONFIRMED"))
    }
}
