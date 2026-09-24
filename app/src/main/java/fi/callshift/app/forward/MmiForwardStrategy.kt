package fi.callshift.app.forward

import fi.callshift.app.domain.ForwardResult
import fi.callshift.app.domain.PermissionProfile
import fi.callshift.app.domain.PhoneNumberNormalizer
import fi.callshift.app.domain.StrategyId

/**
 * S1 — сетевая переадресация оператора через MMI (ТЗ п. 4.3, 10.4, Приложение A).
 *
 * Работает даже когда телефон выключен: переадресацию выполняет сеть.
 * Ограничение: не зависит от наших правил в момент звонка — это «установка
 * состояния сети», поэтому стратегия применяется при сохранении/включении
 * правила, а не на каждый входящий.
 */
class MmiForwardStrategy(
    private val telecom: TelecomPort,
    private val normalizer: PhoneNumberNormalizer,
) : ForwardStrategy {

    override val id = StrategyId.MMI_FORWARD

    /** MMI отправляется через TelecomManager — роль dialer не обязательна. */
    override fun isAvailable(profile: PermissionProfile) = true

    override suspend fun execute(req: ForwardRequest): ForwardResult {
        val mmi = build(
            service = req.action.mmiService,
            target = req.action.target,
            noReplySeconds = req.action.noReplySeconds,
        ) ?: return ForwardResult.Failed("bad_mmi", "Не удалось собрать MMI-код: проверьте номер цели")
        return telecom.placeMmi(req.ctx.phoneAccount?.id, mmi)
    }

    /**
     * Сборка MMI-кода. Формат — 3GPP TS 22.082 / 27.007:
     *   установка   *<код>*<номер>#      (для 61: *61*<номер>**<сек>#)
     *   снятие      ##<код>#
     *   запрос      *#<код>#
     *   снять всё   ##002#
     */
    fun build(service: String, target: String?, noReplySeconds: Int = 20): String? {
        val code = service.trim()
        if (code == ERASE_ALL) return "##002#"
        if (code !in SUPPORTED_CODES) return null
        val e164 = target?.let { normalizer.normalize(it).e164 }
        if (e164.isNullOrBlank()) return null
        // Номер в MMI должен идти без '+': операторы принимают '+' по-разному,
        // поэтому используем E.164 без символа '+' (ТЗ FR-3.3).
        val number = e164.removePrefix("+")
        return if (code == CODE_NO_REPLY) {
            val sec = noReplySeconds.coerceIn(5, 30)
            "*$code*$number**$sec#"
        } else {
            "*$code*$number#"
        }
    }

    /** Код снятия переадресации (используется в «Режиме паники», FR-9.5). */
    fun eraseCode(service: String): String? = when (service.trim()) {
        ERASE_ALL -> "##002#"
        in SUPPORTED_CODES -> "##${service.trim()}#"
        else -> null
    }

    fun interrogateCode(service: String): String? =
        if (service.trim() in SUPPORTED_CODES) "*#${service.trim()}#" else null

    companion object {
        const val CODE_UNCONDITIONAL = "21"
        const val CODE_NO_REPLY = "61"
        const val CODE_NOT_REACHABLE = "62"
        const val CODE_BUSY = "67"
        const val CODE_ALL_CONDITIONAL = "002"
        const val ERASE_ALL = "ERASE_ALL"

        val SUPPORTED_CODES = setOf(
            CODE_UNCONDITIONAL, CODE_NO_REPLY, CODE_NOT_REACHABLE, CODE_BUSY, CODE_ALL_CONDITIONAL, "004",
        )

        /** Человекочитаемые подписи для UI (ТЗ FR-3.7). */
        val LABELS = mapOf(
            CODE_UNCONDITIONAL to "Безусловная (все звонки)",
            CODE_BUSY to "Если занято",
            CODE_NO_REPLY to "Если не отвечаю",
            CODE_NOT_REACHABLE to "Если недоступен",
            CODE_ALL_CONDITIONAL to "Все условные",
            ERASE_ALL to "Снять всё",
        )
    }
}
