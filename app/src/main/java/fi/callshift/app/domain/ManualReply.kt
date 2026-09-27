package fi.callshift.app.domain

import java.net.URLEncoder

object ManualReply {
    fun valid(channel: String?, number: String?, text: String?): Boolean =
        channel != null && ReplyChannel.isManual(channel) && number != null &&
            ReplyChannel.isPhoneAddress(number) && !text.isNullOrBlank() && text.length <= 201

    fun link(channel: String, number: String, text: String): String? {
        require(valid(channel, number, text))
        val encoded = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        return when (channel) {
            "WHATSAPP" -> "https://wa.me/${number.removePrefix("+")}?text=$encoded"
            "TELEGRAM" -> "tg://resolve?phone=${number.removePrefix("+")}&text=$encoded"
            else -> null
        }
    }
}
