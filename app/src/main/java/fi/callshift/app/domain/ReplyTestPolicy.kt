package fi.callshift.app.domain

/** Manual transport test, not a synthetic telephone call or a rule-engine verdict. */
object ReplyTestPolicy {
    fun eventDirection(reason: String, normal: String): String =
        if (reason.startsWith("manual_channel_test:")) "TEST" else normal
    fun error(channel: String, number: String?, text: String, account: String?, active: Set<String>): String? = when {
        !ReplyChannel.supports(channel) -> "Канал не поддерживается"
        account == null || account !in active -> "Выберите активную SIM; другую карту автоматически не подставляем"
        number == null || !ReplyChannel.isPhoneAddress(number) -> "Введите полный номер получателя: +7… или 8… для российского номера"
        text.isBlank() -> "Введите текст тестового сообщения"
        text.length > SmsAutoReplyPolicy.MAX_LENGTH -> "Текст не должен превышать 201 символ"
        else -> null
    }
    fun action(channel: String, text: String) = Action(autoReplySms = text,
        replyChannel = channel, replyChannels = listOf(channel), replyCooldownMinutes = 1)
}
