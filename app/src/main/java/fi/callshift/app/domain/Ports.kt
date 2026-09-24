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
}
