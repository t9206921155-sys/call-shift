package fi.callshift.app.data

import android.content.Context
import android.content.SharedPreferences
import fi.callshift.app.domain.AutoReplySettings
import fi.callshift.app.domain.DefaultPolicy
import fi.callshift.app.domain.SettingsPort
import fi.callshift.app.domain.StrategySpec
import fi.callshift.app.domain.VerdictSpec

/**
 * Настройки приложения на SharedPreferences (референс-M1/M2).
 * По ТЗ п. 6.5 целевое хранилище — DataStore Preferences; интерфейс [SettingsPort]
 * делает замену прозрачной для остального кода.
 */
class SettingsStore(context: Context) : SettingsPort {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("callshift_settings", Context.MODE_PRIVATE)

    override val masterEnabled: Boolean
        get() = prefs.getBoolean(KEY_MASTER, true)

    fun setMasterEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_MASTER, value).apply()

    override val defaultPolicy: DefaultPolicy
        get() = DefaultPolicy(
            verdict = runCatching { VerdictSpec.valueOf(prefs.getString(KEY_DEFAULT_VERDICT, VerdictSpec.PASS.name)!!) }
                .getOrDefault(VerdictSpec.PASS),
            strategy = runCatching { StrategySpec.valueOf(prefs.getString(KEY_DEFAULT_STRATEGY, StrategySpec.NONE.name)!!) }
                .getOrDefault(StrategySpec.NONE),
            target = prefs.getString(KEY_DEFAULT_TARGET, null),
        )

    fun setDefaultPolicy(policy: DefaultPolicy) = prefs.edit()
        .putString(KEY_DEFAULT_VERDICT, policy.verdict.name)
        .putString(KEY_DEFAULT_STRATEGY, policy.strategy.name)
        .putString(KEY_DEFAULT_TARGET, policy.target)
        .apply()

    override val screenOwnAppCalls: Boolean
        get() = prefs.getBoolean(KEY_SCREEN_SELF_MANAGED, false)

    fun setScreenOwnAppCalls(value: Boolean) = prefs.edit().putBoolean(KEY_SCREEN_SELF_MANAGED, value).apply()

    override val logLevel: String
        get() = prefs.getString(KEY_LOG_LEVEL, "WARN") ?: "WARN"

    fun setLogLevel(value: String) = prefs.edit().putString(KEY_LOG_LEVEL, value).apply()

    override val maskNumbersInLogs: Boolean
        get() = prefs.getBoolean(KEY_MASK_NUMBERS, true)

    fun setMaskNumbers(value: Boolean) = prefs.edit().putBoolean(KEY_MASK_NUMBERS, value).apply()

    /**
     * Маскировать номера в UI (экран звонка, журнал). Требование приватности
     * FR-7.4 распространяем и на интерфейс: номер показывается как +35840***567.
     */
    val maskNumbersInUi: Boolean
        get() = prefs.getBoolean(KEY_MASK_UI, false)

    fun setMaskNumbersInUi(value: Boolean) = prefs.edit().putBoolean(KEY_MASK_UI, value).apply()

    override val ownerNumbers: Set<String>
        get() = prefs.getStringSet(KEY_OWNER_NUMBERS, emptySet()) ?: emptySet()

    fun setOwnerNumbers(numbers: Set<String>) = prefs.edit().putStringSet(KEY_OWNER_NUMBERS, numbers).apply()

    override val stormMaxDials: Int
        get() = prefs.getInt(KEY_STORM_MAX, 3)

    override val stormWindowMs: Long
        get() = prefs.getLong(KEY_STORM_WINDOW, 10 * 60 * 1000L)

    fun setStorm(maxDials: Int, windowMs: Long) = prefs.edit()
        .putInt(KEY_STORM_MAX, maxDials)
        .putLong(KEY_STORM_WINDOW, windowMs)
        .apply()

    /**
     * Глобальный флаг DTMF-передачи исходного номера цели (ТЗ п. 10.6, M2).
     * Работает только в профиле B: playDtmfTone доступен владельцу InCallService.
     */
    override val dtmfTransferEnabled: Boolean
        get() = prefs.getBoolean(KEY_DTMF_TRANSFER, false)

    fun setDtmfTransferEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_DTMF_TRANSFER, value).apply()

    // ---------------- Автоответчик ----------------

    override val autoReply: AutoReplySettings
        get() = AutoReplySettings(
            replyChannel = prefs.getString("ar_reply_channel", "SMS") ?: "SMS",
            replyChannels = if (prefs.contains("ar_reply_channels")) prefs.getStringSet("ar_reply_channels", emptySet())!!.toList() else null,
            replyCooldownMinutes = prefs.getInt("ar_reply_cooldown_minutes", 30).coerceIn(0, 1440),
            enabled = prefs.getBoolean(KEY_AR_ENABLED, false),
            text = prefs.getString(KEY_AR_TEXT, null) ?: DEFAULT_AUTO_REPLY_TEXT,
            scope = prefs.getString(KEY_AR_SCOPE, AutoReplySettings.SCOPE_ALL) ?: AutoReplySettings.SCOPE_ALL,
            untilMs = prefs.getLong(KEY_AR_UNTIL, 0L).takeIf { it > 0 },
            simSelector = prefs.getString(KEY_AR_SIM, "ANY") ?: "ANY",
        )

    fun setAutoReply(value: AutoReplySettings) = prefs.edit()
        .putString("ar_reply_channel", value.replyChannel)
        .putStringSet("ar_reply_channels", value.replyChannels?.toSet())
        .putInt("ar_reply_cooldown_minutes", value.replyCooldownMinutes.coerceIn(0, 1440))
        .putBoolean(KEY_AR_ENABLED, value.enabled)
        .putString(KEY_AR_TEXT, value.text)
        .putString(KEY_AR_SCOPE, value.scope)
        .putLong(KEY_AR_UNTIL, value.untilMs ?: 0L)
        .putString(KEY_AR_SIM, value.simSelector)
        .apply()

    fun setAutoReplyEnabled(enabled: Boolean) = setAutoReply(autoReply.copy(enabled = enabled))

    // ---------------- Тема ----------------

    val themeMode: String
        get() = prefs.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK

    fun setThemeMode(mode: String) = prefs.edit().putString(KEY_THEME, mode).apply()

    // ---------------- Белый список ----------------

    override val whitelist: Set<String>
        get() = prefs.getStringSet(KEY_WHITELIST, emptySet())?.toSet() ?: emptySet()

    fun setWhitelist(numbers: Set<String>) = prefs.edit().putStringSet(KEY_WHITELIST, numbers.toSet()).apply()

    // ---------------- Повторный звонок ----------------

    val repeatCallEnabled: Boolean
        get() = prefs.getBoolean(KEY_REPEAT_ENABLED, false)

    val repeatCallMinutes: Int
        get() = prefs.getInt(KEY_REPEAT_MINUTES, 3)

    fun setRepeatCall(enabled: Boolean, minutes: Int) = prefs.edit()
        .putBoolean(KEY_REPEAT_ENABLED, enabled)
        .putInt(KEY_REPEAT_MINUTES, minutes)
        .apply()

    override val repeatCallWindowMs: Long
        get() = if (repeatCallEnabled) repeatCallMinutes * 60_000L else 0L

    private val rejectPrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("callshift_rejects", Context.MODE_PRIVATE)

    override fun lastRejectedAt(e164: String): Long? = rejectPrefs.getLong(e164, 0L).takeIf { it > 0 }

    /** Запомнить сброс (для «повторного звонка»); старые записи чистим. */
    fun markRejected(e164: String, atMs: Long = System.currentTimeMillis()) {
        val edit = rejectPrefs.edit().putLong(e164, atMs)
        rejectPrefs.all.forEach { (k, v) -> if (v is Long && atMs - v > 24 * 3600_000L) edit.remove(k) }
        edit.apply()
    }

    /** Принят ли юридический дисклеймер (ТЗ Приложение D). */
    val disclaimerAccepted: Boolean
        get() = prefs.getBoolean(KEY_DISCLAIMER, false)

    fun acceptDisclaimer() = prefs.edit().putBoolean(KEY_DISCLAIMER, true).apply()

    /** Желаемое состояние MMI-переадресации (таблица cf_state, ТЗ п. 9.3). */
    fun putDesiredCf(simId: String, code: String, target: String?) =
        prefs.edit().putString("cf_${simId}_$code", target ?: "").apply()

    fun desiredCf(simId: String, code: String): String? =
        prefs.getString("cf_${simId}_$code", null)?.takeIf { it.isNotBlank() }

    /** Все сохранённые desired-state записи: (simId, code) → target. */
    fun allDesiredCf(): Map<Pair<String, String>, String> =
        prefs.all.entries
            .filter { it.key.startsWith("cf_") }
            .mapNotNull { (k, v) ->
                val parts = k.removePrefix("cf_").split("_", limit = 2)
                val value = v as? String
                if (parts.size == 2 && !value.isNullOrBlank()) (parts[0] to parts[1]) to value else null
            }
            .toMap()

    companion object {
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_SYSTEM = "system"
        private const val KEY_THEME = "theme_mode"

        private const val KEY_MASTER = "master_enabled"
        private const val KEY_DEFAULT_VERDICT = "default_verdict"
        private const val KEY_DEFAULT_STRATEGY = "default_strategy"
        private const val KEY_DEFAULT_TARGET = "default_target"
        private const val KEY_SCREEN_SELF_MANAGED = "screen_self_managed"
        private const val KEY_LOG_LEVEL = "log_level"
        private const val KEY_MASK_NUMBERS = "mask_numbers"
        private const val KEY_MASK_UI = "mask_numbers_ui"
        private const val KEY_OWNER_NUMBERS = "owner_numbers"
        private const val KEY_STORM_MAX = "storm_max_dials"
        private const val KEY_STORM_WINDOW = "storm_window_ms"
        private const val KEY_DTMF_TRANSFER = "dtmf_transfer_enabled"
        private const val KEY_DISCLAIMER = "disclaimer_accepted"
        private const val KEY_AR_ENABLED = "ar_enabled"
        private const val KEY_AR_TEXT = "ar_text"
        private const val KEY_AR_SCOPE = "ar_scope"
        private const val KEY_AR_UNTIL = "ar_until"
        private const val KEY_AR_SIM = "ar_sim"
        private const val KEY_WHITELIST = "whitelist"
        private const val KEY_REPEAT_ENABLED = "repeat_enabled"
        private const val KEY_REPEAT_MINUTES = "repeat_minutes"
        const val DEFAULT_AUTO_REPLY_TEXT = "Сейчас не могу ответить, перезвоню позже."
    }
}
