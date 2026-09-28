package fi.callshift.app.domain

/** Memory-only bounded report. Raw UI strings, resource IDs and phone values never enter frames. */
class MaxUiDiagnostics(private val now: () -> Long) {
    enum class Stage { FIND_BY_PHONE, GLOBAL_SEARCH, CHAT_LIST_BACK, CHAT_LIST, ROUTE_WAIT, ROUTE_PICK, ROUTE_READY, PROBE, OBSERVE, START, CHAT, SEARCH, RESULTS, VERIFY, PROFILE_OPEN, PROFILE_CHECK, PROFILE_RETURN, DRY_CHECK, INPUT, CLICK, STOP }
    enum class Label { EMPTY, PHONE, SEARCH, SEND, REDACTED, PASSWORD, PHONE_FIELD }
    data class Node(val visible: Boolean, val editable: Boolean, val clickable: Boolean,
        val enabled: Boolean, val hasId: Boolean, val top: Boolean, val label: Label)
    data class Frame(val stage: Stage, val version: Long, val unlocked: Boolean, val profile: Boolean,
        val counts: List<Int>, val outcome: String)
    private var until = 0L
    private var cardLearning = MaxCardLearningPolicy.Status.IDLE
    @Synchronized fun cardLearning(status: MaxCardLearningPolicy.Status) { if (active()) cardLearning = status }
    private var editor: List<Int>? = null
    @Synchronized fun editorCheck(hasText: Boolean, hasHint: Boolean, equal: Boolean,
        showingHint: Boolean, focused: Boolean, hasSelection: Boolean, interpretedEmpty: Boolean) {
        if (active()) editor = listOf(hasText, hasHint, equal, showingHint, focused, hasSelection, interpretedEmpty)
            .map { if (it) 1 else 0 }
    }
    private var opener: List<Int>? = null
    private var globalSearch: List<Int>? = null
    @Synchronized fun globalSearchCheck(editables: Int, fields: Int, section: Boolean, confirmed: Boolean) {
        if (active()) globalSearch = listOf(editables.coerceIn(0, 600), fields.coerceIn(0, 600),
            if (section) 1 else 0, if (confirmed) 1 else 0)
    }
    @Synchronized fun profileOpener(clickable: Boolean, declaresClick: Boolean, semantic: Int, found: Boolean) {
        if (active()) opener = listOf(if (clickable) 1 else 0, if (declaresClick) 1 else 0,
            semantic.coerceIn(0, 600), if (found) 1 else 0)
    }
    private var lastStop = "NONE"
    @Synchronized fun stopReason(message: String) {
        if (!active()) return
        // Map app-owned messages to a closed code set; never retain the supplied text.
        lastStop = when {
            message.startsWith("MAX: действие «Найти по номеру»") -> "PHONE_LOOKUP_AMBIGUOUS"
            message.startsWith("MAX: экран изменился до поиска по номеру") || message.startsWith("MAX: запрос или кнопка изменились до поиска по номеру") -> "PHONE_LOOKUP_CHANGED"
            message.startsWith("MAX не принял «Найти по номеру»") -> "PHONE_LOOKUP_CLICK_FAILED"
            message.startsWith("MAX: экран пароля") -> "APP_PASSWORD_FIELD"
            message.startsWith("MAX: после возврата общий список") || message.startsWith("MAX: общий список чатов") -> "CHAT_LIST_UNCONFIRMED"
            message.startsWith("MAX: исходный чат изменился") -> "CHAT_CHANGED_BEFORE_BACK"
            message.startsWith("MAX: повторный выход") -> "CHAT_LIST_BACK_ALREADY_USED"
            message.startsWith("MAX: Android не принял возврат") -> "CHAT_LIST_BACK_FAILED"
            message.startsWith("MAX: SIM звонка") -> "SIM_UNKNOWN"
            message.startsWith("MAX: для SIM звонка") -> "ROUTE_MISSING"
            message.startsWith("MAX: сначала нужен успешный") -> "ROUTE_TEST_REQUIRED"
            message.startsWith("MAX: SIM или маршрут") -> "ROUTE_CHANGED"
            message.startsWith("MAX: окно выбора копии не появилось") -> "PICKER_REQUIRED"
            message.startsWith("MAX: обнаружено окно двух копий") -> "CLONE_ROUTE_REQUIRED"
            message.startsWith("MAX: системное окно выбора изменилось") -> "PICKER_CHANGED"
            message.startsWith("MAX: система не приняла выбор") -> "PICKER_CLICK_FAILED"
            message.startsWith("MAX: не удалось открыть выбранный аккаунт") -> "ROUTE_TIMEOUT"
            message.startsWith("MAX: Android не передал окно") -> "NO_ACTIVE_WINDOW"
            message.startsWith("В чате есть черновик") || message.startsWith("В открытом чате есть черновик") -> "DRAFT_NOT_EMPTY"
            message.startsWith("Не определено безопасное открытие карточки") -> "PROFILE_OPEN_UNAVAILABLE"
            message.startsWith("MAX: режим выключен") -> "MODE_DISABLED"
            message.startsWith("MAX: профиль интерфейса не сохранён") -> "LAYOUT_MISSING"
            message.startsWith("MAX обновился") -> "LAYOUT_OUTDATED"
            message.startsWith("MAX: разблокируйте экран") -> "LOCKED"
            else -> "OTHER_STOP_SEE_JOURNAL"
        }
    }
    private val frames = ArrayDeque<Frame>()
    private val pickerFrames = ArrayDeque<MaxPickerInspection.Report>()
    @Synchronized fun recordPicker(report: MaxPickerInspection.Report) {
        if (!active() || pickerFrames.lastOrNull() == report) return
        if (pickerFrames.size == 12) pickerFrames.removeFirst()
        pickerFrames.addLast(report)
    }
    private var lastCapture: MaxProfileCapture.Issue? = null
    private var connectedAtCapture: Boolean? = null
    @Synchronized fun recordCapture(issue: MaxProfileCapture.Issue, version: Long, unlocked: Boolean, connected: Boolean) {
        if (!active()) return
        lastCapture = issue
        connectedAtCapture = connected
        record(Stage.PROBE, version, unlocked, false, outcome = "")
    }
    @Synchronized fun start(milliseconds: Long) { frames.clear(); pickerFrames.clear(); cardLearning = MaxCardLearningPolicy.Status.IDLE; opener = null; globalSearch = null; editor = null; lastStop = "NONE"; lastCapture = null; connectedAtCapture = null; until = now() + milliseconds.coerceIn(1, 180_000) }
    @Synchronized fun active(): Boolean = now() < until
    @Synchronized fun clear() { frames.clear(); pickerFrames.clear(); cardLearning = MaxCardLearningPolicy.Status.IDLE; opener = null; globalSearch = null; editor = null; lastStop = "NONE"; lastCapture = null; connectedAtCapture = null; until = 0L }
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
        appendLine("CallShift MAX diagnostic v9")
        // Version string restricted too: callers cannot accidentally export arbitrary strings here.
        appendLine("Android API=$api; app=${appVersion.takeIf { it.matches(Regex("[0-9][0-9A-Za-z.-]{0,60}")) } ?: "unknown"}")
        appendLine("Capture active=${active()}; frames=${frames.size}")
        appendLine("Stop reason=$lastStop")
        appendLine("Card learning=${cardLearning.name}")
        appendLine("Editor flags (hasText,hasHint,textEqualsHint,showingHint,focused,hasSelection,interpretedEmpty)=${editor?.joinToString(",") ?: "NOT_CHECKED"}")
        appendLine("Global search (editables,globalFields,section,confirmed)=${globalSearch?.joinToString(",") ?: "NOT_CHECKED"}")
        appendLine("Profile opener (titleClickable,titleClickAction,semanticButtons,targetFound)=${opener?.joinToString(",") ?: "NOT_CHECKED"}")
        appendLine("Profile capture=${lastCapture?.name ?: "NOT_STARTED"}; serviceConnected=${connectedAtCapture ?: "unknown"}")
        appendLine("No UI text, names, numbers, resource IDs, screenshots or message contents included.")
        appendLine("counts: visible,editable,enabledClickable,withId,topThird,EMPTY,PHONE,SEARCH,SEND,REDACTED,PASSWORD,PHONE_FIELD")
        frames.forEachIndexed { index, f ->
            appendLine("${index + 1}: ${f.stage} MAXversion=${f.version} unlocked=${f.unlocked} profile=${f.profile} result=${f.outcome} counts=${f.counts.joinToString(",")}")
        }
        appendLine("Picker checks=${pickerFrames.size}; counts: visible,titles,labels,described,enabledClickable")
        pickerFrames.forEachIndexed { index, p ->
            appendLine("Picker ${index + 1}: ${p.issue.name} counts=${p.visible},${p.titles},${p.labels},${p.described},${p.clickable}")
        }
        if (frames.isEmpty() && pickerFrames.isEmpty()) appendLine("No frames captured in this process. Start interface or picker capture explicitly.")
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
