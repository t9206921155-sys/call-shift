package fi.callshift.app.max

import android.content.Context
import fi.callshift.app.domain.SmsSafety
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Not exported in settings backups. Enabling real UI clicks is always explicit. */
class MaxUiStore(context: Context) {
    private val p = context.getSharedPreferences("max_ui_v1", Context.MODE_PRIVATE)
    enum class ReportKind { ATTEMPT, LEARNING }
    fun saveReport(diagnostics: fi.callshift.app.domain.MaxUiDiagnostics, kind: ReportKind, durable: Boolean = false) {
        // Only the redacted report generator can supply persisted contents; never caller text.
        val report = diagnostics.report(android.os.Build.VERSION.SDK_INT, fi.callshift.app.BuildConfig.VERSION_NAME)
        val edit = p.edit().putString("last_report", report.take(16000))
            .putLong("last_report_at", System.currentTimeMillis()).putString("last_report_kind", kind.name)
        // Never block between validating a UI node and acting on it for diagnostic disk I/O.
        if (durable) check(edit.commit()) else edit.apply()
    }
    fun savedReport(): String? = p.getString("last_report", null)?.let {
        val kind = runCatching { ReportKind.valueOf(p.getString("last_report_kind", "ATTEMPT")!!) }.getOrDefault(ReportKind.ATTEMPT)
        "Saved MAX report: ${kind.name}; time=${p.getLong("last_report_at", 0)}\n$it"
    }
    val enabled get() = p.getBoolean("enabled", false)
    val live get() = p.getBoolean("live", false)
    val header get() = p.getString("header", "").orEmpty()
    val input get() = p.getString("input", "").orEmpty()
    val version get() = p.getLong("version", -1)
    fun cardOutcome() = runCatching {
        fi.callshift.app.domain.MaxCardLearningPolicy.Status.valueOf(p.getString("card_outcome", "IDLE")!!)
    }.getOrDefault(fi.callshift.app.domain.MaxCardLearningPolicy.Status.IDLE)
    fun cardOutcome(state: fi.callshift.app.domain.MaxCardLearningPolicy.Status) {
        check(p.edit().putString("card_outcome", state.name).commit())
    }
    fun learnedCard(): fi.callshift.app.domain.MaxCardLearningPolicy.Rule? = runCatching {
        p.getString("learned_card", null)?.let { Json.decodeFromString<fi.callshift.app.domain.MaxCardLearningPolicy.Rule>(it) }
    }.getOrNull()
    fun learnCard(rule: fi.callshift.app.domain.MaxCardLearningPolicy.Rule) {
        check(p.edit().putString("learned_card", Json.encodeToString(rule)).putBoolean("live", false).commit())
    }
    fun forgetCard() { check(p.edit().remove("learned_card").putBoolean("live", false).commit()) }
    fun sendOutcome() = runCatching {
        fi.callshift.app.domain.MaxSendTrainingPolicy.Status.valueOf(p.getString("send_outcome", "IDLE")!!)
    }.getOrDefault(fi.callshift.app.domain.MaxSendTrainingPolicy.Status.IDLE)
    fun sendOutcome(state: fi.callshift.app.domain.MaxSendTrainingPolicy.Status) {
        check(p.edit().putString("send_outcome", state.name).commit())
    }
    fun learnedSend(): fi.callshift.app.domain.MaxSendTrainingPolicy.Rule? = runCatching {
        p.getString("learned_send", null)?.let { Json.decodeFromString<fi.callshift.app.domain.MaxSendTrainingPolicy.Rule>(it) }
    }.getOrNull()
    fun learnSend(rule: fi.callshift.app.domain.MaxSendTrainingPolicy.Rule) {
        check(p.edit().putString("learned_send", Json.encodeToString(rule)).commit())
    }
    fun forgetSend() { check(p.edit().remove("learned_send").commit()) }
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
        val next = SmsSafety.reserve(entries, System.currentTimeMillis(), 1, 20) ?: return false
        check(p.edit().putString("attempts", Json.encodeToString(next)).commit())
        return true
    }
}
