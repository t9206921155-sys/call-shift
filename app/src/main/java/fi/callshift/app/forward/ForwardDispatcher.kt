package fi.callshift.app.forward

import fi.callshift.app.domain.CallContext
import fi.callshift.app.domain.Decision
import fi.callshift.app.domain.ForwardResult
import fi.callshift.app.domain.PermissionProfile
import fi.callshift.app.domain.PhoneNumberNormalizer
import fi.callshift.app.domain.StrategyId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Запись события в журнал (ТЗ п. 4.7, FR-7.1). */
fun interface EventRecorder {
    suspend fun record(event: CallEvent)
}

/** Событие журнала — соответствует таблице `events` из ТЗ п. 9.2. */
data class CallEvent(
    val ts: Long,
    val direction: String,
    val numberE164: String?,
    val numberMasked: String,
    val sim: String,
    val ruleId: Long?,
    val ruleName: String?,
    val strategy: String,
    val target: String?,
    val result: String,
    val errorCode: String?,
    val errorMessage: String?,
    val reason: String,
    val screeningMs: Long,
    val forwardMs: Long,
    val totalMs: Long,
)

/**
 * Диспетчер исполнения стратегий (ТЗ п. 6.4).
 *
 * Ключевые решения:
 *  1. Очередь [Channel] с ограничением — два одновременных входящих не должны
 *     устраивать гонку дозвона.
 *  2. Работа вне screening-колбэка: сначала отвечаем системе, потом исполняем.
 *  3. Fail-open: любой сбой фиксируется в журнале и НЕ влияет на уже отданный
 *     screening-ответ.
 */
class ForwardDispatcher(
    private val strategies: Map<StrategyId, ForwardStrategy>,
    private val profileProvider: () -> PermissionProfile,
    private val recorder: EventRecorder,
    private val normalizer: PhoneNumberNormalizer,
    private val scope: CoroutineScope,
    private val capacity: Int = 16,
) {

    private val queue = Channel<ForwardRequest>(capacity)

    init {
        // Единственный потребитель — сериализация исполнения (ТЗ п. 6.4).
        scope.launch(Dispatchers.Default) {
            for (req in queue) {
                runCatching { process(req) }.onFailure { t ->
                    runCatching {
                        recorder.record(
                            eventFor(req, ForwardResult.Failed("dispatcher_error", t.toString()), 0),
                        )
                    }
                }
            }
        }
    }

    /** Неблокирующая постановка задачи из screening-сервиса. */
    fun submit(ctx: CallContext, decision: Decision, action: fi.callshift.app.domain.Action) {
        if (decision.strategy == StrategyId.PASS) return
        queue.trySend(ForwardRequest(ctx, decision, action))
    }

    private suspend fun process(req: ForwardRequest) {
        val profile = profileProvider()
        val strategy = strategies[req.decision.strategy]
        if (strategy == null) {
            recorder.record(eventFor(req, ForwardResult.Failed("no_strategy", "Стратегия не зарегистрирована"), 0))
            return
        }
        if (!strategy.isAvailable(profile)) {
            val msg = when (strategy.id) {
                StrategyId.SIP_BRIDGE -> "SIP-модуль не подключён (M3) — см. ТЗ п. 4.5"
                else -> "Недостаточно полномочий: профиль ${profile.name}. Выполните команды из раздела «Диагностика»"
            }
            recorder.record(eventFor(req, ForwardResult.Failed("profile_${profile.name.lowercase()}", msg), 0))
            return
        }
        val started = System.nanoTime()
        val result = runCatching { strategy.execute(req) }
            .getOrElse { ForwardResult.Failed("strategy_error", it.toString()) }
        val ms = (System.nanoTime() - started) / 1_000_000L
        recorder.record(eventFor(req, result, ms))
    }

    private fun eventFor(req: ForwardRequest, result: ForwardResult, forwardMs: Long) = CallEvent(
        ts = System.currentTimeMillis(),
        direction = req.ctx.direction.name,
        numberE164 = req.ctx.e164,
        numberMasked = normalizer.mask(req.ctx.e164 ?: req.ctx.rawHandle),
        sim = req.ctx.phoneAccount?.label ?: req.ctx.phoneAccount?.id ?: "—",
        ruleId = req.decision.ruleId,
        ruleName = req.decision.ruleName,
        strategy = req.decision.strategy.name,
        target = req.decision.target,
        result = when (result) {
            is ForwardResult.Ok -> "OK"
            is ForwardResult.Failed -> "FAILED"
            ForwardResult.NotAvailable -> "NOT_AVAILABLE"
            ForwardResult.SkippedByGuard -> "BLOCKED"
        },
        errorCode = (result as? ForwardResult.Failed)?.code,
        errorMessage = when (result) {
            is ForwardResult.Failed -> result.message
            is ForwardResult.Ok -> result.detail
            ForwardResult.NotAvailable -> "стратегия недоступна в этой сборке/профиле"
            ForwardResult.SkippedByGuard -> "сработал защитный механизм"
        },
        reason = req.decision.reason,
        screeningMs = req.decision.engineMs,
        forwardMs = forwardMs,
        totalMs = req.decision.engineMs + forwardMs,
    )

    /** Для тестов и «Режима паники»: очистить очередь. */
    suspend fun drain() = withContext(Dispatchers.Default) {
        while (true) {
            val r = queue.tryReceive()
            if (r.isFailure) break
        }
    }
}
