package fi.callshift.app.domain

/**
 * Чистая логика автоответа SMS после отбоя (без Android-зависимостей, покрыта тестами).
 *
 * SMS отправляется, только если:
 *  - вердикт правила — отбой (DISALLOW_REJECT / DISALLOW_AS_MISSED);
 *  - в правиле задан непустой текст;
 *  - номер звонящего известен и похож на мобильный/обычный (не скрытый, не короткий сервисный);
 *  - этому номеру не отправляли SMS в течение [cooldownMs] (анти-спам и защита от петель).
 */
class SmsAutoReplyPolicy(
    private val cooldownMs: Long = COOLDOWN_MS,
    private val maxLength: Int = MAX_LENGTH,
) {

    sealed interface Result {
        data class Send(val number: String, val text: String) : Result
        data class Skip(val reason: String) : Result
    }

    fun decide(
        verdict: Verdict,
        template: String?,
        e164: String?,
        lastSentAtMs: Long?,
        nowMs: Long,
        cooldownOverrideMs: Long = cooldownMs,
    ): Result {
        if (verdict != Verdict.DISALLOW_REJECT && verdict != Verdict.DISALLOW_AS_MISSED) {
            return Result.Skip("verdict_not_reject")
        }
        val text = template?.trim().orEmpty()
        if (text.isEmpty()) return Result.Skip("no_text")
        val number = e164?.trim().orEmpty()
        if (number.isEmpty()) return Result.Skip("unknown_number")
        val digits = number.count { it.isDigit() }
        if (digits < MIN_DIGITS) return Result.Skip("short_number")
        if (!ReplyChannel.isPhoneAddress(number)) return Result.Skip("invalid_number")
        if (lastSentAtMs != null && nowMs - lastSentAtMs in 0 until cooldownOverrideMs.coerceAtLeast(0)) {
            return Result.Skip("cooldown")
        }
        return Result.Send(number, text.take(maxLength))
    }

    companion object {
        /** Не чаще одного SMS на номер за 30 минут. */
        const val COOLDOWN_MS: Long = 30 * 60 * 1000L

        /** Ограничение длины: до 3 сегментов кириллицы (3 × 67). */
        const val MAX_LENGTH = 201

        /** Короткие номера (сервисные/банковские) не трогаем. */
        const val MIN_DIGITS = 7
    }
}
