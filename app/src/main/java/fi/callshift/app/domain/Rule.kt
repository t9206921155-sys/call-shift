package fi.callshift.app.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Правило переадресации (ТЗ п. 9.1).
 *
 * Хранится как JSON в files/rules.json (референс-M1). По ТЗ целевое хранилище —
 * Room с миграциями и exportSchema=true; интерфейс [RuleStore] уже изолирует
 * способ хранения, поэтому переход на Room в M2/M3 не трогает домен и UI.
 */
@Serializable
data class Rule(
    val id: Long = 0,
    val name: String = "Новое правило",
    val enabled: Boolean = true,
    /** 0..999, чем меньше — тем выше приоритет (FR-1.2, FR-1.5). */
    val priority: Int = 100,
    val conditions: ConditionGroup = ConditionGroup(),
    val action: Action = Action(),
    /** ANY | SIM1 | SIM2 | HANDLE:<phoneAccountId> — см. SimSelector. */
    val simSelector: String = SimSelector.ANY,
    val schedule: ScheduleSpec? = null,
    /** Epoch ms; null = без ограничения (FR-1.2). */
    val validFrom: Long? = null,
    val validTo: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    /** Активно ли правило прямо сейчас (без учёта условий). */
    fun isActiveAt(nowMs: Long): Boolean {
        if (!enabled) return false
        if (validFrom != null && nowMs < validFrom) return false
        if (validTo != null && nowMs > validTo) return false
        return true
    }
}

object SimSelector {
    const val ANY = "ANY"
    const val SIM1 = "SIM1"
    const val SIM2 = "SIM2"
    const val HANDLE_PREFIX = "HANDLE:"

    fun matches(selector: String, ctxAccount: PhoneAccountRef?, index: Int?): Boolean =
        when {
            selector == ANY -> true
            // Не смогли определить SIM → правило для конкретной SIM НЕ применяем
            // (звонок проходит как обычно — это и есть fail-open для вызова).
            ctxAccount == null -> false
            selector == HANDLE_PREFIX + ctxAccount.id -> true
            selector == SIM1 -> index == 0
            selector == SIM2 -> index == 1
            else -> false
        }
}

/** Действие правила: вердикт screening + стратегия перенаправления. */
@Serializable
data class Action(
    val verdict: VerdictSpec = VerdictSpec.PASS,
    val strategy: StrategySpec = StrategySpec.NONE,
    /** Номер цели (E.164) / SIP URI / null для BLOCK и PASS. */
    val target: String? = null,
    /** Для MMI: код сервиса 21/61/62/67/002/04 (ТЗ п. 19). */
    val mmiService: String = "21",
    /** Для кода 61: через сколько секунд отсутствия ответа включать переадресацию. */
    val noReplySeconds: Int = 20,
    /** Для NOTIFY: канал — LOCAL / TELEGRAM / WEBHOOK / SMS. */
    val notifyChannel: String = "LOCAL",
    /** Для CALLBACK_DIAL: задержка перед дозвном, мс (ТЗ п. 10.5). */
    val dialDelayMs: Long = 800,
    /** Текст/команда для внешнего канала. */
    val endpoint: String? = null,
    /**
     * Для CALLBACK_DIAL: передать исходный номер звонящего цели тонами DTMF
     * после соединения (ТЗ п. 10.6, работает только в профиле B — M2).
     */
    val dtmfTransferOriginal: Boolean = false,
    /** Префикс DTMF-последовательности, если АТС его ожидает (например "*9"). */
    val dtmfPrefix: String = "",
    /**
     * Автоответ SMS звонящему после отбоя (вердикт DISALLOW_*). null/пусто — не отправлять.
     * Анти-спам: не чаще одного SMS на номер за [SmsAutoReplyPolicy.COOLDOWN_MS].
     */
    val autoReplySms: String? = null,
    val replyChannel: String = ReplyChannel.SMS,
    val replyChannels: List<String>? = null,
    val replyCooldownMinutes: Int = 30,
)

/**
 * Сериализуемый аналог [Verdict]. Держим отдельно, чтобы значения в JSON
 * оставались стабильными даже при рефакторинге доменных enum'ов.
 */
@Serializable
enum class VerdictSpec {
    @SerialName("PASS") PASS,
    @SerialName("DISALLOW_REJECT") DISALLOW_REJECT,
    @SerialName("DISALLOW_AS_MISSED") DISALLOW_AS_MISSED,
    @SerialName("SILENCE") SILENCE,
    ;

    fun toDomain(): Verdict = when (this) {
        PASS -> Verdict.PASS
        DISALLOW_REJECT -> Verdict.DISALLOW_REJECT
        DISALLOW_AS_MISSED -> Verdict.DISALLOW_AS_MISSED
        SILENCE -> Verdict.SILENCE
    }
}

@Serializable
enum class StrategySpec {
    @SerialName("NONE") NONE,
    @SerialName("MMI_FORWARD") MMI_FORWARD,
    @SerialName("CALLBACK_DIAL") CALLBACK_DIAL,
    @SerialName("SIP_BRIDGE") SIP_BRIDGE,
    @SerialName("NOTIFY") NOTIFY,
    ;

    fun toDomain(): StrategyId = when (this) {
        NONE -> StrategyId.PASS
        MMI_FORWARD -> StrategyId.MMI_FORWARD
        CALLBACK_DIAL -> StrategyId.CALLBACK_DIAL
        SIP_BRIDGE -> StrategyId.SIP_BRIDGE
        NOTIFY -> StrategyId.NOTIFY
    }
}

/** Группа условий: anyOf( allOf(...) , allOf(...) ) — ТЗ FR-1.3, п. 9.4. */
@Serializable
data class ConditionGroup(
    val anyOf: List<List<ConditionSpec>> = listOf(emptyList()),
) {
    /** Пустое правило = «совпадает всегда». */
    val isEmpty: Boolean get() = anyOf.all { it.isEmpty() }
}

/**
 * Универсальное условие. Осознанно «плоская» структура вместо полиморфной
 * сериализации: проще для R8, для ручной правки JSON и для миграций.
 */
@Serializable
data class ConditionSpec(
    /** number_match | number_in_list | in_contacts | anonymous | sim | network | battery_below | line_state | dnd */
    val type: String,
    val value: Boolean? = null,
    val pattern: String? = null,
    val numbers: List<String> = emptyList(),
    /** NETWORK: MOBILE/WIFI/ROAMING/NONE; LINE_STATE: IDLE/RINGING/OFFHOOK */
    val text: String? = null,
    val int: Int? = null,
)

/** Расписание: дни недели + окно времени (ТЗ п. 9.4). */
@Serializable
data class ScheduleSpec(
    /** 1=Пн … 7=Вс (ISO-8601). Пустой список = каждый день. */
    val days: List<Int> = emptyList(),
    /** "HH:mm" локального времени; null = без ограничения. */
    val from: String? = null,
    val to: String? = null,
    /** Поддерживает переход через полночь (22:00–07:00) — US-1. */
    val crossesMidnight: Boolean = false,
)

/** Политика по умолчанию, если ни одно правило не совпало (FR-1.5, FR-9.2). */
@Serializable
data class DefaultPolicy(
    val verdict: VerdictSpec = VerdictSpec.PASS,
    val strategy: StrategySpec = StrategySpec.NONE,
    val target: String? = null,
)
