package fi.callshift.app.forward

import fi.callshift.app.domain.ForwardResult
import fi.callshift.app.domain.PermissionProfile
import fi.callshift.app.domain.StrategyId
import org.json.JSONObject

/**
 * Очередь повторной доставки внешнего уведомления (ТЗ FR-6.3:
 * WorkManager, backoff ≤ 15 мин, очередь ≤ 100 событий).
 */
fun interface ExternalRetryQueue {
    fun enqueue(channel: String, endpoint: String, payload: String)
}

/**
 * S4 — уведомление / эскалация (ТЗ п. 4.6).
 *
 * Вызов при этом НЕ отклоняется автоматически (если в правиле не задан
 * соответствующий verdict) — пользователь получает уведомление с кнопками,
 * а событие дублируется во внешний канал (Telegram / webhook).
 */
class NotifyForwardStrategy(
    private val notifier: NotifierPort,
    private val retryQueue: ExternalRetryQueue? = null,
) : ForwardStrategy {

    override val id = StrategyId.NOTIFY

    override fun isAvailable(profile: PermissionProfile): Boolean = true

    override suspend fun execute(req: ForwardRequest): ForwardResult {
        val number = req.ctx.displayNumber
        val ruleName = req.decision.ruleName ?: "без правила"
        notifier.notifyForwarded(
            text = "Входящий от $number → правило «$ruleName»",
            number = number,
            ruleName = ruleName,
        )

        val channel = req.action.notifyChannel.uppercase()
        val endpoint = req.action.endpoint
        if (channel == "LOCAL" || endpoint.isNullOrBlank()) return ForwardResult.Ok("локальное уведомление")

        val payload = JSONObject().apply {
            put("event", "incoming_call")
            put("number", number)
            put("rule", ruleName)
            put("strategy", id.name)
            put("target", req.action.target ?: JSONObject.NULL)
            put("ts", req.ctx.timestampEpochMs)
            put("sim", req.ctx.phoneAccount?.label ?: "")
        }.toString()

        val external = notifier.sendExternal(channel, endpoint, payload)
        return if (external is ForwardResult.Ok) {
            ForwardResult.Ok("уведомление + $channel")
        } else {
            // Ставим в очередь повторной доставки (FR-6.3) и не роняем сценарий.
            retryQueue?.enqueue(channel, endpoint, payload)
            ForwardResult.Ok("уведомление показано, $channel в очереди повторной доставки: ${describe(external)}")
        }
    }

    private fun describe(r: ForwardResult): String = when (r) {
        is ForwardResult.Failed -> "${r.code}: ${r.message}"
        ForwardResult.NotAvailable -> "канал недоступен"
        else -> "неизвестно"
    }
}
