package fi.callshift.app.max

import android.content.Context
import fi.callshift.app.domain.SmsSafety
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Not exported in settings backups. Enabling real UI clicks is always explicit. */
class MaxUiStore(context: Context) {
    private val p = context.getSharedPreferences("max_ui_v1", Context.MODE_PRIVATE)
    val enabled get() = p.getBoolean("enabled", false)
    val live get() = p.getBoolean("live", false)
    val header get() = p.getString("header", "").orEmpty()
    val input get() = p.getString("input", "").orEmpty()
    val version get() = p.getLong("version", -1)
    fun modes(enabled: Boolean, live: Boolean) { check(p.edit().putBoolean("enabled", enabled).putBoolean("live", live).commit()) }
    fun profile(header: String, input: String, version: Long) {
        check(p.edit().putString("header", header).putString("input", input).putLong("version", version)
            .putBoolean("live", false).commit())
    }
    /** Reserve before editing/clicking. Errors and process death never cause an automatic retry. */
    @Synchronized fun reserve(): Boolean {
        val entries = Json.decodeFromString<List<SmsSafety.Reservation>>(p.getString("attempts", "[]")!!)
        val next = SmsSafety.reserve(entries, System.currentTimeMillis(), 1, 5) ?: return false
        check(p.edit().putString("attempts", Json.encodeToString(next)).commit())
        return true
    }
}
