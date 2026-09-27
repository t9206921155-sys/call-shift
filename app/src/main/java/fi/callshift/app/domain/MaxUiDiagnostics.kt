package fi.callshift.app.domain

/** Memory-only bounded report. Raw UI strings, resource IDs and phone values never enter frames. */
class MaxUiDiagnostics(private val now: () -> Long) {
    enum class Stage { OBSERVE, START, CHAT, SEARCH, RESULTS, VERIFY, PROFILE_OPEN, PROFILE_CHECK, PROFILE_RETURN, DRY_CHECK, INPUT, CLICK, STOP }
    enum class Label { EMPTY, PHONE, SEARCH, SEND, REDACTED, PASSWORD, PHONE_FIELD }
    data class Node(val visible: Boolean, val editable: Boolean, val clickable: Boolean,
        val enabled: Boolean, val hasId: Boolean, val top: Boolean, val label: Label)
    data class Frame(val stage: Stage, val version: Long, val unlocked: Boolean, val profile: Boolean,
        val counts: List<Int>, val outcome: String)
    private var until = 0L
    private val frames = ArrayDeque<Frame>()
    @Synchronized fun start(milliseconds: Long) { frames.clear(); until = now() + milliseconds.coerceIn(1, 180_000) }
    @Synchronized fun active(): Boolean = now() < until
    @Synchronized fun clear() { frames.clear(); until = 0L }
    @Synchronized fun record(stage: Stage, version: Long, unlocked: Boolean, profile: Boolean,
        nodes: List<Node> = emptyList(), outcome: String = "") {
        if (!active()) return
        val visible = nodes.take(600).filter { it.visible }
        val counts = listOf(visible.size, visible.count { it.editable }, visible.count { it.clickable && it.enabled },
            visible.count { it.hasId }, visible.count { it.top }) + Label.values().map { label -> visible.count { it.label == label } }
        // Outcomes are app-owned enums, never arbitrary exception/UI strings.
        val safeOutcome = outcome.takeIf { it in setOf("BLOCKED", "UI_UNKNOWN", "UI_CHECKED", "UI_PENDING") }.orEmpty()
        val frame = Frame(stage, version, unlocked, profile, counts, safeOutcome)
        if (frames.lastOrNull() == frame) return
        if (frames.size == 24) frames.removeFirst()
        frames.addLast(frame)
    }
    @Synchronized fun report(api: Int, appVersion: String): String = buildString {
        appendLine("CallShift MAX diagnostic v2")
        // Version string restricted too: callers cannot accidentally export arbitrary strings here.
        appendLine("Android API=$api; app=${appVersion.takeIf { it.matches(Regex("[0-9][0-9A-Za-z.-]{0,60}")) } ?: "unknown"}")
        appendLine("Capture active=${active()}; frames=${frames.size}")
        appendLine("No UI text, names, numbers, resource IDs, screenshots or message contents included.")
        appendLine("counts: visible,editable,enabledClickable,withId,topThird,EMPTY,PHONE,SEARCH,SEND,REDACTED,PASSWORD,PHONE_FIELD")
        frames.forEachIndexed { index, f ->
            appendLine("${index + 1}: ${f.stage} MAXversion=${f.version} unlocked=${f.unlocked} profile=${f.profile} result=${f.outcome} counts=${f.counts.joinToString(",")}")
        }
        if (frames.isEmpty()) appendLine("No MAX frames captured. Check the accessibility service and open MAX while capture is active.")
    }
    companion object {
        fun label(password: Boolean, text: String?, description: String?, hint: String?): Label {
            if (password) return Label.PASSWORD
            val values = listOfNotNull(text, description, hint).filter { it.isNotBlank() }
            return when {
                values.isEmpty() -> Label.EMPTY
                values.any(MaxProfilePolicy::phoneLabel) -> Label.PHONE_FIELD
                values.any { MaxUiPolicy.phone(it) != null } -> Label.PHONE
                values.any(MaxSearchPolicy::isSearchLabel) -> Label.SEARCH
                values.any { it.trim().lowercase(java.util.Locale.ROOT) in setOf("send", "send message", "отправить", "отправить сообщение") } -> Label.SEND
                else -> Label.REDACTED
            }
        }
    }
}
