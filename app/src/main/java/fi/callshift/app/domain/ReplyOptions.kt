package fi.callshift.app.domain

/** Null selection is the legacy single-channel format. Empty explicitly means none. */
object ReplyOptions {
    val intervals = linkedMapOf(
        0 to "На каждый подходящий звонок",
        1 to "Не чаще раза в 1 минуту",
        5 to "Не чаще раза в 5 минут",
        15 to "Не чаще раза в 15 минут",
        30 to "Не чаще раза в 30 минут",
        60 to "Не чаще раза в 1 час",
        180 to "Не чаще раза в 3 часа",
        1440 to "Не чаще раза в сутки",
    )
    fun channels(legacy: String, selected: List<String>?): List<String> =
        (selected ?: listOf(legacy)).distinct()
    fun cooldownMs(minutes: Int): Long = minutes.coerceIn(0, 1440) * 60_000L
    fun intervalLabel(minutes: Int): String = intervals[minutes] ?: "Не чаще раза в ${minutes.coerceIn(0, 1440)} мин"
    fun labels(legacy: String, selected: List<String>?): String = channels(legacy, selected)
        .joinToString(" + ") { ReplyChannel.labels[it] ?: "Неизвестный канал" }.ifBlank { "Без ответа" }
}
