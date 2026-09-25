package fi.callshift.app.domain

object TelegramReplyPolicy {
    const val CHANNEL = "TELEGRAM_ACCOUNT"
    fun canAddress(expected: String, returnedPhone: String, isBot: Boolean, isSelf: Boolean): Boolean =
        ReplyChannel.isPhoneAddress(expected) && !isBot && !isSelf &&
            expected.removePrefix("+") == returnedPhone.removePrefix("+")
    fun error(code: Int): String = when (code) {
        429 -> "Telegram ограничил частоту запросов. Автоповтор отключён."
        401 -> "Требуется вход в Telegram."
        403 -> "Telegram запретил отправку этому получателю."
        404 -> "Получатель не найден по номеру."
        else -> "Telegram вернул ошибку $code. Автоматической замены на SMS нет."
    }
}
