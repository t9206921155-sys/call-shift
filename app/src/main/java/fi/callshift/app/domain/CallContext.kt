package fi.callshift.app.domain

/**
 * Нормализованный контекст вызова, который уходит в RuleEngine.
 * Формируется в screening-слое из android.telecom.Call.Details (ТЗ п. 6.3).
 *
 * Домен намеренно не знает о типах Android — это позволяет тестировать
 * RuleEngine обычными unit-тестами (NFR-8: покрытие domain ≥ 80 %).
 */
data class CallContext(
    /** Сырой handle из Telecom, может отсутствовать (скрытый/ограниченный CLI). */
    val rawHandle: String? = null,
    /** Номер в E.164 после нормализации (ТЗ п. 10.1), null для анонимных. */
    val e164: String? = null,
    /** Номер в национальном формате — для матчинга масок вида «8*». */
    val national: String? = null,
    val direction: Direction = Direction.INCOMING,
    val phoneAccount: PhoneAccountRef? = null,
    /** Экстренный номер: всегда PASS, абсолютный приоритет (FR-2.6, E-04). */
    val isEmergency: Boolean = false,
    /** Вызов от self-managed соединения (VoIP других приложений), FR-2.7 / E-12. */
    val isSelfManaged: Boolean = false,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    /** Дополнительный контекст для условий: NETWORK / BATTERY / LINE_STATE и т. д. */
    val signals: Map<Signal, String> = emptyMap(),
) {
    val isAnonymous: Boolean get() = e164 == null && rawHandle.isNullOrBlank()

    /** Номер, который показываем в UI/журнале. */
    val displayNumber: String get() = e164 ?: national ?: rawHandle ?: "unknown"
}

/** Сигналы окружения, влияющие на условия правил (ТЗ п. 9.4). */
enum class Signal {
    /** MOBILE / WIFI / ROAMING / NONE */
    NETWORK,

    /** 0..100 */
    BATTERY,

    /** IDLE / RINGING / OFFHOOK */
    LINE_STATE,

    /** true/false — активен ли «Не беспокоить» */
    DND,
}

/**
 * Решение движка правил. Обязательный принцип: fail-open (ТЗ NFR-2, FR-2.8) —
 * при любой ошибке/таймауте возвращаем PASS, но фиксируем причину в журнале.
 */
data class Decision(
    val verdict: Verdict,
    val strategy: StrategyId = StrategyId.PASS,
    /** Цель: номер E.164, SIP URI или MMI-сервис. */
    val target: String? = null,
    val ruleId: Long? = null,
    val ruleName: String? = null,
    /** Человекочитаемая причина — обязательна для журнала и отладки (FR-7.1, US-8). */
    val reason: String,
    /** Для какой SIM применялась сетевая переадресация (S1). */
    val phoneAccount: PhoneAccountRef? = null,
    /** Длительность поиска решения, мс — пишется в журнал (LOG-2). */
    val engineMs: Long = 0,
    /**
     * Параметры действия совпавшего правила (или политики по умолчанию).
     * Нужны диспетчеру для исполнения стратегии после ответа системе (ТЗ п. 6.6).
     */
    val matchedAction: Action? = null,
    /** Почему правила НЕ сработали: «Имя правила — причина» (для журнала). */
    val skipped: List<String> = emptyList(),
) {
    companion object {
        fun pass(reason: String, ms: Long = 0) =
            Decision(Verdict.PASS, StrategyId.PASS, null, null, null, reason, engineMs = ms)
    }

    val shouldDisallow: Boolean
        get() = verdict == Verdict.DISALLOW_REJECT || verdict == Verdict.DISALLOW_AS_MISSED
}
