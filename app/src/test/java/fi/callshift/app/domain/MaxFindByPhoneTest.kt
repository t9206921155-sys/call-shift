package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxFindByPhoneTest {
    @Test fun lookupIsNotCallOrGenericSearch() {
        assertTrue(MaxSearchPolicy.isFindByPhoneLabel("Найти по номеру"))
        assertTrue(MaxSearchPolicy.isFindByPhoneAction("Найти по номеру", null))
        assertTrue(MaxSearchPolicy.isFindByPhoneAction(null, "Найти по номеру"))
        assertFalse(MaxSearchPolicy.isFindByPhoneAction("Позвонить", "Найти по номеру"))
        assertFalse(MaxSearchPolicy.isFindByPhoneAction(null, null))
        assertTrue(MaxSearchPolicy.isFindByPhoneLabel(" Find by phone number "))
        assertFalse(MaxSearchPolicy.isFindByPhoneLabel("Позвонить по номеру"))
        assertFalse(MaxSearchPolicy.isFindByPhoneLabel("Поиск"))
        assertFalse(MaxSearchPolicy.isFindByPhoneLabel("Тестовый контакт"))
    }
    @Test fun exactOwnedQueryAndEquivalentRecipientAreRequiredOnce() {
        assertTrue(MaxSearchPolicy.canFindByPhone("89991234567", "89991234567", "+79991234567", false))
        assertTrue(MaxSearchPolicy.canFindByPhone("+79991234567", "+79991234567", "89991234567", false))
        assertFalse(MaxSearchPolicy.canFindByPhone("89991234567", "89991234567", "+79991234567", true))
        assertFalse(MaxSearchPolicy.canFindByPhone("89991234567", "+79991234567", "+79991234567", false))
        assertFalse(MaxSearchPolicy.canFindByPhone("89991234568", "89991234568", "+79991234567", false))
        assertFalse(MaxSearchPolicy.canFindByPhone("Тестовый контакт", "Тестовый контакт", "+79991234567", false))
        assertFalse(MaxSearchPolicy.canFindByPhone(null, null, "+79991234567", false))
    }
}
