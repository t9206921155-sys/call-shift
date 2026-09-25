package fi.callshift.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9Test {
    @Test fun cyrillicAndLatin() {
        assertEquals("4226", T9.encode("Иван"))
        assertEquals("5646", T9.encode("john"))
    }

    @Test fun scoring() {
        assertEquals(100, T9.score("422", "Иван Петров", "+358401112233"))
        assertEquals(80, T9.score("5366", "Иван Петров", "+358401112233"))
        assertEquals(50, T9.score("358", null, "+358401112233"))
        assertTrue(T9.score("1122", "Иван", "+358401112233") > 0)
        assertEquals(0, T9.score("999", "Иван", "+358401112233"))
    }
}
