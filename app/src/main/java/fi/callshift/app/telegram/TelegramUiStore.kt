package fi.callshift.app.telegram

import android.content.Context
import fi.callshift.app.domain.TelegramUiPolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Not exported in backups. Enabling real UI taps in Telegram is always explicit. */
class TelegramUiStore(context: Context) {
    private val p = context.getSharedPreferences("tg_ui_v1", Context.MODE_PRIVATE)

    val enabled get() = p.getBoolean("enabled", false)
    val live get() = p.getBoolean("live", false)
    val version get() = p.getLong("version", -1)

    fun modes(enabled: Boolean, live: Boolean) {
        check(p.edit().putBoolean("enabled", enabled).putBoolean("live", live).commit())
    }

    fun markVersion(version: Long) { check(p.edit().putLong("version", version).commit()) }

    fun sendOutcome(): TelegramUiPolicy.TrainStatus = runCatching {
        TelegramUiPolicy.TrainStatus.valueOf(p.getString("send_outcome", "IDLE")!!)
    }.getOrDefault(TelegramUiPolicy.TrainStatus.IDLE)

    fun sendOutcome(state: TelegramUiPolicy.TrainStatus) {
        check(p.edit().putString("send_outcome", state.name).commit())
    }

    fun learnedSend(): TelegramUiPolicy.SendRule? = runCatching {
        p.getString("learned_send", null)?.let { Json.decodeFromString<TelegramUiPolicy.SendRule>(it) }
    }.getOrNull()

    fun learnSend(rule: TelegramUiPolicy.SendRule) {
        check(p.edit().putString("learned_send", Json.encodeToString(rule)).commit())
    }

    fun forgetSend() { check(p.edit().remove("learned_send").commit()) }
}
