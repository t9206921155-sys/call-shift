package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class ManualReplyTest {
    @Test fun manualTargetsStayInSelectedMessenger() {
        assertEquals(listOf("com.whatsapp"), ManualReply.packages("WHATSAPP"))
        assertEquals(listOf("ru.oneme.app"), ManualReply.packages("MAX"))
        assertTrue(ManualReply.packages("TELEGRAM").all { it.startsWith("org.telegram.") })
        assertTrue(ManualReply.packages("OTHER").isEmpty())
    }
    @Test fun unicodeAndReservedCharactersRoundTripWithoutChangingRecipient() {
        val text = "Привет 👋\n+7 & ? # @тест"
        for (channel in listOf("WHATSAPP", "TELEGRAM")) {
            val link = ManualReply.link(channel, "+79991234567", text)!!
            assertEquals(text, java.net.URLDecoder.decode(link.substringAfter("text="), "UTF-8"))
            assertFalse(link.contains("#"))
        }
    }

    @Test fun phoneLinksKeepExactRecipientAndEncodeText() {
        val number = "+79991234567"
        assertEquals("https://wa.me/79991234567?text=A%20%26%20B%3F", ManualReply.link("WHATSAPP", number, "A & B?"))
        assertEquals("tg://resolve?phone=79991234567&text=A%20%26%20B%3F", ManualReply.link("TELEGRAM", number, "A & B?"))
        assertNull(ManualReply.link("MAX", number, "Текст"))
    }
    @Test fun missingLegacySnapshotMustNotUseCurrentRule() {
        assertFalse(ManualReply.valid(null, "+79991234567", null))
        assertFalse(ManualReply.valid("SMS", "+79991234567", "Text"))
        assertFalse(ManualReply.valid("MAX", "112", "Text"))
        assertFalse(ManualReply.valid("MAX", "+79991234567", ""))
    }
}
