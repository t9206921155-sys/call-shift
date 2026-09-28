package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxTransitionPolicyTest {
    private val ready = MaxTransitionPolicy.FinalClick(true, true, true, true, true,
        "+79991234567", "89991234567", "Проверка", "Проверка", 1, 1, 1, false, true)

    @Test fun namedContactPhoneProofThenInputThenFinalClick() {
        // The profile from the user's scenario: explicit labelled phone, not a name match.
        val phone = MaxProfilePolicy.verifiedPhone("Номер телефона", listOf("+7 999 123-45-67"), "89991234567")
        assertNotNull(phone)
        assertEquals("", MaxUiPolicy.editableText("Сообщение", null, true))
        assertTrue(MaxTransitionPolicy.canClick(ready.copy(actualPhone = phone)))
        assertFalse(MaxTransitionPolicy.canClick(ready.copy(actualPhone = "Одинаковое имя")))
        assertFalse(MaxTransitionPolicy.canClick(ready.copy(actualPhone = "+79991234568")))
        // A successful dry check does not authorize a live click.
        assertFalse(MaxTransitionPolicy.canClick(ready.copy(allowed = false)))
    }
    @Test fun everyChangedFinalConditionPreventsClick() {
        for (state in listOf(ready.copy(allowed = false), ready.copy(unlocked = false),
            ready.copy(sameWindow = false), ready.copy(routeCurrent = false), ready.copy(layoutCurrent = false),
            ready.copy(actualPhone = null), ready.copy(actualText = "Чужой черновик"),
            ready.copy(actualText = ""), ready.copy(expectedText = "", actualText = ""),
            ready.copy(headerCount = 0), ready.copy(headerCount = 2), ready.copy(editorCount = 0),
            ready.copy(editorCount = 2), ready.copy(editableCount = 2), ready.copy(password = true),
            ready.copy(sameButton = false))) assertFalse(state.toString(), MaxTransitionPolicy.canClick(state))
    }
    @Test fun keyboardDismissalIsNotMistakenForNavigationAndNeverAllowsBlindBack() {
        fun permitted(was: Boolean = true, visible: Boolean = false, elapsed: Long = 500,
            window: Boolean = true, nodes: Boolean = true, caption: Boolean = true,
            editors: Int = 1, empty: Boolean = true, password: Boolean = false) =
            MaxTransitionPolicy.keyboardReturn(was, visible, elapsed, window, nodes, caption, editors, empty, password)
        assertTrue(permitted())
        assertTrue(permitted(elapsed = 4000))
        assertFalse(permitted(was = false))
        assertFalse(permitted(visible = true))
        assertFalse(permitted(elapsed = 4001))
        assertFalse(permitted(elapsed = -1))
        assertFalse(permitted(window = false))
        assertFalse(permitted(nodes = false))
        assertFalse(permitted(caption = false))
        assertFalse(permitted(editors = 0))
        assertFalse(permitted(editors = 2))
        assertFalse(permitted(empty = false))
        assertFalse(permitted(password = true))
    }
}
