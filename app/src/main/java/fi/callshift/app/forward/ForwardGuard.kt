package fi.callshift.app.forward

import fi.callshift.app.domain.PhoneNumberNormalizer
import fi.callshift.app.domain.SettingsPort

/**
 * Защитные механизмы (ТЗ п. 10.3): антипетля, антишторм, cooldown, контроль стоимости.
 *
 * Всё состояние — в памяти процесса и намеренно «короткое»: после перезапуска
 * процесса счётчики обнуляются. Это безопасно, потому что guard'ы защищают от
 * немедленных петель, а не от долгосрочной статистики.
 */
class ForwardGuard(
    private val settings: SettingsPort,
    private val normalizer: PhoneNumberNormalizer,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    sealed interface Result {
        data object Allowed : Result
        data class Blocked(val code: String, val message: String) : Result
    }

    private data class DialRecord(val target: String, val atMs: Long, val caller: String?)

    private val dials = ArrayDeque<DialRecord>()

    /** Полный набор проверок перед исполнением перенаправления. */
    fun check(caller: String?, target: String?): Result {
        if (target.isNullOrBlank()) return Result.Blocked("no_target", "Цель перенаправления не задана")

        val now = clock()
        pruneOld(now)

        // Loop-self: цель == номеру звонящего → немедленная петля (ТЗ 10.3)
        if (caller != null && sameNumber(caller, target)) {
            return Result.Blocked("guard_loop_self", "Цель совпадает с номером звонящего")
        }

        // Loop-owner: цель == собственный номер SIM
        if (settings.ownerNumbers.any { sameNumber(it, target) }) {
            return Result.Blocked("guard_loop_owner", "Цель совпадает с собственным номером")
        }

        // Loop-recursion: звонит та самая цель, на которую мы уже перенаправляли
        if (caller != null && dials.any { sameNumber(it.target, caller) && now - it.atMs < RECURSION_WINDOW_MS }) {
            return Result.Blocked("guard_recursion", "Входящий от цели перенаправления — пропускаем, чтобы разорвать петлю")
        }

        // Cooldown: тот же номер дважды за короткое время
        if (caller != null && dials.any { sameNumber(it.caller ?: "", caller) && now - it.atMs < COOLDOWN_MS }) {
            return Result.Blocked("guard_cooldown", "Повторный вызов от того же номера за ${COOLDOWN_MS / 1000} с")
        }

        // Storm: слишком много дозвонов за период (FR-4.4)
        val window = settings.stormWindowMs
        val recent = dials.count { now - it.atMs <= window }
        if (recent >= settings.stormMaxDials) {
            return Result.Blocked("guard_storm", "Превышен лимит дозвонов (${settings.stormMaxDials} за ${window / 60000} мин)")
        }

        return Result.Allowed
    }

    /** Фиксируем успешный/начатый дозвон для guard'ов. */
    fun recordDial(caller: String?, target: String?) {
        if (target.isNullOrBlank()) return
        pruneOld(clock())
        dials.addLast(DialRecord(target, clock(), caller))
        while (dials.size > MAX_RECORDS) dials.removeFirst()
    }

    /** Премиум-диапазоны: не блокируем, но помечаем в журнале (E-10, п. 10.3 «Cost»). */
    fun isPremiumRange(target: String?): Boolean {
        if (target == null) return false
        val digits = target.filter { it.isDigit() }
        return PREMIUM_PREFIXES.any { digits.startsWith(it) }
    }

    private fun pruneOld(now: Long) {
        val horizon = maxOf(settings.stormWindowMs, RECURSION_WINDOW_MS, COOLDOWN_MS)
        while (dials.isNotEmpty() && now - dials.first().atMs > horizon) dials.removeFirst()
    }

    private fun sameNumber(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val na = normalizer.normalize(a).e164 ?: a.filter { it.isDigit() }
        val nb = normalizer.normalize(b).e164 ?: b.filter { it.isDigit() }
        if (na == nb) return true
        // сравнение по последним 9 цифрам — ловит случаи «национальный vs международный»
        val tailA = na.filter { it.isDigit() }.takeLast(9)
        val tailB = nb.filter { it.isDigit() }.takeLast(9)
        return tailA.length >= 7 && tailA == tailB
    }

    companion object {
        const val COOLDOWN_MS = 30_000L
        const val RECURSION_WINDOW_MS = 120_000L
        private const val MAX_RECORDS = 200

        /** Типовые премиум-направления (проверяется по началу E.164). */
        private val PREMIUM_PREFIXES = listOf("358600", "358700", "358100", "900", "1900")
    }
}
