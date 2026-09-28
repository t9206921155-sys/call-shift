package fi.callshift.app.domain

/** A UI check is configuration proof, not delivery proof or a contact binding. */
object MaxTestSessionPolicy {
    fun waitMillis(now: Long, last: Long): Long = if (last <= 0) 0 else
        (60_000L - (now - last)).coerceAtLeast(0)

    fun canStartLive(checked: Boolean, enabled: Boolean, live: Boolean, alreadyAttempted: Boolean) =
        checked && enabled && live && !alreadyAttempted

    fun checked(result: String?, testedRoute: Boolean, storedConfiguration: String?, currentConfiguration: String) =
        result == "UI_CHECKED" && testedRoute && storedConfiguration == currentConfiguration

    fun result(result: String?, detail: String?): String = when (result) {
        null, "UI_PENDING" -> "Завершённого результата пока нет. Не запускайте отправку повторно."
        "UI_CHECKED" -> "Получатель и пустое поле проверены. Сообщение не отправлялось."
        "UI_UNKNOWN" -> "Доставка не подтверждена. Проверьте сообщение у получателя; автоматического повтора не будет.\n${detail.orEmpty()}"
        else -> detail?.takeIf { it.isNotBlank() } ?: "Сценарий остановлен. Отправка не подтверждена; автоматического повтора нет."
    }
}
