package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxProfilePolicyTest {
    @Test fun namedProfileUsesPhoneFieldNotDisplayName() {
        assertEquals("+79991234567", MaxProfilePolicy.verifiedPhone("Номер телефона", listOf("Номер телефона", "+7 999 123-45-67"), "89991234567"))
        assertEquals("+79991234567", MaxProfilePolicy.verifiedPhone("Phone number", listOf("8 (999) 123-45-67"), "+79991234567"))
        assertNull(MaxProfilePolicy.verifiedPhone("Рабочий", listOf("+79991234567"), "+79991234567"))
        assertNull(MaxProfilePolicy.verifiedPhone("Номер телефона", listOf("Совпадающее имя"), "+79991234567"))
    }
    @Test fun namesakesAndAmbiguousFieldsCannotAuthorizeSend() {
        assertTrue(MaxProfilePolicy.candidateNameMatches(" Рабочий ", "рабочий"))
        assertFalse(MaxProfilePolicy.candidateNameMatches("Рабочий новый", "Рабочий"))
        assertFalse(MaxProfilePolicy.candidateNameMatches("", null))
        assertNull(MaxProfilePolicy.verifiedPhone("Номер телефона", listOf("+79991234568"), "+79991234567"))
        assertNull(MaxProfilePolicy.verifiedPhone("Номер телефона", listOf("+79991234567", "+79991234568"), "+79991234567"))
        assertNull(MaxProfilePolicy.verifiedPhone("Номер телефона", listOf("Позвоните +79991234567"), "+79991234567"))
    }
    @Test fun diagnosticExportsFieldCategoryNotNameOrPhone() {
        assertEquals(MaxUiDiagnostics.Label.PHONE_FIELD, MaxUiDiagnostics.label(false, "Номер телефона", null, null))
        assertEquals(MaxUiDiagnostics.Label.REDACTED, MaxUiDiagnostics.label(false, "Рабочий Омега", null, null))
    }
}
