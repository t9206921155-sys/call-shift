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
    fun learnedCard(): fi.callshift.app.domain.MaxCardLearningPolicy.Rule? = runCatching {
        p.getString("learned_card", null)?.let { Json.decodeFromString<fi.callshift.app.domain.MaxCardLearningPolicy.Rule>(it) }
    }.getOrNull()
    fun learnCard(rule: fi.callshift.app.domain.MaxCardLearningPolicy.Rule) {
        check(p.edit().putString("learned_card", Json.encodeToString(rule)).putBoolean("live", false).commit())
    }
    fun forgetCard() { check(p.edit().remove("learned_card").putBoolean("live", false).commit()) }
    fun modes(enabled: Boolean, live: Boolean) { check(p.edit().putBoolean("enabled", enabled).putBoolean("live", live).commit()) }
    fun profile(header: String, input: String, version: Long) {
        check(p.edit().putString("header", header).putString("input", input).putLong("version", version).remove("learned_card")
            .putBoolean("live", false).commit())
    }
    // Temporary interface metadata only: survives process recreation, never stores contact text.
    fun clearStaged() { check(p.edit().remove("staged_header").remove("staged_input").remove("staged_wall").commit()) }
    fun stage(header: String, input: String, version: Long, elapsed: Long) {
        check(p.edit().putString("staged_header", header).putString("staged_input", input)
            .putLong("staged_version", version).putLong("staged_elapsed", elapsed)
            .putLong("staged_wall", System.currentTimeMillis()).commit())
    }
    fun staged(): MaxUiService.Profile? {
        val age = System.currentTimeMillis() - p.getLong("staged_wall", 0)
        if (age !in 0..600_000) return null
        val h = p.getString("staged_header", null) ?: return null
        val i = p.getString("staged_input", null) ?: return null
        return MaxUiService.Profile(h, i, p.getLong("staged_version", -1), "восстановленный образец (без номера)", p.getLong("staged_elapsed", -1))
    }
    /** Reserve before editing/clicking. Errors and process death never cause an automatic retry. */
    @Synchronized fun reserve(): Boolean {
        val entries = Json.decodeFromString<List<SmsSafety.Reservation>>(p.getString("attempts", "[]")!!)
        val next = SmsSafety.reserve(entries, System.currentTimeMillis(), 1, 5) ?: return false
        check(p.edit().putString("attempts", Json.encodeToString(next)).commit())
        return true
    }
}
