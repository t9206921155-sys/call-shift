package fi.callshift.app.domain

/**
 * Порт доступа к правилам. Целевая реализация по ТЗ — Room (п. 6.5, 9.1);
 * референс-M1 использует JsonFileRuleStore. Домен и UI зависят только от
 * этого интерфейса, поэтому замена хранилища локальна.
 */
interface RuleStore {
    /** Правила, отсортированные по приоритету (ASC). Всегда свежий снимок. */
    suspend fun rules(): List<Rule>

    suspend fun save(rule: Rule): Rule

    suspend fun delete(id: Long)

    suspend fun setEnabled(id: Long, enabled: Boolean)

    suspend fun replaceAll(rules: List<Rule>)

    suspend fun nextId(): Long
}

/** Порт проверки «номер в контактах» (условие in_contacts, FR-1.3). */
fun interface ContactChecker {
    /**
     * @return true / false, либо null, если контакт-провайдер недоступен
     *         (нет разрешения). null трактуется движком БЕЗОПАСНО —
     *         вызов не блокируется (FR-2.3, E-02).
     */
    suspend fun contains(e164: String?): Boolean?
}

/** Порт настроек приложения (FR-9.x). */
interface SettingsPort {
    val masterEnabled: Boolean
    val defaultPolicy: DefaultPolicy
    val screenOwnAppCalls: Boolean
    val logLevel: String
    val maskNumbersInLogs: Boolean
    /** Номер владельца (для guard'а Loop-owner, ТЗ п. 10.3). */
    val ownerNumbers: Set<String>
    /** Антишторм: максимум дозвонов за период (FR-4.4). */
    val stormMaxDials: Int
    val stormWindowMs: Long
    /** DTMF-передача исходного номера цели по умолчанию (ТЗ п. 10.6, M2). */
    val dtmfTransferEnabled: Boolean

    // ---- Автоответчик / белый список / повторный звонок (значения по умолчанию — выключено) ----

    /** Глобальный режим «Автоответчик»: сброс + SMS, действует до [autoReply].untilMs. */
    val autoReply: AutoReplySettings get() = AutoReplySettings()

    /** Номера (E.164), которые никогда не сбрасываются. */
    val whitelist: Set<String> get() = emptySet()

    /** Повторный звонок с того же номера в течение окна — пропускать (0 = выключено). */
    val repeatCallWindowMs: Long get() = 0L

    /** Когда последний раз сбрасывали звонок с этого номера (epoch ms) или null. */
    fun lastRejectedAt(e164: String): Long? = null
}

/** Настройки режима «Автоответчик». */
data class AutoReplySettings(
    val enabled: Boolean = false,
    val text: String = "",
    /** ALL — все звонки, UNKNOWN — только номера не из контактов. */
    val scope: String = SCOPE_ALL,
    /** Автовыключение (epoch ms) или null — до ручного выключения. */
    val untilMs: Long? = null,
    /** Как в правиле: ANY | HANDLE:<id> | SIM1 | SIM2. */
    val simSelector: String = SimSelector.ANY,
) {
    fun isActiveAt(nowMs: Long): Boolean = enabled && (untilMs == null || nowMs <= untilMs)

    companion object {
        const val SCOPE_ALL = "ALL"
        const val SCOPE_UNKNOWN = "UNKNOWN"
    }
}
