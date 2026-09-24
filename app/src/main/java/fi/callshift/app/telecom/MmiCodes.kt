package fi.callshift.app.telecom

/**
 * Справочник MMI-кодов (ТЗ Приложение A, 3GPP TS 22.082 / 27.007).
 *
 * Хранится отдельно от логики, чтобы коды можно было переиспользовать
 * в UI («Быстрые пресеты», FR-3.7) и в «Режиме паники» (FR-9.5).
 */
object MmiCodes {

    data class Service(val code: String, val labelRu: String, val supportsTarget: Boolean = true)

    val UNCONDITIONAL = Service("21", "Безусловная: все звонки")
    val NO_REPLY = Service("61", "Если не отвечаю")
    val NOT_REACHABLE = Service("62", "Если недоступен")
    val BUSY = Service("67", "Если занято")
    val ALL_CONDITIONAL = Service("002", "Все условные", supportsTarget = false)
    val ALL = Service("004", "Все переадресации", supportsTarget = false)

    val ALL_SERVICES = listOf(UNCONDITIONAL, NO_REPLY, NOT_REACHABLE, BUSY, ALL_CONDITIONAL, ALL)

    /** Установка: *<код>*<номер без '+'># ; для 61 — *61*<номер>**<сек># */
    fun register(code: String, e164Target: String, noReplySeconds: Int = 20): String {
        val number = e164Target.removePrefix("+")
        return if (code == NO_REPLY.code) {
            val sec = noReplySeconds.coerceIn(5, 30)
            "*${code}*$number**$sec#"
        } else {
            "*${code}*$number#"
        }
    }

    /** Снятие: ##<код># */
    fun erase(code: String): String = "##$code#"

    /** Запрос статуса: *#<код># */
    fun interrogate(code: String): String = "*#$code#"

    /** «Режим паники»: снять безусловную + все условные. */
    fun panicSequence(): List<String> = listOf(erase(UNCONDITIONAL.code), erase(ALL_CONDITIONAL.code), erase(ALL.code))

    fun label(code: String): String = ALL_SERVICES.firstOrNull { it.code == code }?.labelRu ?: "Код $code"
}
