package fi.callshift.app.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReplyChannelTest {
    @Test fun oldActionsKeepSmsDefault() {
        val action = Json.decodeFromString<Action>("""{"autoReplySms":"Занят"}""")
        assertEquals(ReplyChannel.SMS, action.replyChannel)
    }

    @Test fun channelSurvivesSaveAndLoad() {
        for (channel in ReplyChannel.labels.keys) {
            val action = Action(autoReplySms = "Позвоните позже", replyChannel = channel)
            assertEquals(action, Json.decodeFromString<Action>(Json.encodeToString(action)))
        }
    }

    @Test fun unsupportedChannelDoesNotBecomeSms() {
        assertFalse(ReplyChannel.supports("unknown"))
    }

    @Test fun onlyInternationalPhoneNumbersCanOpenCallerChat() {
        assertTrue(ReplyChannel.isPhoneAddress("+79161234567"))
        for (number in listOf("", "112", "sip:user@example.com", "+1234", "+7916?text=hi", "79161234567")) {
            assertFalse(number, ReplyChannel.isPhoneAddress(number))
        }
    }
}
