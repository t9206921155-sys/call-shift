package fi.callshift.app.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

/**
 * Движок правил (ТЗ п. 6.3, 10.2).
 *
 * Контракт:
 *  1. Бюджет [budgetMs] — по умолчанию 3000 мс. Системный таймаут screening
 *     ≈ 5 с, при превышении Android ПРОПУСКАЕТ вызов, поэтому выходим раньше.
 *  2. Fail-open: любая ошибка или таймаут → Decision.pass(...). Вызов никогда
 *     не теряется из-за сбоя приложения (NFR-2, FR-2.8).
 *  3. Экстренные номера и выключенный мастер-переключатель — абсолютный PASS.
 *
 * Класс не знает об Android: все внешние данные приходят через порты
 * ([RuleStore], [ContactChecker], [SettingsPort]) и [CallContext].
 */
class RuleEngine(
    private val ruleStore: RuleStore,
    private val contacts: ContactChecker,
    private val settings: SettingsPort,
    private val profileProvider: () -> PermissionProfile,
    private val simIndexProvider: (PhoneAccountRef?) -> Int?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val budgetMs: Long = DEFAULT_BUDGET_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun evaluate(ctx: CallContext): Decision {
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000L

        // --- Абсолютные правила (FR-2.6, FR-9.1) ---
        if (ctx.isEmergency) return Decision.pass("emergency_number", elapsed())
        if (!settings.masterEnabled) return Decision.pass("master_switch_off", elapsed())
        if (ctx.direction == Direction.INCOMING && ctx.isSelfManaged && !settings.screenOwnAppCalls) {
            return Decision.pass("self_managed_call_skipped", elapsed())
        }
        if (profileProvider() == PermissionProfile.NONE && ctx.direction == Direction.INCOMING) {
            // Роли нет — screening нас и так не вызовет, но защищаемся от
            // ручных/тестовых запусков: честно пишем причину.
            return Decision.pass("no_screening_role", elapsed())
        }

        return try {
            // Таймаут и диспетчер — в ОДНОМ контексте корутины. Если вложить
            // withContext внутрь withTimeoutOrNull, то на тестовом диспетчере
            // (виртуальное время) работа на реальном пуле «не успевает» за
            // виртуальные 3 с и движок ложно уходит в fail-open. В продакшене
            // комбинация контекстов даёт то же поведение: budgetMs реального
            // времени на Dispatchers.Default.
            val decision = withContext(dispatcher) {
                withTimeoutOrNull(budgetMs) { evaluateUnsafe(ctx) }
            } ?: Decision.pass("engine_timeout_budget_${budgetMs}ms", elapsed())
            decision.copy(engineMs = elapsed())
        } catch (t: Throwable) {
            Decision.pass("engine_error:${t.javaClass.simpleName}", elapsed())
        }
    }

    private suspend fun evaluateUnsafe(ctx: CallContext): Decision {
        val now = clock()
        val rules = ruleStore.rules()
        val simIndex = simIndexProvider(ctx.phoneAccount)

        for (rule in rules) {
            if (!rule.isActiveAt(now)) continue
            if (!ScheduleMatcher.matches(rule.schedule, now, zone)) continue
            if (!SimSelector.matches(rule.simSelector, ctx.phoneAccount, simIndex)) continue
            if (!matchesConditions(rule.conditions, ctx)) continue
            return decisionFrom(rule, ctx, "rule#${rule.id}:${rule.name}")
        }
        return decisionFromPolicy(settings.defaultPolicy, ctx, "default_policy")
    }

    /** Матчинг условий: anyOf( allOf(...) ) — FR-1.3, п. 9.4. */
    suspend fun matchesConditions(group: ConditionGroup, ctx: CallContext): Boolean {
        if (group.isEmpty) return true
        return group.anyOf.any { allOf -> allOf.all { matchesCondition(it, ctx) } }
    }

    private suspend fun matchesCondition(c: ConditionSpec, ctx: CallContext): Boolean = when (c.type) {
        TYPE_NUMBER_MATCH -> NumberMatcher.matches(c.pattern, ctx.e164) ||
            NumberMatcher.matches(c.pattern, ctx.national) ||
            NumberMatcher.matches(c.pattern, ctx.rawHandle)

        TYPE_NUMBER_IN_LIST -> NumberMatcher.matchesAny(c.numbers, ctx.e164) ||
            NumberMatcher.matchesAny(c.numbers, ctx.national)

        TYPE_ANONYMOUS -> ctx.isAnonymous == (c.value ?: true)

        TYPE_IN_CONTACTS -> {
            val expected = c.value ?: true
            when (val found = contacts.contains(ctx.e164)) {
                null -> false // провайдер недоступен → условие НЕ выполняется, вызов не блокируем (E-02)
                else -> found == expected
            }
        }

        TYPE_SIM -> SimSelector.matches(c.text ?: SimSelector.ANY, ctx.phoneAccount, null)
        TYPE_NETWORK -> (ctx.signals[Signal.NETWORK] ?: "UNKNOWN") == (c.text ?: "")
        TYPE_LINE_STATE -> (ctx.signals[Signal.LINE_STATE] ?: "IDLE") == (c.text ?: "IDLE")
        TYPE_BATTERY_BELOW -> {
            val battery = ctx.signals[Signal.BATTERY]?.toIntOrNull() ?: 100
            battery < (c.int ?: 20)
        }
        TYPE_DND -> ((ctx.signals[Signal.DND] ?: "false") == "true") == (c.value ?: true)

        else -> false // неизвестное условие → не совпало (защита от битого JSON)
    }

    private fun decisionFrom(rule: Rule, ctx: CallContext, reason: String): Decision {
        val action = rule.action
        val verdict = action.verdict.toDomain()
        val strategy = action.strategy.toDomain()
        return Decision(
            verdict = verdict,
            strategy = strategy,
            target = action.target,
            ruleId = rule.id,
            ruleName = rule.name,
            reason = reason,
            phoneAccount = ctx.phoneAccount,
            matchedAction = action,
        )
    }

    private fun decisionFromPolicy(policy: DefaultPolicy, ctx: CallContext, reason: String): Decision =
        Decision(
            verdict = policy.verdict.toDomain(),
            strategy = policy.strategy.toDomain(),
            target = policy.target,
            ruleId = null,
            ruleName = null,
            reason = reason,
            phoneAccount = ctx.phoneAccount,
            matchedAction = Action(
                verdict = policy.verdict,
                strategy = policy.strategy,
                target = policy.target,
            ),
        )

    companion object {
        /** ТЗ п. 10.2 / FR-2.1: бюджет 3000 мс при системном таймауте ~5000 мс. */
        const val DEFAULT_BUDGET_MS = 3000L

        const val TYPE_NUMBER_MATCH = "number_match"
        const val TYPE_NUMBER_IN_LIST = "number_in_list"
        const val TYPE_ANONYMOUS = "anonymous"
        const val TYPE_IN_CONTACTS = "in_contacts"
        const val TYPE_SIM = "sim"
        const val TYPE_NETWORK = "network"
        const val TYPE_LINE_STATE = "line_state"
        const val TYPE_BATTERY_BELOW = "battery_below"
        const val TYPE_DND = "dnd"
    }
}
