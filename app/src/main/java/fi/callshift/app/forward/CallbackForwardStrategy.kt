package fi.callshift.app.forward

import fi.callshift.app.domain.ForwardResult
import fi.callshift.app.domain.PermissionProfile
import fi.callshift.app.domain.PhoneNumberNormalizer
import fi.callshift.app.domain.StrategyId
import kotlinx.coroutines.delay

/**
 * Порт передачи исходного номера цели тонами DTMF (ТЗ п. 10.6, FR-4.6).
 * Реализация живёт в telecom-слое (InCallController), потому что playDtmfTone
 * доступен только в профиле B — у приложения-владельца InCallService.
 */
fun interface DtmfTransferPort {
    fun enqueue(targetNumber: String, originalNumber: String, ruleName: String?)
}

/**
 * S2 — основная стратегия приложения (ТЗ п. 4.4).
 *
 * Screening уже отклонил вызов; здесь мы совершаем исходящий дозвон на цель.
 * Важно и это явно показано в UI/документации (ТЗ п. 3.3, R-1):
 * это НЕ сетевая переадресация — целевой абонент увидит номер владельца,
 * а не номер исходного звонящего (передать чужой CLI приложение не может).
 * Компенсация — DTMF-передача номера (п. 10.6) и уведомление цели.
 */
class CallbackForwardStrategy(
    private val telecom: TelecomPort,
    private val guard: ForwardGuard,
    private val normalizer: PhoneNumberNormalizer,
    private val dtmf: DtmfTransferPort? = null,
) : ForwardStrategy {

    override val id = StrategyId.CALLBACK_DIAL

    /** Нужен CALL_PHONE (профиль A после adb-гранта) либо роль dialer (профиль B). */
    override fun isAvailable(profile: PermissionProfile): Boolean = profile.canScreen

    override suspend fun execute(req: ForwardRequest): ForwardResult {
        val target = normalizer.normalize(req.action.target).e164
            ?: return ForwardResult.Failed("bad_target", "Некорректный номер цели")

        when (val g = guard.check(req.ctx.e164, target)) {
            is ForwardGuard.Result.Blocked -> return ForwardResult.Failed(g.code, g.message)
            ForwardGuard.Result.Allowed -> Unit
        }

        val premium = guard.isPremiumRange(target)

        // DTMF-передача исходного номера имеет смысл только если цель — IVR/АТС
        // и приложение держит InCallService (профиль B). Порт может быть null.
        if (req.action.dtmfTransferOriginal && req.ctx.e164 != null) {
            dtmf?.enqueue(target, req.ctx.e164, req.decision.ruleName)
        }

        val delayMs = req.action.dialDelayMs.coerceIn(0, 5_000)
        if (delayMs > 0) delay(delayMs)

        var last: ForwardResult = ForwardResult.Failed("not_attempted", "Дозвон не выполнен")
        repeat(MAX_ATTEMPTS) { attempt ->
            last = telecom.dial(target, req.ctx.phoneAccount?.id)
            if (last is ForwardResult.Ok) {
                guard.recordDial(req.ctx.e164, target)
                return ForwardResult.Ok(
                    buildString {
                        append("дозвон на $target (попытка ${attempt + 1}, задержка ${delayMs}мс)")
                        if (req.action.dtmfTransferOriginal) append(", DTMF-передача номера ${req.ctx.e164} заказана")
                        if (premium) append(" ⚠ цель в премиум-диапазоне")
                    },
                )
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(RETRY_DELAY_MS)
        }
        return last
    }

    companion object {
        /** ТЗ п. 10.5: max_retries = 1 → всего 2 попытки. */
        private const val MAX_ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 1_500L
    }
}
