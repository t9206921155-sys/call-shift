package fi.callshift.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SettingsBackup(
    val format: String = "callshift-settings",
    val version: Int = 1,
    val createdAt: Long = 0,
    val rules: List<Rule> = emptyList(),
    val autoReply: AutoReplySettings = AutoReplySettings(),
    val whitelist: Set<String> = emptySet(),
    val repeatEnabled: Boolean = false,
    val repeatMinutes: Int = 3,
    val smsLimit: Int = SmsSafety.DEFAULT_LIMIT,
    val perSimCooldown: Boolean = true,
    val maskUi: Boolean = false,
)

object BackupCodec {
    const val MAX_BYTES = 1_048_576
    private val json = Json { encodeDefaults = true; prettyPrint = true; ignoreUnknownKeys = false }
    fun encode(backup: SettingsBackup): String {
        val sanitized = backup.copy(
            // Credentials in webhook endpoints are deliberately not exported.
            rules = backup.rules.map { it.copy(action = it.action.copy(endpoint = null)) },
        )
        validate(sanitized)
        return json.encodeToString(sanitized).also { require(it.toByteArray().size <= MAX_BYTES) { "Копия больше 1 МБ" } }
    }
    fun decode(text: String): SettingsBackup {
        require(text.toByteArray().size <= MAX_BYTES) { "Файл больше 1 МБ" }
        return json.decodeFromString<SettingsBackup>(text).also(::validate)
    }
    private fun validate(b: SettingsBackup) {
        require(b.format == "callshift-settings" && b.version == 1) { "Неизвестный формат/версия копии" }
        require(b.rules.size <= 500 && b.rules.map { it.id }.distinct().size == b.rules.size) { "Слишком много правил или повторяются ID" }
        require(b.smsLimit in 1..1000 && b.repeatMinutes in 1..1440)
        require(b.whitelist.size <= 2000 && b.whitelist.all { it.length in 1..100 })
        fun checkReply(text: String?, channels: List<String>, interval: Int) {
            require(text == null || text.length <= SmsAutoReplyPolicy.MAX_LENGTH)
            require(interval in 0..1440 && channels.size <= ReplyChannel.labels.size && channels.all(ReplyChannel::supports))
        }
        require(b.autoReply.scope in listOf(AutoReplySettings.SCOPE_ALL, AutoReplySettings.SCOPE_UNKNOWN))
        require(b.autoReply.simSelector.length in 1..512)
        checkReply(b.autoReply.text, ReplyOptions.channels(b.autoReply.replyChannel, b.autoReply.replyChannels), b.autoReply.replyCooldownMinutes)
        b.rules.forEach { r ->
            require(r.id > 0 && r.name.length in 1..256 && r.priority in 0..999 && r.simSelector.length in 1..512)
            require(r.action.target == null || r.action.target.length <= 2048)
            require(r.action.endpoint == null) { "Копия с внешними токенами/endpoint не поддерживается" }
            require(r.conditions.anyOf.size <= 100 && r.conditions.anyOf.all { it.size <= 100 })
            r.conditions.anyOf.flatten().forEach { c ->
                require(c.type.length in 1..100 && (c.pattern?.length ?: 0) <= 512 && (c.text?.length ?: 0) <= 512)
                require(c.numbers.size <= 2000 && c.numbers.all { it.length <= 100 })
            }
            r.schedule?.let { s ->
                require(s.days.all { it in 1..7 } && s.days.size <= 7)
                require((s.from == null) == (s.to == null))
                require(s.from == null || ScheduleMatcher.parse(s.from) != null)
                require(s.to == null || ScheduleMatcher.parse(s.to) != null)
            }
            require(r.validFrom == null || r.validTo == null || r.validFrom <= r.validTo)
            checkReply(r.action.autoReplySms, ReplyOptions.channels(r.action.replyChannel, r.action.replyChannels), r.action.replyCooldownMinutes)
        }
    }
    /** Imported rules/auto-reply NEVER become active automatically. */
    fun safeRestore(b: SettingsBackup): SettingsBackup = b.copy(
        rules = b.rules.map { it.copy(enabled = false) }, autoReply = b.autoReply.copy(enabled = false),
    )
}
