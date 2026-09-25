package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TelegramReplyPolicyTest {
    @Test fun onlyConfirmedExactNumberCanReceive() {
        assertTrue(TelegramReplyPolicy.canAddress("+79161234567", "79161234567", false, false))
        assertFalse(TelegramReplyPolicy.canAddress("+79161234567", "", false, false))
        assertFalse(TelegramReplyPolicy.canAddress("+79161234567", "79161234568", false, false))
        assertFalse(TelegramReplyPolicy.canAddress("+79161234567", "79161234567", true, false))
        assertFalse(TelegramReplyPolicy.canAddress("+79161234567", "79161234567", false, true))
        assertFalse(TelegramReplyPolicy.canAddress("112", "112", false, false))
    }
    @Test fun automaticChannelIsSeparateOptIn() {
        assertTrue(ReplyChannel.isManual("TELEGRAM"))
        assertFalse(ReplyChannel.isManual(TelegramReplyPolicy.CHANNEL))
        assertTrue(ReplyChannel.supports(TelegramReplyPolicy.CHANNEL))
        val action = Action(replyChannel = TelegramReplyPolicy.CHANNEL, autoReplySms = "Занят")
        assertEquals(action, Json.decodeFromString<Action>(Json.encodeToString(action)))
    }
    @Test fun rateLimitIsExplicitAndNoSecretsAreEchoed() {
        assertTrue(TelegramReplyPolicy.error(429).contains("Автоповтор отключён"))
        assertTrue(TelegramReplyPolicy.error(401).contains("вход"))
    }
}
