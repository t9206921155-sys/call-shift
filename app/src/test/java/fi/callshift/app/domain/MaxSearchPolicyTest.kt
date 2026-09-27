package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxSearchPolicyTest {
    @Test fun russianFormatsProduceSameSearchesAndIdentity() {
        val expected = listOf("+79991234567", "89991234567")
        for (number in listOf("+79991234567", "8 (999) 123-45-67", "79991234567", "+7\u00a0999 123 45 67")) {
            assertEquals(expected, MaxSearchPolicy.queries(number))
            assertTrue(MaxSearchPolicy.equivalent(number, "+79991234567"))
            assertTrue(MaxSearchPolicy.equivalent("89991234567", number))
        }
    }
    @Test fun internationalPlusEightIsNotRussianNationalEight() {
        assertEquals(listOf("+86123456789"), MaxSearchPolicy.queries("+86 123456789"))
        assertFalse(MaxSearchPolicy.equivalent("+86123456789", "86123456789"))
    }
    @Test fun noNamesPartialNumbersOrAmbiguousStrings() {
        for (value in listOf("Рабочий", "1234567", "Звоните +79991234567", "++79991234567", "7+9991234567", "")) {
            assertTrue(value, MaxSearchPolicy.queries(value).isEmpty())
            assertFalse(MaxSearchPolicy.equivalent(value, value))
        }
        assertFalse(MaxSearchPolicy.equivalent("89991234567", "+79991234568"))
        assertFalse(MaxSearchPolicy.equivalent(null, null))
    }
    @Test fun composerLabelsAreNotSearchFields() {
        assertTrue(MaxSearchPolicy.isSearchLabel("Поиск"))
        assertTrue(MaxSearchPolicy.isSearchLabel(" Search "))
        assertFalse(MaxSearchPolicy.isSearchLabel("Сообщение"))
        assertFalse(MaxSearchPolicy.isSearchLabel("Написать сообщение"))
    }
    @Test fun finalGateAlsoAcceptsNationalFormWithoutBinding() {
        val check = MaxUiPolicy.Check(true, true, MaxUiPolicy.PACKAGE, true, 1,
            "+79991234567", "89991234567", 1, "")
        assertNull(MaxUiPolicy.block(check))
    }
}
