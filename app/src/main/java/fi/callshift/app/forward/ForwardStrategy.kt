package fi.callshift.app.forward

import fi.callshift.app.domain.CallContext
import fi.callshift.app.domain.Decision
import fi.callshift.app.domain.ForwardResult
import fi.callshift.app.domain.PermissionProfile
import fi.callshift.app.domain.StrategyId

/** Порт в системные API телефона (реализация — telecom/AndroidTelecomPort). */
interface TelecomPort {
    /** Тихая отправка MMI/USSD-кода на конкретную SIM (ТЗ п. 10.4). */
    suspend fun placeMmi(accountId: String?, mmi: String): ForwardResult

    /** Исходящий дозвон на номер (S2). */
    suspend fun dial(number: String, accountId: String? = null): ForwardResult

    /** Список SIM-аккаунтов: id → метка. */
    fun phoneAccounts(): Map<String, String>

    /** Индекс SIM в порядке, который показывает система (для селектора SIM1/SIM2). */
    fun simIndex(accountId: String?): Int?
}

/** Порт локального/внешнего уведомления (S4). */
interface NotifierPort {
    fun notifyForwarded(text: String, number: String?, ruleName: String?)
    fun notifyError(text: String)
    suspend fun sendExternal(channel: String, endpoint: String?, payload: String): ForwardResult
}

/** Данные, которые получает стратегия на исполнение. */
data class ForwardRequest(
    val ctx: CallContext,
    val decision: Decision,
    val action: fi.callshift.app.domain.Action,
)

/**
 * Стратегия перенаправления (ТЗ п. 3.3, 6.3).
 * Каждая стратегия обязана уметь честно сказать «я недоступна в этом профиле»
 * через [isAvailable] — это требование деградации из ТЗ п. 3.5.
 */
interface ForwardStrategy {
    val id: StrategyId
    fun isAvailable(profile: PermissionProfile): Boolean
    suspend fun execute(req: ForwardRequest): ForwardResult
}
