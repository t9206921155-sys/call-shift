package fi.callshift.app.domain

/** Stable persisted values. Messenger drafts are not automatic deliveries. */
object ReplyChannel {
    const val SMS = "SMS"
    const val WHATSAPP = "WHATSAPP"
    val labels = linkedMapOf(
        SMS to "SMS — автоматически",
        TelegramReplyPolicy.CHANNEL to "Telegram — автоматически (мой аккаунт)",
        WHATSAPP to "WhatsApp — РУЧНАЯ отправка",
        "TELEGRAM" to "Telegram — РУЧНАЯ отправка",
        "MAX" to "MAX — РУЧНАЯ отправка",
        "OTHER" to "Другой мессенджер — РУЧНАЯ отправка",
    )
    fun isManual(value: String) = supports(value) && value != SMS && value != TelegramReplyPolicy.CHANNEL
    fun supports(value: String) = value in labels
    fun isPhoneAddress(number: String) = number.startsWith("+") &&
        number.drop(1).all { it in '0'..'9' } && number.length in 8..16
}
