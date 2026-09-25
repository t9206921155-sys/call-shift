package fi.callshift.app.domain

/** Stable persisted values. Messenger drafts are not automatic deliveries. */
object ReplyChannel {
    const val SMS = "SMS"
    const val WHATSAPP = "WHATSAPP"
    val labels = linkedMapOf(
        SMS to "SMS — автоматически",
        WHATSAPP to "WhatsApp — открыть чат и подтвердить",
        "TELEGRAM" to "Telegram — выбрать чат и подтвердить",
        "MAX" to "MAX — выбрать чат и подтвердить",
        "OTHER" to "Другой мессенджер — выбрать чат и подтвердить",
    )
    fun supports(value: String) = value in labels
    fun isPhoneAddress(number: String) = number.startsWith("+") &&
        number.drop(1).all { it in '0'..'9' } && number.length in 8..16
}
