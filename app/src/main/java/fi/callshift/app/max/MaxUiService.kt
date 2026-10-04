package fi.callshift.app.max

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo as Node
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.MaxUiDiagnostics
import fi.callshift.app.domain.MaxPickerInspection
import fi.callshift.app.domain.MaxRoutePolicy
import fi.callshift.app.domain.MaxUiPolicy
import fi.callshift.app.domain.MaxProfileActionPolicy
import fi.callshift.app.domain.MaxCardLearningPolicy
import fi.callshift.app.domain.MaxSendTrainingPolicy
import fi.callshift.app.domain.MaxProfileCapture
import fi.callshift.app.domain.MaxProfilePolicy
import fi.callshift.app.domain.MaxProfileReturnPolicy
import fi.callshift.app.domain.MaxChatListPolicy
import fi.callshift.app.domain.MaxLeftoverPolicy
import fi.callshift.app.domain.MaxSearchPolicy
import fi.callshift.app.forward.CallEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** Opt-in bounded UI interaction. Recipient identity is a phone, never a display name.
 * A separately consented title gesture resolves fresh bounds; no screen coordinates are stored. */
class MaxUiService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { MaxUiStore(this) }
    private val routes by lazy { MaxRouteStore(this) }
    private var pickerUntil = 0L
    private val pickerTick = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() >= pickerUntil) { pickerUntil = 0; return }
            runCatching {
                if (!unlocked()) pickerReport(MaxPickerInspection.Report(MaxPickerInspection.Issue.LOCKED))
                else {
                    val root = pickerRoot()
                    if (root == null) pickerReport(MaxPickerInspection.Report(MaxPickerInspection.Issue.NO_ROOT))
                    else {
                        val inspection = MaxSystemPicker.inspect(this@MaxUiService, root)
                        pickerReport(inspection.report)
                        inspection.snapshot?.let { pickerCandidate = it.picker }
                    }
                }
            }.onFailure { pickerReport(MaxPickerInspection.Report(MaxPickerInspection.Issue.ERROR)) }
            main.postDelayed(this, 750)
        }
    }
    /** Prefer the focused foreground window: active root can still refer to MAX behind a dialog. */
    private fun pickerRoot(): Node? {
        val focused = windows.filter { it.isFocused && it.type in setOf(
            android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION,
            android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) }
        if (focused.size > 1) return null
        if (focused.size == 1) return focused.single().root // never fall through to a background root
        return rootInActiveWindow
    }
    private var lastPickerIssue = MaxPickerInspection.Issue.STARTED
    private fun pickerReport(report: MaxPickerInspection.Report) {
        diagnostics.recordPicker(report)
        if (pickerCandidate != null) return
        if (!MaxPickerInspection.meaningful(report.issue) && MaxPickerInspection.meaningful(lastPickerIssue)) return
        lastPickerIssue = report.issue
        pickerStatus = "${report.issue.name}: ${report.issue.explanation}"
    }
    private val app by lazy { CallShiftApp.from(this) }
    private data class Pending(val event: CallEvent, val number: String, val text: String, val dry: Boolean,
        val accountId: String, val route: MaxRoutePolicy.Route,
        val started: Long = SystemClock.elapsedRealtime(), var routeClickedAt: Long? = null,
        var routeReady: Boolean = false,
        var listBackAt: Long? = null, var globalSearchConfirmed: Boolean = false,
        var keyboardReturn: ProfileVisit? = null,
        var edited: Boolean = false, var searchClicked: Boolean = false, var query: String? = null,
        var queryAt: Long = 0, var queryIndex: Int = 0, var selectedAt: Long? = null,
        var findByPhoneAt: Long? = null, var sendWaitAt: Long? = null,
        val contactName: String? = null, val triedRows: MutableSet<Node> = mutableSetOf(),
        var inspectedNames: Int = 0, var inspectedTotal: Int = 0,
        var profile: ProfileVisit? = null, var skipCurrentProfile: Boolean = false, var returningSearch: Boolean = false)
    private data class ProfileVisit(val title: Node, val editor: Node, val caption: String, val window: Int,
        val started: Long, var returning: Boolean = false, var verified: Boolean = false, var returnedAt: Long? = null,
        val anchor: MaxProfileReturnPolicy.Anchor? = null, val openerSources: Set<Node> = emptySet(),
        var openedAt: Long = 0, var openerEventPending: Boolean = true, var cardReadAt: Long? = null,
        var returnedTitle: Node? = null, var returnedEditor: Node? = null, var candidateAt: Long = 0,
        var returnIssue: MaxProfileReturnPolicy.Issue? = null)
    private var pending: Pending? = null
    private var busy = false
    /** Fingerprint of the draft this process is holding inside MAX, if any. The stored
     * key authorizes a later run to clear exactly this text once the run stopped. */
    private var ownDraft: String? = null
    private var lastSavedStage: MaxUiDiagnostics.Stage? = null
    private val tick = Runnable { step() }
    private val probeTick = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() >= probeUntil) { probeUntil = 0; return }
            runCatching { capture() }.onFailure { captureState(MaxProfileCapture.Issue.ERROR) }
            main.postDelayed(this, 750)
        }
    }
    private val timeout = Runnable { finish("BLOCKED", "Время ожидания MAX истекло. Повтора не будет; проверьте черновик вручную") }

    private val logs = kotlinx.coroutines.channels.Channel<CallEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    override fun onCreate() {
        super.onCreate()
        app.appScope.launch { for (event in logs) runCatching { app.eventStore.record(event) } }
    }
    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        // Reconnection/reboot must not silently resume permission to send messages.
        store.modes(store.enabled, false)
    }
    override fun onInterrupt() { stop("Служба прервана") }
    override fun onDestroy() { stop("Служба отключена"); instance = null; logs.close(); super.onDestroy() }
    fun stop(reason: String) {
        endProbe()
        endPicker()
        endCardLearning()
        endSendLearning()
        sendProofEvent = null; main.removeCallbacks(sendProofTick)
        finish("BLOCKED", reason)
        runCatching { store.modes(false, false) }
    }
    private fun endPicker() { pickerUntil = 0; main.removeCallbacks(pickerTick) }
    private fun endProbe() { main.removeCallbacks(probeTick); probeUntil = 0 }
    private fun startProbe() {
        stop("Отправка остановлена для настройки интерфейса")
        candidate = null
        store.clearStaged()
        diagnostics.start(60_000)
        probeUntil = SystemClock.elapsedRealtime() + 60_000
        captureState(MaxProfileCapture.Issue.WAITING)
        main.post(probeTick)
    }
    private fun captureState(issue: MaxProfileCapture.Issue) {
        // Returning to CallShift must not hide the last concrete refusal from MAX.
        if (issue in listOf(MaxProfileCapture.Issue.OTHER_APP, MaxProfileCapture.Issue.NO_ROOT) && captureIssue !in listOf(
                MaxProfileCapture.Issue.NOT_STARTED, MaxProfileCapture.Issue.WAITING,
                MaxProfileCapture.Issue.NO_ROOT, MaxProfileCapture.Issue.OTHER_APP)) return
        captureIssue = issue
        diagnostics.recordCapture(issue, version(), unlocked(), connected)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != MaxUiPolicy.PACKAGE) return
        if (cardUntil != 0L) runCatching { learnCardClick(event) }
            .onFailure { endCardLearning(MaxCardLearningPolicy.Status.ERROR) }
        if (sendTrainingUntil != 0L && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            runCatching { handleSendTrainingClick(event) }.onFailure { endSendLearning(MaxSendTrainingPolicy.Status.ERROR) }
        }
        val visit = pending?.profile
        if (visit != null && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val source = event.source
            val owned = visit.openerEventPending &&
                fi.callshift.app.domain.MaxHeaderGesturePolicy.expectedEvent(SystemClock.uptimeMillis(),
                    visit.openedAt, event.eventTime, visit.window, event.windowId) &&
                (visit.cardReadAt == null || event.eventTime <= visit.cardReadAt!!) &&
                (source == null || source in visit.openerSources)
            if (owned) visit.openerEventPending = false
            else {
                finish("BLOCKED", "Постороннее нажатие во время проверки карточки; отправка остановлена"); return
            }
        }
        // Profile probing polls independently: quiet MAX screens need not emit events.
        if (pending == null && diagnostics.active()) trace(MaxUiDiagnostics.Stage.OBSERVE)
        if (pending != null && !busy) { main.removeCallbacks(tick); main.postDelayed(tick, 300) }
    }
    private fun editableText(n: Node?): String {
        val text = n?.text?.toString()
        val hint = n?.hintText?.toString()
        val value = MaxUiPolicy.editableText(text, hint, n?.isShowingHintText == true)
        if (n != null && n.isEditable && n.viewIdResourceName == store.input) {
            diagnostics.editorCheck(!text.isNullOrEmpty(), !hint.isNullOrEmpty(),
                !text.isNullOrEmpty() && text == hint, n.isShowingHintText, n.isFocused,
                n.textSelectionStart >= 0 || n.textSelectionEnd >= 0, value.isEmpty())
        }
        return value
    }
    private fun unlocked() = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun version(): Long = runCatching { packageManager.getPackageInfo(MaxUiPolicy.PACKAGE, 0).longVersionCode }.getOrDefault(-1)
    private fun nodes(root: Node): List<Node> {
        val out = mutableListOf<Node>()
        fun visit(n: Node, depth: Int) {
            if (out.size >= 600 || depth > 25) return
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        visit(root, 0)
        return out
    }
    /** Toolbar title (phone OR name). A name is not recipient proof. */
    private fun header(n: Node): Boolean {
        if (!n.isVisibleToUser || n.isEditable || n.isPassword || n.text.isNullOrBlank()) return false
        val bounds = android.graphics.Rect(); n.getBoundsInScreen(bounds)
        if (bounds.top < 0 || bounds.bottom > resources.displayMetrics.heightPixels / 3) return false
        var parent = n.parent
        var toolbar = false
        repeat(20) {
            val current = parent ?: return@repeat
            val cls = current.className?.toString().orEmpty()
            if (current.isScrollable || listOf("RecyclerView", "ListView", "ScrollView").any { cls.contains(it) }) return false
            if (cls.endsWith("Toolbar") || current.viewIdResourceName?.contains("toolbar", ignoreCase = true) == true) toolbar = true
            parent = current.parent
        }
        return toolbar
    }
    private fun capture() {
        if (!unlocked()) { captureState(MaxProfileCapture.Issue.LOCKED); return }
        val root = rootInActiveWindow ?: run { captureState(MaxProfileCapture.Issue.NO_ROOT); return }
        if (root.packageName?.toString() != MaxUiPolicy.PACKAGE) { captureState(MaxProfileCapture.Issue.OTHER_APP); return }
        val all = nodes(root)
        trace(MaxUiDiagnostics.Stage.PROBE, all)
        val headers = all.mapIndexedNotNull { index, node ->
            if (!header(node)) null else MaxProfileCapture.Header(index, !node.viewIdResourceName.isNullOrBlank(),
                MaxUiPolicy.phone(node.text.toString()) != null, MaxProfilePolicy.titleId(node.viewIdResourceName.orEmpty()))
        }
        val inputs = all.mapIndexedNotNull { index, node ->
            if (!node.isVisibleToUser || !node.isEnabled || !node.isEditable || node.isPassword) null
            else MaxProfileCapture.Input(index, !node.viewIdResourceName.isNullOrBlank())
        }
        val selection = MaxProfileCapture.select(headers, inputs)
        captureState(selection.issue)
        if (selection.issue != MaxProfileCapture.Issue.READY) return
        val h = all[selection.headerIndex!!]
        val input = all[selection.inputIndex!!]
        if (all.count { header(it) && it.viewIdResourceName == h.viewIdResourceName } != 1) {
            captureState(MaxProfileCapture.Issue.AMBIGUOUS_HEADER); return
        }
        val previous = candidate
        val next = Profile(h.viewIdResourceName, input.viewIdResourceName, version(),
            MaxUiPolicy.phone(h.text.toString()) ?: "имя (не экспортируется)", SystemClock.elapsedRealtime())
        if (previous == null || previous.header != next.header || previous.input != next.input || next.at - previous.at > 5000) {
            store.stage(next.header, next.input, next.version, next.at)
            candidate = next
        }
    }
    private fun begin(event: CallEvent, number: String, text: String, contactName: String?, accountId: String?) {
        if (pending != null || headerGestureBusy || cardUntil != 0L || sendTrainingUntil != 0L) {
            log(event, "BLOCKED", "MAX занят сценарием, обучением или незавершённым касанием Android. Очередь и повтор отключены"); return
        }
        ownDraft = null
        diagnostics.observe(45_000); lastSavedStage = null
        trace(MaxUiDiagnostics.Stage.START)
        val startupIssue = when {
            !store.enabled -> "MAX: режим выключен. Включите проверку без отправки"
            !unlocked() -> "MAX: разблокируйте экран"
            store.header.isEmpty() || store.input.isEmpty() -> "MAX: профиль интерфейса не сохранён"
            store.version != version() -> "MAX обновился: повторите настройку профиля интерфейса"
            accountId == null || accountId !in app.telecom.phoneAccounts().keys -> "MAX: SIM звонка не определена однозначно; аккаунт не выбирается"
            else -> null
        }
        if (startupIssue != null) { log(event, "BLOCKED", startupIssue); return }
        val route = MaxRoutePolicy.resolve(accountId, app.telecom.phoneAccounts().keys, routes.routes())
        if (route == null) { log(event, "BLOCKED", "MAX: для SIM звонка не выбран аккаунт. Откройте «Аккаунт отправителя по SIM»"); return }
        val dry = !store.live || diagnostics.forcesDry()
        diagnostics.attempt(dry)
        if (!dry && !routes.tested(accountId!!, route)) {
            log(event, "BLOCKED", "MAX: сначала нужен успешный проверочный звонок для маршрута этой SIM"); return
        }
        val canonical = MaxUiPolicy.phone(number)
        if (canonical == null) { log(event, "BLOCKED", "Номер звонящего некорректен"); return }
        pending = Pending(event, canonical, text, dry, accountId!!, route, contactName = contactName)
        log(event, "UI_PENDING", "Ожидаем проверку открытого чата MAX. Отправка не подтверждена")
        main.postDelayed(timeout, 30_000)
        try {
            val launch = packageManager.getLaunchIntentForPackage(MaxUiPolicy.PACKAGE) ?: error("MAX отсутствует")
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main.postDelayed(tick, 700)
        } catch (_: Exception) { finish("BLOCKED", "Android не разрешил открыть MAX") }
    }
    /** A draft is the app's own leftover when it is byte-identical to this run's own test
     * text, or when its fingerprint matches text CallShift itself wrote into this composer
     * before: a stopped live run or an aborted training. Such a draft is cleared once and
     * the run continues. Anything else is the user's content and is never touched. */
    private fun clearOwnLeftover(p: Pending, draft: String, input: Node): Boolean {
        if (p.edited) return false
        val sameRun = draft == p.text
        val fingerprint = MaxLeftoverPolicy.key(store.input, version(), draft)
        val remembered = MaxLeftoverPolicy.isOwn(store.leftovers(), fingerprint)
        if (!sameRun && !remembered) return false
        val args = Bundle().apply { putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
        val cleared = runCatching { input.performAction(Node.ACTION_SET_TEXT, args) }.getOrDefault(false)
        if (cleared) {
            if (remembered) store.forgetLeftover(fingerprint)
            // A mid-run note, never a verdict: only a finished dry check may read UI_CHECKED.
            log(p.event, "UI_PENDING", if (sameRun) "Очищен остаточный черновик предыдущей проверки CallShift"
                else "Очищен остаточный черновик CallShift от прерванного сценария")
        }
        return cleared
    }
    private fun step() {
        if (busy) return
        val p = pending ?: return
        if (headerGestureBusy) {
            if (gestureFlight.overdue(SystemClock.elapsedRealtime())) {
                finish("BLOCKED", "Android не подтвердил завершение касания MAX. Продолжения и повтора не будет")
            } else main.postDelayed(tick, 100)
            return
        }
        try {
            if (!store.enabled || !unlocked() || !app.settings.masterEnabled || store.version != version()) {
                finish("BLOCKED", "MAX: режим выключен, экран заблокирован или версия интерфейса изменилась"); return
            }
            if (!p.dry && !store.live) { finish("BLOCKED", "Реальная отправка выключена"); return }
            if (routes.routes()[p.accountId] != p.route || p.accountId !in app.telecom.phoneAccounts().keys) {
                finish("BLOCKED", "MAX: SIM или маршрут изменились во время сценария"); return
            }
            val root = if (!p.routeReady) pickerRoot() else rootInActiveWindow
            if (root == null) {
                if (!p.routeReady && SystemClock.elapsedRealtime() - p.started > 8000) {
                    finish("BLOCKED", "MAX: Android не передал окно выбора или окно MAX"); return
                }
                main.postDelayed(tick, 500); return
            }
            if (!routeReady(p, root)) return
            if (root.packageName?.toString() != MaxUiPolicy.PACKAGE) {
                if (p.profile != null) { finish("BLOCKED", "Окно MAX потеряно во время проверки карточки; отправка остановлена"); return }
                main.postDelayed(tick, 500); return
            }
            val all = nodes(root)
            if (all.any { it.isVisibleToUser && it.isPassword }) {
                finish("BLOCKED", "MAX: экран пароля или защищённое поле; навигация и отправка запрещены"); return
            }
            trace(MaxUiDiagnostics.Stage.CHAT, all)
            val h = all.filter { it.viewIdResourceName == store.header && header(it) }
            val inputs = all.filter { it.viewIdResourceName == store.input && it.isEditable && it.isEnabled && it.isVisibleToUser && !it.isPassword }
            val input = inputs.singleOrNull()
            val draft = editableText(input)
            if (handleProfile(p, root, all, h.singleOrNull(), input)) return
            if (p.returningSearch) {
                if (searchFields(all).singleOrNull()?.text?.toString() != p.query) {
                    main.postDelayed(tick, 500); return
                }
                p.returningSearch = false; p.selectedAt = null; p.skipCurrentProfile = false
            }
            if (p.listBackAt != null && !p.globalSearchConfirmed) {
                trace(MaxUiDiagnostics.Stage.CHAT_LIST, all)
                if (chatList(all) || openGlobalSearch(all)) p.globalSearchConfirmed = true
                else {
                    val keyboard = p.keyboardReturn
                    if (keyboard != null && fi.callshift.app.domain.MaxTransitionPolicy.keyboardReturn(
                            true, keyboardVisible(), SystemClock.elapsedRealtime() - p.listBackAt!!,
                            root.windowId == keyboard.window, h.singleOrNull() == keyboard.title && input == keyboard.editor,
                            h.singleOrNull()?.text?.toString() == keyboard.caption,
                            all.count { it.isVisibleToUser && it.isEditable }, draft.isEmpty(),
                            all.any { it.isVisibleToUser && it.isPassword })) {
                        p.keyboardReturn = null // consume before action; at most one additional Back
                        if (!backWithinMax()) { finish("BLOCKED", "MAX: не удалось выйти из чата после закрытия клавиатуры"); return }
                        main.postDelayed(tick, 400); return
                    }
                    if (SystemClock.elapsedRealtime() - p.listBackAt!! >= 4000) {
                        finish("BLOCKED", "MAX: после возврата общий список чатов не подтверждён. Поиск внутри переписки не запускается")
                    } else main.postDelayed(tick, 400)
                    return
                }
            }
            if (!p.edited && input != null && h.size == 1 && searchFields(all).isEmpty() &&
                MaxUiPolicy.phone(h.single().text.toString()) == null && !p.skipCurrentProfile && p.profile == null) {
                if (draft.isNotEmpty() && !clearOwnLeftover(p, draft, input)) {
                    finish("BLOCKED", "В чате есть черновик — CallShift не трогает чужие тексты. Удалите текст из поля сообщения в MAX вручную и повторите. Если поле выглядит пустым, MAX не обозначил подсказку как подсказку — пришлите отчёт кнопкой «Скопировать всё для поддержки»")
                    return
                }
                if (draft.isNotEmpty()) { main.postDelayed(tick, 400); return }
                openProfile(p, root, h.single(), input)
                return
            }
            if (!p.edited) {
                val correctChat = h.size == 1 && MaxSearchPolicy.equivalent(checkedRecipient(p, h.singleOrNull(), input), p.number)
                if (!correctChat || (p.query != null && p.selectedAt == null) || searchFields(all).isNotEmpty()) {
                    if (draft.isNotEmpty() && !clearOwnLeftover(p, draft, input!!)) {
                        finish("BLOCKED", "В открытом чате есть черновик — поиск не запускается. Удалите текст из поля сообщения в MAX вручную и повторите; поле CallShift не меняет")
                        return
                    }
                    if (draft.isNotEmpty()) { main.postDelayed(tick, 400); return }
                    if (p.query == null && !p.globalSearchConfirmed && input != null && h.size == 1 && searchFields(all).isEmpty()) {
                        returnToChatList(p, root, h.single(), input)
                    } else search(p, root, all)
                    return
                }
            }
            val reason = MaxUiPolicy.block(MaxUiPolicy.Check(store.enabled, unlocked(), root.packageName.toString(),
                store.version == version(), h.size, checkedRecipient(p, h.singleOrNull(), input), p.number,
                inputs.size, if (p.edited && draft == p.text) "" else draft))
            if (reason != null) { finish("BLOCKED", reason); return }
            if (p.dry) { finish("UI_CHECKED", "Проверка: номер в шапке или карточке и пустое поле подтверждены. Текст не введён, кнопка не нажата; работа кнопки отправки ещё не проверена"); return }
            if (!store.live || !app.settings.masterEnabled) { finish("BLOCKED", "Разрешение отправки или главный переключатель выключены"); return }
            if (!p.edited) {
                trace(MaxUiDiagnostics.Stage.INPUT, all)
                busy = true
                app.appScope.launch {
                    val reserved = runCatching { store.reserve() }.getOrDefault(false)
                    withContext(Dispatchers.Main) {
                        if (pending !== p) return@withContext
                        busy = false
                        if (!reserved) { finish("BLOCKED", "Лимит MAX: ${store.sendLimit()} попыток за 24 часа, либо хранилище недоступно"); return@withContext }
                        // Recheck fresh UI before writing; do not retain stale node references across IO.
                        p.edited = true
                        val fresh = rootInActiveWindow
                        if (fresh?.packageName?.toString() != MaxUiPolicy.PACKAGE || !unlocked() || !store.enabled || !store.live || !app.settings.masterEnabled) {
                            finish("BLOCKED", "Экран изменился до ввода"); return@withContext
                        }
                        val ns = nodes(fresh)
                        if (ns.any { it.isVisibleToUser && it.isPassword }) {
                            finish("BLOCKED", "MAX: экран пароля или защищённое поле; навигация и отправка запрещены"); return@withContext
                        }
                        val recipient = ns.filter { it.viewIdResourceName == store.header && header(it) }.singleOrNull()
                        val field = ns.filter { it.viewIdResourceName == store.input && it.isVisibleToUser && it.isEnabled && it.isEditable && !it.isPassword }.singleOrNull()
                        if (!MaxSearchPolicy.equivalent(checkedRecipient(p, recipient, field), p.number) || field == null || editableText(field).isNotEmpty()) {
                            finish("BLOCKED", "Получатель или черновик изменился до ввода"); return@withContext
                        }
                        val args = Bundle().apply { putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p.text) }
                        if (!field.performAction(Node.ACTION_SET_TEXT, args)) { finish("BLOCKED", "MAX не принял ввод текста"); return@withContext }
                        // The written text may outlive this run if the send never happens:
                        // remember its fingerprint so a later run recognizes and clears it.
                        ownDraft = MaxLeftoverPolicy.key(store.input, version(), p.text)
                        store.rememberLeftover(ownDraft!!)
                        main.postDelayed(tick, 500)
                    }
                }
                return
            }
            if (draft != p.text) { finish("BLOCKED", "Текст изменился; отправка остановлена"); return }
            val sendCandidates = sendButtons(all, input!!)
            val send = sendCandidates.singleOrNull() ?: learnedSend(all, input!!)
            if (send == null) {
                val now = SystemClock.elapsedRealtime()
                if (p.sendWaitAt == null) p.sendWaitAt = now
                if (sendCandidates.isEmpty() && learnedSend(all, input!!) == null && now - p.sendWaitAt!! < 1500) {
                    main.postDelayed(tick, 250); return // wait for composer animation, never write or click again
                }
                finish("BLOCKED", if (sendCandidates.isEmpty())
                    "MAX не передал доступное действие отправки, обученной кнопки нет. Черновик оставлен. НАЖМИТЕ «ОБУЧИТЬ КНОПКУ ОТПРАВКИ MAX» на экране CallShift — один раз, затем повторите тест"
                    else "MAX показал несколько действий отправки. Ничего не нажато; черновик оставлен")
                return
            }
            // Android can replace nodes after text input or while the keyboard opens.
            // Re-read recipient, draft, sender configuration and the exact action BEFORE clicking.
            val fresh = rootInActiveWindow
            val ns = fresh?.let(::nodes).orEmpty()
            val titles = ns.filter { it.viewIdResourceName == store.header && header(it) }
            val editors = ns.filter { it.isVisibleToUser && it.isEnabled && it.isEditable && !it.isPassword && it.viewIdResourceName == store.input }
            val field = editors.singleOrNull()
            val trainedGesture = store.learnedSend()?.gesture == true
            // A trained gesture arrow may expose no click action at all: the replay is a
            // real anchored tap, so clickability is not required - only the fresh identity.
            val sameButton = field != null && send.refresh() &&
                (if (trainedGesture) learnedSend(ns, field) == send
                else profileClickable(send) && (sendButton(ns, field) == send || learnedSend(ns, field) == send))
            val finalCheck = fi.callshift.app.domain.MaxTransitionPolicy.FinalClick(
                store.enabled && store.live && app.settings.masterEnabled, unlocked(),
                fresh?.packageName?.toString() == MaxUiPolicy.PACKAGE && fresh.windowId == root.windowId,
                routes.routes()[p.accountId] == p.route && p.accountId in app.telecom.phoneAccounts().keys,
                store.version == version(), p.number, checkedRecipient(p, titles.singleOrNull(), field),
                p.text, editableText(field), titles.size, editors.size,
                ns.count { it.isVisibleToUser && it.isEditable }, ns.any { it.isVisibleToUser && it.isPassword }, sameButton)
            if (!fi.callshift.app.domain.MaxTransitionPolicy.canClick(finalCheck)) {
                finish("BLOCKED", "MAX: перед отправкой изменились получатель, текст, кнопка или настройки. Нажатия не было; проверьте черновик"); return
            }
            trace(MaxUiDiagnostics.Stage.CLICK, ns)
            // Remove pending BEFORE clicking: no event, timeout or reconnection can retry.
            pending = null; main.removeCallbacks(tick); main.removeCallbacks(timeout)
            log(p.event, "UI_UNKNOWN", "Передано управление кнопке MAX. Результат неизвестен; повторов нет", durable = false)
            val learnedGesture = store.learnedSend()?.gesture == true && (field == null || sendButton(ns, field) != send)
            val accepted = if (learnedGesture) tapLearnedSend(send) else send.performAction(Node.ACTION_CLICK)
            if (accepted) {
                log(p.event, "UI_UNKNOWN", "Нажатие кнопки MAX передано. Проверяем результат по экрану; повторов нет")
                scheduleSendProof(p.event)
            } else {
                log(p.event, "UI_UNKNOWN", "Android не принял нажатие кнопки MAX. Проверьте чат вручную; повторов нет")
            }
        } catch (_: Exception) { finish("UI_UNKNOWN", "Сценарий MAX остановлен с неопределённым результатом. Проверьте чат; повторов нет") }
    }
    private fun sendButton(all: List<Node>, input: Node): Node? = sendButtons(all, input).singleOrNull()
    private fun sendButtons(all: List<Node>, input: Node): List<Node> {
        val inputBounds = android.graphics.Rect().also { input.getBoundsInScreen(it) }
        fun evidence(n: Node) = fi.callshift.app.domain.MaxSendActionPolicy.evidence(
            n.text?.toString(), n.contentDescription?.toString(), n.viewIdResourceName)
        fun near(n: Node): Boolean {
            val bounds = android.graphics.Rect().also { n.getBoundsInScreen(it) }
            return !bounds.isEmpty && kotlin.math.abs(bounds.centerY() - inputBounds.centerY()) <= (96 * resources.displayMetrics.density).toInt()
        }
        fun outsideHistory(node: Node): Boolean = outsideHistoryNode(node)
        val found = mutableSetOf<Node>()
        for (label in all.filter { it.isVisibleToUser && it.isEnabled && !it.isEditable && !it.isPassword && near(it) && evidence(it) && outsideHistory(it) }) {
            if (profileClickable(label)) {
                val descendants = nodes(label).filter { it != label && it.isVisibleToUser }
                if (descendants.none { it.isEditable || it.isPassword || profileClickable(it) && !evidence(it) }) found += label
                continue
            }
            var parent = label.parent
            repeat(3) {
                val node = parent ?: return@repeat
                if (!node.isVisibleToUser || !node.isEnabled || node.isEditable || node.isPassword || collection(node) || !near(node)) {
                    parent = null; return@repeat
                }
                if (profileClickable(node)) {
                    val children = nodes(node).filter { it != node && it.isVisibleToUser }
                    if (fi.callshift.app.domain.MaxSendActionPolicy.wrapperSafe(
                            children.count { it.isEditable || it.isPassword }, children.count(::profileClickable),
                            children.count { evidence(it) }) &&
                        // A labelled conflicting parent must not override its own semantics.
                        !fi.callshift.app.domain.MaxSendActionPolicy.conflict(node.text?.toString(), node.contentDescription?.toString(), node.viewIdResourceName)) found += node
                    parent = null
                } else parent = node.parent
            }
        }
        // Parent and child may both expose the same Send control. Prefer its sole
        // actionable child, never resolve two sibling send actions by their order.
        return found.filter { parent ->
            val actionable = nodes(parent).filter { it != parent && it.isVisibleToUser && profileClickable(it) }
            !fi.callshift.app.domain.MaxSendActionPolicy.redundantParent(actionable.size, actionable.singleOrNull() in found)
        }
    }
    private fun outsideHistoryNode(node: Node): Boolean {
        var current: Node? = node
        repeat(25) {
            val n = current ?: return true
            if (collection(n)) return false
            current = n.parent
        }
        return false
    }
    // ----- One-time training of the send control: a deliberate user tap proves the action.
    // Only the recognition shape is stored — never recipient, chat, text or coordinates. -----
    private val TRAIN_TEXT = "Тест обучения CallShift"
    private var sendTrainingUntil = 0L
    private var sendPhaseAt = 0L
    private var sendDraft: String? = null
    private var sendTapSeen = false
    private var sendTapAt = -1L
    private var sendTapChain: List<Pair<String, String>> = emptyList()
    private var sendTapSource = false
    private var sendEventUnresolved = false
    private var sendAutoTypePending = false
    private var sendAutoTypeTries = 0
    private var sendAutoTypeAt = 0L
    private var sendTrial: MaxSendTrainingPolicy.Rule? = null
    private data class SendSnapshot(val at: Long, val window: Int,
        val identities: List<MaxSendTrainingPolicy.Candidate>,
        val rightIdentities: List<MaxSendTrainingPolicy.Candidate>, val shape: String)
    private var sendSnapshot: SendSnapshot? = null
    private var sendArmedAt = 0L
    private fun sendState(state: MaxSendTrainingPolicy.Status) {
        val changed = sendTrainingStatus != state
        sendTrainingStatus = state
        diagnostics.sendTraining(state)
        if (changed) {
            runCatching { store.saveReport(diagnostics, MaxUiStore.ReportKind.LEARNING) }
            // The user is inside MAX: toasts are the only visible guidance.
            if (state == MaxSendTrainingPolicy.Status.VERIFY)
                android.widget.Toast.makeText(this, "Проверяем, что сообщение ушло", android.widget.Toast.LENGTH_SHORT).show()
        }
        sendPhaseAt = SystemClock.elapsedRealtime()
    }
    private fun endSendLearning(state: MaxSendTrainingPolicy.Status = MaxSendTrainingPolicy.Status.STOPPED) {
        if (sendTrainingUntil == 0L) return
        val returnToSetup = state != MaxSendTrainingPolicy.Status.STOPPED && unlocked() &&
            rootInActiveWindow?.packageName?.toString() == MaxUiPolicy.PACKAGE
        sendTrainingUntil = 0L; main.removeCallbacks(sendTick)
        sendSnapshot = null; sendTrial = null; sendDraft = null
        sendTapSeen = false; sendTapAt = -1L; sendTapChain = emptyList(); sendTapSource = false
        sendEventUnresolved = false
        sendAutoTypePending = false; sendAutoTypeTries = 0; sendAutoTypeAt = 0L
        sendArmedAt = 0L
        sendState(state)
        runCatching { store.sendOutcome(state) }
        runCatching { store.saveReport(diagnostics, MaxUiStore.ReportKind.LEARNING, durable = true) }
        if (state != MaxSendTrainingPolicy.Status.STOPPED)
            android.widget.Toast.makeText(this, state.explanation, android.widget.Toast.LENGTH_LONG).show()
        if (returnToSetup) runCatching {
            startActivity(Intent(this, MaxSimpleActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }
    private val sendTick = object : Runnable {
        override fun run() {
            if (sendTrainingUntil == 0L) return
            if (SystemClock.elapsedRealtime() >= sendTrainingUntil) { endSendLearning(MaxSendTrainingPolicy.Status.TIMEOUT); return }
            runCatching { sendTrainingStep() }.onFailure { endSendLearning(MaxSendTrainingPolicy.Status.ERROR) }
            if (sendTrainingUntil != 0L) main.postDelayed(this, 250)
        }
    }
    private fun sendTrainingStep() {
        val status = sendTrainingStatus
        if (status == MaxSendTrainingPolicy.Status.VERIFY && SystemClock.elapsedRealtime() - sendPhaseAt > 6000) {
            endSendLearning(MaxSendTrainingPolicy.Status.NOT_SENT); return
        }
        if (!unlocked() || store.version != version() || store.header.isEmpty() || store.input.isEmpty()) {
            endSendLearning(MaxSendTrainingPolicy.Status.STOPPED); return
        }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != MaxUiPolicy.PACKAGE) return
        val all = nodes(root)
        if (all.any { it.isVisibleToUser && it.isPassword }) { endSendLearning(MaxSendTrainingPolicy.Status.STOPPED); return }
        val editor = all.filter { it.viewIdResourceName == store.input && it.isVisibleToUser && it.isEnabled && it.isEditable && !it.isPassword }.singleOrNull()
        when (status) {
            MaxSendTrainingPolicy.Status.WAIT_CHAT -> if (editor != null) {
                sendState(MaxSendTrainingPolicy.Status.WAIT_TAP); sendAutoTypePending = true
            }
            MaxSendTrainingPolicy.Status.WAIT_TAP -> {
                // Keep the user's part to a single action: the service types the draft itself.
                if (sendAutoTypePending) {
                    // The draft counts only when the editor really shows it: MAX may
                    // ignore or clear a blind SET_TEXT while the composer assembles.
                    if (editor != null && editableText(editor) == TRAIN_TEXT) {
                        sendAutoTypePending = false
                        android.widget.Toast.makeText(this,
                            "Текст введён. Нажмите синюю стрелку отправки ОДИН раз (у вас 30 секунд)",
                            android.widget.Toast.LENGTH_LONG).show()
                        return
                    }
                    if (editor != null && !editor.text.isNullOrEmpty() && editableText(editor) != TRAIN_TEXT) {
                        sendAutoTypePending = false // the user typed their own draft
                        android.widget.Toast.makeText(this,
                            "Черновик распознан. Нажмите синюю стрелку отправки ОДИН раз",
                            android.widget.Toast.LENGTH_LONG).show()
                        return
                    }
                    // Plain SET_TEXT, no focusing: the proven real-send path enters this
                    // same editor without focus, and focusing keeps the hint visible.
                    val now2 = SystemClock.elapsedRealtime()
                    if (editor != null && now2 - sendAutoTypeAt >= 700) {
                        if (sendAutoTypeTries >= 8) {
                            sendAutoTypePending = false
                            android.widget.Toast.makeText(this,
                                "Не удалось ввести текст сам — введите любой текст вручную и нажмите синюю стрелку",
                                android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            sendAutoTypeTries++; sendAutoTypeAt = now2
                            val typed = runCatching {
                                val args = Bundle().apply { putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, TRAIN_TEXT) }
                                editor.performAction(Node.ACTION_SET_TEXT, args)
                            }.getOrDefault(false)
                            // An aborted training must not leave a draft that blocks every
                            // later run: remember its fingerprint like a stopped live run.
                            if (typed) store.rememberLeftover(MaxLeftoverPolicy.key(store.input, version(), TRAIN_TEXT))
                        }
                    }
                    return
                }
                // The composer can be rebuilt right after the message goes out; the armed
                // proof survives this briefly instead of dropping back to WAIT_CHAT.
                if (editor == null) {
                    val withinProof = sendArmedAt > 0 && SystemClock.elapsedRealtime() - sendArmedAt <= 30_000
                    if (withinProof) return
                    if (sendTapSeen && SystemClock.uptimeMillis() - sendTapAt > 2000)
                        endSendLearning(MaxSendTrainingPolicy.Status.SOURCE_MISSING)
                    else {
                        sendSnapshot = null; sendDraft = null; sendArmedAt = 0L
                        sendTapSeen = false; sendEventUnresolved = false
                        sendState(MaxSendTrainingPolicy.Status.WAIT_CHAT)
                    }
                    return
                }
                val draft = editableText(editor)
                if (draft.isEmpty()) {
                    val snap = sendSnapshot
                    val withinProof = sendArmedAt > 0 && SystemClock.elapsedRealtime() - sendArmedAt <= 30_000
                    if (snap != null && sendTapSeen && !sendEventUnresolved) {
                        resolveSendTap()
                        if (sendTrainingStatus != MaxSendTrainingPolicy.Status.WAIT_TAP) return // VERIFY reached
                    }
                    if (snap != null && withinProof && (sendEventUnresolved || !sendTapSeen)) {
                        // The outgoing message proves the send tap even when Android handed
                        // us no usable click source. Save only an unambiguous single
                        // right-half control of the live row: the paperclip drops out.
                        val only = snap.rightIdentities.singleOrNull() ?: rightSideSingle(all)
                        if (only != null) finishTrainingSave(MaxSendTrainingPolicy.Resolution(
                            MaxSendTrainingPolicy.ResolveReason.SOLE_CANDIDATE, only), snap)
                    }
                    if (sendTrainingStatus != MaxSendTrainingPolicy.Status.WAIT_TAP) return
                    if (snap == null) {
                        if (sendTapSeen && SystemClock.uptimeMillis() - sendTapAt > 2000)
                            endSendLearning(MaxSendTrainingPolicy.Status.SOURCE_MISSING)
                        else if (!withinProof && SystemClock.elapsedRealtime() - sendPhaseAt > 3000) {
                            sendTapSeen = false; sendEventUnresolved = false
                        }
                    }
                    return
                }
                if (sendDraft != draft) { sendDraft = draft; sendSnapshot = null; sendArmedAt = 0L; return } // arm only on a stable draft
                val candidates = sendRowButtons(all, editor)
                val shape = composerShape(all, editor)
                // MAX hides the arrow as soon as the draft leaves, so the proof must use
                // controls captured while the draft still exists: the send arrow is the
                // single actionable control in the RIGHT half of the composer row.
                val rights = candidates.filter { n ->
                    val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                    !b.isEmpty && b.centerX() >= resources.displayMetrics.widthPixels / 2 &&
                        !fi.callshift.app.domain.MaxSendActionPolicy.conflict(
                            n.text?.toString(), n.contentDescription?.toString(), n.viewIdResourceName)
                }.filter { m -> rights_noneAncestor(m, candidates) }
                    .let { listOfNotNull(rightmostDistinct(it)) }
                    .map { MaxSendTrainingPolicy.Candidate(it.viewIdResourceName.orEmpty(),
                        it.className?.toString().orEmpty(), profileClickable(it)) }
                sendSnapshot = if (candidates.isEmpty() || shape == null) null else
                    SendSnapshot(SystemClock.elapsedRealtime(), root.windowId,
                        candidates.map { MaxSendTrainingPolicy.Candidate(it.viewIdResourceName.orEmpty(),
                            it.className?.toString().orEmpty(), profileClickable(it)) }, rights, shape)
                sendArmedAt = if (sendSnapshot == null) 0L else SystemClock.elapsedRealtime()
                if (sendSnapshot == null && SystemClock.elapsedRealtime() - sendPhaseAt > 5000) {
                    endSendLearning(MaxSendTrainingPolicy.Status.NO_CANDIDATES); return
                }
                if (sendTapSeen && sendSnapshot != null) resolveSendTap()
            }
            MaxSendTrainingPolicy.Status.VERIFY -> {
                // The draft disappearing is the only accepted proof that the tap sent the message.
                if (editor != null && editableText(editor).isEmpty()) {
                    val trial = sendTrial
                    if (trial == null) endSendLearning(MaxSendTrainingPolicy.Status.ERROR)
                    else {
                        store.learnSend(trial)
                        store.forgetLeftover(MaxLeftoverPolicy.key(store.input, version(), TRAIN_TEXT))
                        endSendLearning(MaxSendTrainingPolicy.Status.SAVED)
                    }
                }
            }
            else -> {}
        }
    }
    private fun handleSendTrainingClick(event: AccessibilityEvent) {
        if (sendTrainingStatus != MaxSendTrainingPolicy.Status.WAIT_TAP) return
        // Clicks before the armed draft (opening chats, back) are navigation, not the send tap.
        if (sendSnapshot == null) return
        val now = SystemClock.uptimeMillis()
        if (sendTapSeen) {
            if (now - sendTapAt <= 700) return // duplicated event for one physical tap
            endSendLearning(MaxSendTrainingPolicy.Status.FOREIGN); return
        }
        val source = event.source
        sendTapAt = now
        sendTapChain = clickChain(source, event.className?.toString())
        sendTapSource = source != null
        sendEventUnresolved = false
        sendTapSeen = true
        resolveSendTap()
    }
    /** The pressed node followed by its ancestors, nearest first, as (id, class) pairs. */
    private fun clickChain(source: Node?, eventClass: String?): List<Pair<String, String>> {
        val chain = mutableListOf<Pair<String, String>>()
        if (source != null) {
            var current: Node? = source
            repeat(6) {
                val n = current ?: return chain
                chain += (n.viewIdResourceName ?: "") to n.className?.toString().orEmpty()
                current = n.parent
            }
            return chain
        }
        chain += "" to eventClass.orEmpty()
        return chain
    }
    private fun resolveSendTap() {
        val snap = sendSnapshot ?: return // the tick rebuilds a fresh snapshot, then resolves
        val resolution = MaxSendTrainingPolicy.resolve(snap.identities, sendTapChain, sendTapSource)
        if (resolution.candidate == null) {
            // An unusable source is not a verdict: the vanish proof below may still
            // identify the control once the message demonstrably went out.
            sendEventUnresolved = true
            return
        }
        finishTrainingSave(resolution, snap)
    }
    private fun finishTrainingSave(resolution: MaxSendTrainingPolicy.Resolution, snap: SendSnapshot) {
        val trial = MaxSendTrainingPolicy.rule(version(), store.header, store.input, resolution, snap.shape)
        if (trial == null) { endSendLearning(MaxSendTrainingPolicy.Status.ERROR); return }
        sendTrial = trial
        sendState(MaxSendTrainingPolicy.Status.VERIFY)
    }
    /** With the outgoing message proving the tap, the send arrow is the single
     * actionable control in the RIGHT half of the composer row. The paperclip and
     * other left-side tools drop out; anything still ambiguous stays unsaved. */
    private fun rightSideSingle(all: List<Node>): MaxSendTrainingPolicy.Candidate? {
        val editor = all.filter { it.viewIdResourceName == store.input && it.isVisibleToUser && it.isEnabled && it.isEditable && !it.isPassword }.singleOrNull()
            ?: return null
        val half = resources.displayMetrics.widthPixels / 2
        val right = sendRowButtons(all, editor).filter { n ->
            fi.callshift.app.domain.MaxSendActionPolicy.conflict(
                n.text?.toString(), n.contentDescription?.toString(), n.viewIdResourceName).not() &&
                run { val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }; !b.isEmpty && b.centerX() >= half }
        }
        val node = right.filter { m -> right.none { other -> other != m && isNodeAncestor(other, m) } }.singleOrNull()
            ?: return null
        return MaxSendTrainingPolicy.Candidate(node.viewIdResourceName.orEmpty(), node.className?.toString().orEmpty(), true)
    }
    private fun rights_noneAncestor(m: Node, pool: List<Node>): Boolean =
        pool.none { o -> o != m && isNodeAncestor(o, m) }
    /** The send arrow is the distinctly rightmost control of the row: the paperclip
     * and other tools sit clearly to its left. Refuse when two controls crowd the
     * same right edge — ordering without separation is never guessed. */
    private fun rightmostDistinct(candidates: List<Node>): Node? {
        fun centerX(n: Node) = android.graphics.Rect().also { n.getBoundsInScreen(it) }.centerX()
        val ordered = candidates.sortedByDescending(::centerX)
        val best = ordered.firstOrNull() ?: return null
        val second = ordered.getOrNull(1) ?: return best
        return if (centerX(best) - centerX(second) >= (16 * resources.displayMetrics.density).toInt()) best else null
    }
    /** Composer-scoped: the control shares a close ancestor with the editor, so the
     * scrollable message list and its bubbles never enter the candidate set. */
    private fun inComposerScope(n: Node, editor: Node): Boolean {
        val ancestors = HashSet<Node>()
        var current: Node? = editor.parent
        var up = 0
        while (current != null && up < 8) { ancestors.add(current); current = current.parent; up++ }
        current = n
        var down = 0
        while (current != null && down < 8) {
            if (ancestors.contains(current)) return true
            current = current.parent; down++
        }
        return false
    }
    /** Composer-row controls. MAX may expose the arrow without a click action or
     * inside a scrolled container, so clickability and history ancestry are not
     * required: scope plus the editor's vertical band decide. */
    private fun sendRowButtons(all: List<Node>, editor: Node): List<Node> =
        all.filter { n -> n != editor && n.isVisibleToUser && n.isEnabled && !n.isEditable && !n.isPassword &&
            !collection(n) && inComposerScope(n, editor) && nearComposerRow(n, editor) }
    private fun nearComposerRow(n: Node, editor: Node): Boolean {
        val bounds = android.graphics.Rect().also { n.getBoundsInScreen(it) }
        val inputBounds = android.graphics.Rect().also { editor.getBoundsInScreen(it) }
        if (bounds.isEmpty || inputBounds.isEmpty) return false
        return kotlin.math.abs(bounds.centerY() - inputBounds.centerY()) <= (96 * resources.displayMetrics.density).toInt()
    }
    /** Stable composer fingerprint: which controls and classes share the row with the editor. */
    private fun composerShape(all: List<Node>, editor: Node): String? {
        val band = all.filter { it.isVisibleToUser && nearComposerRow(it, editor) }
        if (band.isEmpty() || band.size >= 60) return null
        val shapeSource = band.map {
            "${it.viewIdResourceName}|${it.className}|${it.childCount}|${it.isClickable}|${it.actionList.any { a -> a.id == Node.ACTION_CLICK }}"
        }.sorted().joinToString("\n")
        return java.security.MessageDigest.getInstance("SHA-256").digest(shapeSource.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    /** The trained send control in the live tree; exactly one identity match in the composer row.
     * The stored shape is informational: keyboard state changes the band census, so replay
     * relies on the trained identity plus the strict context guards instead. */
    private fun learnedSend(all: List<Node>, input: Node?): Node? {
        if (input == null) return null
        val rule = store.learnedSend() ?: return null
        if (!MaxSendTrainingPolicy.replayable(rule, version(), store.header, store.input)) return null
        val inputBounds = android.graphics.Rect().also { input.getBoundsInScreen(it) }
        fun near(n: Node): Boolean {
            val bounds = android.graphics.Rect().also { n.getBoundsInScreen(it) }
            return !bounds.isEmpty && !inputBounds.isEmpty &&
                kotlin.math.abs(bounds.centerY() - inputBounds.centerY()) <= (96 * resources.displayMetrics.density).toInt()
        }
        val half = resources.displayMetrics.widthPixels / 2
        val matches = all.filter { n ->
            n != input && n.isVisibleToUser && n.isEnabled && !n.isEditable && !n.isPassword &&
                (rule.gesture || profileClickable(n)) && inComposerScope(n, input) && near(n) &&
                // Gesture rules were proven on the right-half arrow; resolve them there too.
                (!rule.gesture || run { val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }; !b.isEmpty && b.centerX() >= half }) &&
                MaxSendTrainingPolicy.identityMatches(rule.targetId, rule.targetClass, n.viewIdResourceName, n.className?.toString()) &&
                !fi.callshift.app.domain.MaxSendActionPolicy.conflict(n.text?.toString(), n.contentDescription?.toString(), n.viewIdResourceName)
        }
        // A wrapper and its sole actionable child may expose one control; the descendant acts.
        val deduped = matches.filter { m -> matches.none { other -> other != m && isNodeAncestor(other, m) } }
        return if (deduped.size == 1) deduped.single() else rightmostDistinct(deduped)
    }
    private fun isNodeAncestor(ancestor: Node, node: Node): Boolean {
        var current: Node? = node
        repeat(25) { current = current?.parent ?: return false; if (current == ancestor) return true }
        return false
    }
    /** A real anchored tap on the freshly located trained button: the same consented
     * mechanism as the title gesture. If the saved target is a container, the tap goes
     * to the center of its distinctly-rightmost child, not to the container center. */
    private fun tapLearnedSend(target: Node): Boolean {
        val accessibility = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (accessibility == null || accessibility.isTouchExplorationEnabled) return false
        val scale = runCatching {
            if (Build.VERSION.SDK_INT >= 33) magnificationController.magnificationConfig?.scale
            else magnificationController.scale
        }.getOrNull()
        if (scale != 1f) return false
        if (!target.refresh() || !target.isVisibleToUser || !target.isEnabled) return false
        val bounds = android.graphics.Rect(); target.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val children = nodes(target).filter { it != target && it.isVisibleToUser && it.isEnabled && !it.isEditable && !it.isPassword }
            .filter { !collection(it) }
        val point = rightmostDistinct(children)?.let { child ->
            val cb = android.graphics.Rect(); child.getBoundsInScreen(cb)
            if (cb.isEmpty || !bounds.contains(cb)) null
            else android.graphics.Point(cb.centerX(), cb.centerY())
        } ?: android.graphics.Point(bounds.centerX(), bounds.centerY())
        val foreground = windows.singleOrNull { it.id == target.windowId && it.isFocused &&
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION } ?: return false
        if (Build.VERSION.SDK_INT >= 30 && foreground.displayId != android.view.Display.DEFAULT_DISPLAY) return false
        val path = android.graphics.Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(
            android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)).build()
        val token = gestureFlight.begin(SystemClock.elapsedRealtime()) ?: return false
        val submitted = runCatching { dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(description: android.accessibilityservice.GestureDescription) { gestureFlight.resolve(token) }
            override fun onCancelled(description: android.accessibilityservice.GestureDescription) { gestureFlight.resolve(token) }
        }, main) }.getOrDefault(false)
        if (!submitted) gestureFlight.resolve(token)
        return submitted
    }
    /** Read-only post-click proof: the draft vanishing from the editor is the same
     * evidence training uses. No further actions, no retries - the result text only. */
    private var sendProofEvent: CallEvent? = null
    private var sendProofChecks = 0
    private fun scheduleSendProof(event: CallEvent) {
        sendProofEvent = event; sendProofChecks = 0
        main.postDelayed(sendProofTick, 2000)
    }
    private val sendProofTick = object : Runnable {
        override fun run() {
            val event = sendProofEvent ?: return
            val editor = rootInActiveWindow
                ?.takeIf { unlocked() && it.packageName?.toString() == MaxUiPolicy.PACKAGE }
                ?.let(::nodes)
                ?.filter { it.viewIdResourceName == store.input && it.isVisibleToUser && it.isEditable && !it.isPassword }
                ?.singleOrNull()
            if (editor == null) {
                sendProofEvent = null
                log(event, "UI_UNKNOWN", "Нажатие выполнено; подтвердить результат по экрану не удалось. Проверьте сообщение у получателя; повторов нет")
                return
            }
            if (editableText(editor).isEmpty()) {
                sendProofEvent = null
                // The message left the composer: nothing of ours remains in MAX.
                ownDraft?.let { store.forgetLeftover(it) }
                ownDraft = null
                log(event, "UI_SENT_LOCAL", "Нажатие выполнено; текст исчез из поля — сообщение предположительно отправлено. Доставку подтвердите у получателя; повторов нет")
                return
            }
            sendProofChecks++
            if (sendProofChecks >= 3) {
                sendProofEvent = null
                ownDraft = null // the draft stayed: the fingerprint must remain registered
                log(event, "UI_UNKNOWN", "Нажатие выполнено; текст остался в поле — вероятно, сообщение не ушло. Проверьте чат вручную; повторов нет")
                return
            }
            main.postDelayed(this, 2000)
        }
    }
    /** One fresh, explicitly learned system choice per call. Never click twice or use list order. */
    private fun routeReady(p: Pending, root: Node): Boolean {
        if (p.routeReady) return true
        val now = SystemClock.elapsedRealtime()
        if (now - p.started > 8000) {
            finish("BLOCKED", "MAX: не удалось открыть выбранный аккаунт за 8 секунд. Окно выбора недоступно или не поддерживается"); return false
        }
        trace(MaxUiDiagnostics.Stage.ROUTE_WAIT)
        fun waitUi(): Boolean { main.removeCallbacks(tick); main.postDelayed(tick, 500); return false }
        val inMax = root.packageName?.toString() == MaxUiPolicy.PACKAGE
        val expected = p.route.picker
        if (expected == null) {
            if (inMax) { p.routeReady = true; trace(MaxUiDiagnostics.Stage.ROUTE_READY); return true }
            if (MaxSystemPicker.read(this, root) != null) {
                finish("BLOCKED", "MAX: обнаружено окно двух копий. Настройте аккаунт отправителя по SIM вместо режима одного MAX"); return false
            }
            return waitUi()
        }
        if (p.routeClickedAt != null) {
            if (inMax && now - p.routeClickedAt!! >= 700) {
                p.routeReady = true; trace(MaxUiDiagnostics.Stage.ROUTE_READY); return true
            }
            return waitUi()
        }
        if (inMax) {
            finish("BLOCKED", "MAX: окно выбора копии не появилось. Аккаунт не подтверждён; отключите выбор MAX по умолчанию в настройках клонирования"); return false
        }
        val inspection = MaxSystemPicker.inspect(this, root)
        diagnostics.recordPicker(inspection.report)
        val actual = inspection.snapshot ?: return waitUi()
        val target = p.route.label
        if (target == null || !MaxRoutePolicy.matches(expected, actual.picker, target)) {
            finish("BLOCKED", "MAX: системное окно выбора изменилось. Повторите изучение окна и настройку маршрута SIM"); return false
        }
        val button = actual.buttons[target]
        val fresh = pickerRoot()
        if (!unlocked() || !store.enabled || !app.settings.masterEnabled || fresh == null ||
            fresh.windowId != root.windowId || fresh.packageName?.toString() != root.packageName?.toString() ||
            button == null || !button.refresh() || !button.isVisibleToUser || !button.isEnabled || !button.isClickable) {
            finish("BLOCKED", "MAX: системное окно выбора изменилось. Повторите изучение окна и настройку маршрута SIM"); return false
        }
        trace(MaxUiDiagnostics.Stage.ROUTE_PICK)
        // Mark BEFORE action: uncertain result must never lead to a second click.
        p.routeClickedAt = now
        if (!button.performAction(Node.ACTION_CLICK)) {
            finish("BLOCKED", "MAX: система не приняла выбор аккаунта; повторного нажатия не будет"); return false
        }
        return waitUi()
    }
    private fun checkedRecipient(p: Pending, title: Node?, editor: Node?): String? {
        val direct = title?.text?.toString()?.let(MaxUiPolicy::phone)
        if (direct != null) return direct
        val proof = p.profile ?: return null
        if (!proof.verified || proof.returnedAt == null || SystemClock.elapsedRealtime() - proof.returnedAt!! > 10_000) return null
        // Phone proof and the controlled return bind fresh nodes. Never transfer proof
        // again if those nodes are replaced after validation or after entering text.
        return p.number.takeIf { title != null && title == proof.returnedTitle && editor == proof.returnedEditor &&
            title.windowId == proof.window && title.text?.toString() == proof.caption }
    }
    private fun profileClickable(n: Node): Boolean = n.isEnabled && n.isVisibleToUser && !n.isEditable && !n.isPassword &&
        MaxProfileActionPolicy.supportsClick(n.isClickable, n.actionList.any { it.id == Node.ACTION_CLICK })
    private fun toolbar(n: Node): Boolean = n.className?.toString()?.endsWith("Toolbar") == true ||
        n.viewIdResourceName?.contains("toolbar", ignoreCase = true) == true
    private fun under(n: Node, parent: Node): Boolean {
        var current: Node? = n
        repeat(20) {
            val c = current ?: return false
            if (c == parent) return true
            if (collection(c)) return false
            current = c.parent
        }
        return false
    }
    private data class CardSnapshot(val at: Long, val window: Int, val title: Node, val editor: Node,
        val caption: String, val targets: Map<Node, MaxCardLearningPolicy.Rule>)
    private var cardUntil = 0L
    private var gestureTraining = false
    private var gestureReadyAt = 0L
    private var gestureReadyPoint: fi.callshift.app.domain.MaxHeaderGesturePolicy.Point? = null
    private var gestureEventAt = -1L
    private var gestureEventPending = false
    private var cardGeneration = 0L
    private val gestureFlight = fi.callshift.app.domain.MaxGestureFlight()
    private val headerGestureBusy get() = gestureFlight.busy
    private var cardPhaseAt = 0L
    private var cardSnapshot: CardSnapshot? = null
    private var cardTrial: MaxCardLearningPolicy.Rule? = null
    private val cardProof = fi.callshift.app.domain.MaxCardReplayProof()
    private var replayNode: Node? = null
    private var replayEventExpected = false
    private fun learningState(state: MaxCardLearningPolicy.Status) {
        val changed = cardLearningStatus != state
        cardLearningStatus = state
        diagnostics.cardLearning(state)
        if (changed) runCatching { store.saveReport(diagnostics, MaxUiStore.ReportKind.LEARNING) }
        cardPhaseAt = SystemClock.elapsedRealtime()
    }
    private fun endCardLearning(state: MaxCardLearningPolicy.Status = MaxCardLearningPolicy.Status.STOPPED) {
        if (cardUntil == 0L) return
        val returnToSetup = gestureTraining && !headerGestureBusy && state != MaxCardLearningPolicy.Status.STOPPED && unlocked() &&
            rootInActiveWindow?.packageName?.toString() == MaxUiPolicy.PACKAGE
        cardUntil = 0L; cardGeneration++; main.removeCallbacks(cardTick)
        gestureTraining = false; gestureReadyAt = 0L; gestureReadyPoint = null; gestureEventPending = false; gestureEventAt = -1L
        cardSnapshot = null; cardTrial = null; cardProof.reset(); replayNode = null; replayEventExpected = false
        val finalState = if (state in setOf(MaxCardLearningPolicy.Status.STOPPED, MaxCardLearningPolicy.Status.TIMEOUT) &&
            cardLearningStatus in setOf(MaxCardLearningPolicy.Status.NO_TARGETS, MaxCardLearningPolicy.Status.GESTURE_AREA_BLOCKED)) cardLearningStatus else state
        learningState(finalState)
        runCatching { store.cardOutcome(finalState) }
        runCatching { store.saveReport(diagnostics, MaxUiStore.ReportKind.LEARNING, durable = true) }
        if (finalState != MaxCardLearningPolicy.Status.STOPPED)
            android.widget.Toast.makeText(this, finalState.explanation, android.widget.Toast.LENGTH_LONG).show()
        if (returnToSetup) runCatching {
            startActivity(Intent(this, MaxSimpleActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }
    private fun cardToolbar(title: Node): Node? {
        var current = title.parent
        repeat(20) {
            val n = current ?: return null
            if (collection(n)) return null
            if (toolbar(n)) return n
            current = n.parent
        }
        return null
    }
    private fun toolbarShape(ns: List<Node>): String {
        val shapeSource = ns.map {
            "${it.className}|${it.viewIdResourceName}|${it.childCount}|${it.isClickable}|${it.actionList.any { a -> a.id == Node.ACTION_CLICK }}"
        }.sorted().joinToString("\n")
        return java.security.MessageDigest.getInstance("SHA-256").digest(shapeSource.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun cardRules(title: Node): Map<Node, MaxCardLearningPolicy.Rule> {
        val bar = cardToolbar(title) ?: return emptyMap()
        val ns = nodes(bar)
        if (ns.size >= 120) return emptyMap()
        val shape = toolbarShape(ns)
        return ns.filter { n ->
            val id = n.viewIdResourceName.orEmpty()
            profileClickable(n) && !collection(n) && under(n, bar) &&
                id.startsWith(MaxUiPolicy.PACKAGE + ":id/") &&
                !Regex("(^|_)(call|video|back|more|menu)(_|$)").containsMatchIn(id.substringAfter('/').lowercase(java.util.Locale.ROOT)) &&
                ns.count { it.viewIdResourceName == id } == 1
        }.associateWith { n -> MaxCardLearningPolicy.Rule(version(), store.header, store.input,
            n.viewIdResourceName, n.className?.toString().orEmpty(), shape) }
    }
    private fun titleGestureRule(title: Node): MaxCardLearningPolicy.Rule? {
        if (!header(title) || !title.isEnabled || title.viewIdResourceName != store.header) return null
        val bar = cardToolbar(title) ?: return null
        val ns = nodes(bar)
        if (ns.size >= 120 || ns.count { it.viewIdResourceName == store.header } != 1) return null
        return MaxCardLearningPolicy.Rule(version(), store.header, store.input, store.header,
            title.className?.toString().orEmpty(), toolbarShape(ns), gesture = true)
    }
    private fun learnedCardButton(title: Node, rule: MaxCardLearningPolicy.Rule): Node? =
        if (rule.gesture) titleGestureRule(title)?.takeIf { MaxCardLearningPolicy.matches(rule, it) }?.let { title }
        else cardRules(title).entries.filter { MaxCardLearningPolicy.matches(rule, it.value) }.singleOrNull()?.key

    private data class HeaderTap(val title: Node, val editor: Node, val rule: MaxCardLearningPolicy.Rule,
        val window: Int, val point: fi.callshift.app.domain.MaxHeaderGesturePolicy.Point)
    private fun titleTap(root: Node): HeaderTap? {
        // Gesture coordinates can be transformed by zoom or touch exploration. Do not
        // assume their mapping, and never target a secondary display with a default-display tap.
        val accessibility = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (accessibility == null || accessibility.isTouchExplorationEnabled) return null
        val scale = runCatching {
            if (Build.VERSION.SDK_INT >= 33) magnificationController.magnificationConfig?.scale
            else magnificationController.scale
        }.getOrNull()
        if (scale != 1f) return null
        if (!unlocked() || store.version != version() || root.packageName?.toString() != MaxUiPolicy.PACKAGE) return null
        val all = nodes(root)
        if (all.size >= 600 || all.any { it.isVisibleToUser && it.isPassword } || all.count { it.isVisibleToUser && it.isEditable } != 1) return null
        val title = all.filter { it.viewIdResourceName == store.header && header(it) }.singleOrNull() ?: return null
        val editor = all.filter { it.viewIdResourceName == store.input && it.isVisibleToUser && it.isEnabled && it.isEditable && !it.isPassword }.singleOrNull() ?: return null
        if (editableText(editor).isNotEmpty()) return null
        val rule = titleGestureRule(title) ?: return null
        val foreground = windows.singleOrNull { it.id == root.windowId && it.isFocused &&
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION } ?: return null
        if (Build.VERSION.SDK_INT >= 30 && foreground.displayId != android.view.Display.DEFAULT_DISPLAY) return null
        fun rect(r: android.graphics.Rect) = fi.callshift.app.domain.MaxHeaderGesturePolicy.Rect(r.left, r.top, r.right, r.bottom)
        fun bounds(n: Node) = rect(android.graphics.Rect().also { n.getBoundsInScreen(it) })
        val obstacles = all.filter { n -> n != title && n.isVisibleToUser && n.isEnabled &&
            (n.isClickable || n.actionList.any { it.id == Node.ACTION_CLICK }) &&
            (!under(title, n) || Regex("(^|_)(call|video|back|more|menu|delete|block)(_|$)")
                .containsMatchIn(n.viewIdResourceName.orEmpty().substringAfter('/').lowercase(java.util.Locale.ROOT))) }.map(::bounds) +
            windows.filter { it.id != foreground.id && it.layer >= foreground.layer }
                .map { w -> rect(android.graphics.Rect().also { w.getBoundsInScreen(it) }) }
        val point = fi.callshift.app.domain.MaxHeaderGesturePolicy.point(bounds(title),
            rect(android.graphics.Rect().also { foreground.getBoundsInScreen(it) }), resources.displayMetrics.density, obstacles) ?: return null
        return HeaderTap(title, editor, rule, root.windowId, point)
    }
    private fun tapTitle(rule: MaxCardLearningPolicy.Rule, window: Int, title: Node, editor: Node,
        beforeDispatch: () -> Unit = {}, failure: () -> Unit): Boolean {
        if (!rule.gesture || headerGestureBusy) return false
        val fresh = rootInActiveWindow ?: return false
        val tap = titleTap(fresh) ?: return false
        if (tap.window != window || tap.title != title || tap.editor != editor || !MaxCardLearningPolicy.matches(rule, tap.rule)) return false
        val path = android.graphics.Path().apply { moveTo(tap.point.x, tap.point.y) }
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(
            android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)).build()
        val token = gestureFlight.begin(SystemClock.elapsedRealtime()) ?: return false
        beforeDispatch()
        val submitted = runCatching { dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(description: android.accessibilityservice.GestureDescription) {
                if (gestureFlight.resolve(token) && pending != null) {
                    main.removeCallbacks(tick); main.post(tick)
                }
            }
            override fun onCancelled(description: android.accessibilityservice.GestureDescription) {
                if (gestureFlight.resolve(token)) failure()
            }
        }, main) }.getOrDefault(false)
        if (!submitted) gestureFlight.resolve(token)
        return submitted
    }
    private fun learnCardClick(event: AccessibilityEvent) {
        if (cardUntil == 0L || event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val status = cardLearningStatus
        if (gestureTraining && gestureEventPending && fi.callshift.app.domain.MaxHeaderGesturePolicy.expectedEvent(
                SystemClock.uptimeMillis(), gestureEventAt, event.eventTime, cardSnapshot?.window ?: -1, event.windowId)) {
            gestureEventPending = false; return // injected tap acknowledgement, source is deliberately not needed
        }
        if (status in setOf(MaxCardLearningPolicy.Status.WAIT_CHAT, MaxCardLearningPolicy.Status.NO_TARGETS, MaxCardLearningPolicy.Status.GESTURE_AREA_BLOCKED)) return
        val source = event.source
        if (status in setOf(MaxCardLearningPolicy.Status.REPLAY, MaxCardLearningPolicy.Status.FINAL_RETURN) && replayEventExpected &&
            (source != null && source == replayNode || source == null && replayNode?.let {
                event.windowId == it.windowId && event.className?.toString() == it.className?.toString() &&
                    MaxCardLearningPolicy.recoverMissingSource(SystemClock.elapsedRealtime(), cardPhaseAt,
                        it.windowId, event.windowId, event.className?.toString(),
                        cardSnapshot?.targets?.values.orEmpty()) == cardTrial
            } == true)) {
            replayEventExpected = false; return
        }
        if (status != MaxCardLearningPolicy.Status.WAIT_TAP) {
            endCardLearning(MaxCardLearningPolicy.Status.EXTRA_CLICK); return
        }
        val cached = cardSnapshot
        val selected = if (source != null) cached?.targets?.get(source) else cached?.let {
            MaxCardLearningPolicy.recovery(SystemClock.elapsedRealtime(), it.at,
                it.window, event.windowId, event.className?.toString(), it.targets.values).also { recovery ->
                    diagnostics.sourceRecovery(recovery.reason)
                }.rule
        }
        if (source == null && selected == null) { endCardLearning(MaxCardLearningPolicy.Status.SOURCE_MISSING); return }
        if (cached == null || selected == null || (source?.windowId ?: event.windowId) != cached.window ||
            !MaxCardLearningPolicy.fresh(SystemClock.elapsedRealtime(), cached.at)) {
            endCardLearning(MaxCardLearningPolicy.Status.SOURCE_REJECTED); return
        }
        // With no source node, reject whole toolbar containers and composite
        // controls: a class/resource hint must not turn into a call/menu click.
        if (source == null) {
            val candidate = cached.targets.entries.singleOrNull { it.value == selected }?.key
            if (candidate == null || toolbar(candidate) || nodes(candidate).any { it != candidate && profileClickable(it) }) {
                endCardLearning(MaxCardLearningPolicy.Status.SOURCE_REJECTED); return
            }
        }
        cardTrial = selected
        learningState(MaxCardLearningPolicy.Status.WAIT_CARD)
    }
    private val cardTick = object : Runnable {
        override fun run() {
            if (cardUntil == 0L) return
            if (SystemClock.elapsedRealtime() >= cardUntil) { endCardLearning(MaxCardLearningPolicy.Status.TIMEOUT); return }
            runCatching { cardLearningStep() }.onFailure { endCardLearning(MaxCardLearningPolicy.Status.ERROR) }
            if (cardUntil != 0L) main.postDelayed(this, 250)
        }
    }
    private fun cardLearningStep() {
        // Never navigate Back or inspect/save the outcome before dispatchGesture completes.
        if (headerGestureBusy) {
            if (gestureFlight.overdue(SystemClock.elapsedRealtime())) endCardLearning(MaxCardLearningPolicy.Status.GESTURE_PENDING)
            return
        }
        val status = cardLearningStatus
        val observing = status in setOf(MaxCardLearningPolicy.Status.WAIT_CHAT, MaxCardLearningPolicy.Status.WAIT_TAP, MaxCardLearningPolicy.Status.NO_TARGETS, MaxCardLearningPolicy.Status.GESTURE_AREA_BLOCKED)
        if (!observing && SystemClock.elapsedRealtime() - cardPhaseAt > 6000) {
            endCardLearning(if (status in setOf(MaxCardLearningPolicy.Status.WAIT_CARD, MaxCardLearningPolicy.Status.REPLAY))
                MaxCardLearningPolicy.Status.CARD_UNVERIFIED else MaxCardLearningPolicy.Status.RETURN_UNVERIFIED); return
        }
        if (!unlocked() || store.version != version()) { endCardLearning(MaxCardLearningPolicy.Status.STOPPED); return }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != MaxUiPolicy.PACKAGE) return
        val all = nodes(root)
        if (all.any { it.isVisibleToUser && it.isPassword }) { endCardLearning(MaxCardLearningPolicy.Status.STOPPED); return }
        val title = all.filter { it.viewIdResourceName == store.header && header(it) }.singleOrNull()
        val editor = all.filter { it.isVisibleToUser && it.isEnabled && it.isEditable && it.viewIdResourceName == store.input }.singleOrNull()
        if (observing) {
            if (title == null || editor == null || editableText(editor).isNotEmpty() || all.count { it.isVisibleToUser && it.isEditable } != 1) {
                // Window-content events can arrive before the click event. Retain the
                // pre-click snapshot briefly, never infer the action from the new screen.
                val cached = cardSnapshot
                if (cached != null && MaxCardLearningPolicy.fresh(SystemClock.elapsedRealtime(), cached.at)) return
                cardSnapshot = null; gestureReadyAt = 0L; learningState(MaxCardLearningPolicy.Status.WAIT_CHAT); return
            }
            if (gestureTraining) {
                val tap = titleTap(root)
                if (tap == null) { gestureReadyAt = 0L; learningState(MaxCardLearningPolicy.Status.GESTURE_AREA_BLOCKED); return }
                val previous = cardSnapshot
                val now = SystemClock.elapsedRealtime()
                if (previous == null || previous.title != title || previous.editor != editor || previous.window != root.windowId ||
                    previous.caption != title.text.toString() || cardTrial != tap.rule || gestureReadyPoint != tap.point) gestureReadyAt = now
                gestureReadyPoint = tap.point
                cardTrial = tap.rule
                cardSnapshot = CardSnapshot(now, root.windowId, title, editor, title.text.toString(), emptyMap())
                if (gestureReadyAt == 0L) gestureReadyAt = now
                if (now - gestureReadyAt < 1000) return
                gestureEventPending = true; gestureEventAt = SystemClock.uptimeMillis()
                val generation = cardGeneration
                learningState(MaxCardLearningPolicy.Status.WAIT_CARD)
                if (!tapTitle(tap.rule, root.windowId, title, editor) {
                        if (cardGeneration == generation && cardUntil != 0L) endCardLearning(MaxCardLearningPolicy.Status.REPLAY_FAILED)
                    }) endCardLearning(MaxCardLearningPolicy.Status.REPLAY_FAILED)
                return
            }
            val targets = cardRules(title)
            cardSnapshot = CardSnapshot(SystemClock.elapsedRealtime(), root.windowId, title, editor, title.text.toString(), targets)
            learningState(if (targets.isEmpty()) MaxCardLearningPolicy.Status.NO_TARGETS else MaxCardLearningPolicy.Status.WAIT_TAP)
            return
        }
        if (status == MaxCardLearningPolicy.Status.WAIT_CARD || status == MaxCardLearningPolicy.Status.REPLAY) {
            if (all.any { it.isVisibleToUser && it.isEditable }) return
            // Use exactly the same labelled section as normal recipient verification.
            // A phone elsewhere in the profile is neither evidence nor a reason to reject this field.
            val phone = readProfilePhone(all).phone ?: return
            val accepted = if (status == MaxCardLearningPolicy.Status.WAIT_CARD) cardProof.manualCard(phone)
                else cardProof.replayCard(phone)
            if (!accepted) { endCardLearning(MaxCardLearningPolicy.Status.CARD_UNVERIFIED); return }
            learningState(if (status == MaxCardLearningPolicy.Status.WAIT_CARD) MaxCardLearningPolicy.Status.RETURNING else MaxCardLearningPolicy.Status.FINAL_RETURN)
            if (!backWithinMax()) endCardLearning(MaxCardLearningPolicy.Status.RETURN_UNVERIFIED)
            return
        }
        val original = cardSnapshot ?: run { endCardLearning(MaxCardLearningPolicy.Status.ERROR); return }
        if (title == null || editor == null) return
        if (!MaxCardLearningPolicy.trainingReturn(root.windowId == original.window,
                title.className == original.title.className, editor.className == original.editor.className,
                title.text?.toString() == original.caption, all.count { it.isVisibleToUser && it.isEditable }, editableText(editor).isEmpty())) {
            endCardLearning(MaxCardLearningPolicy.Status.RETURN_UNVERIFIED); return
        }
        val rule = cardTrial ?: run { endCardLearning(MaxCardLearningPolicy.Status.ERROR); return }
        val target = learnedCardButton(title, rule) ?: run { endCardLearning(MaxCardLearningPolicy.Status.SOURCE_REJECTED); return }
        if (status == MaxCardLearningPolicy.Status.FINAL_RETURN) {
            if (!cardProof.returned() || !cardProof.canSave()) {
                endCardLearning(MaxCardLearningPolicy.Status.RETURN_UNVERIFIED); return
            }
            store.learnCard(rule)
            endCardLearning(MaxCardLearningPolicy.Status.SAVED)
        } else if (status == MaxCardLearningPolicy.Status.RETURNING) {
            val fresh = rootInActiveWindow
            if (!unlocked() || fresh == null || fresh.packageName?.toString() != MaxUiPolicy.PACKAGE || fresh.windowId != original.window ||
                !target.refresh() || (!rule.gesture && !profileClickable(target)) || !editor.refresh() || editableText(editor).isNotEmpty()) {
                endCardLearning(MaxCardLearningPolicy.Status.RETURN_UNVERIFIED); return
            }
            if (!cardProof.returned() || !cardProof.claimReplay()) {
                endCardLearning(MaxCardLearningPolicy.Status.REPLAY_FAILED); return
            }
            replayNode = target; replayEventExpected = !rule.gesture
            gestureEventPending = rule.gesture; gestureEventAt = SystemClock.uptimeMillis()
            val generation = cardGeneration
            learningState(MaxCardLearningPolicy.Status.REPLAY)
            val accepted = if (rule.gesture) tapTitle(rule, original.window, title, editor) {
                if (cardGeneration == generation && cardUntil != 0L) endCardLearning(MaxCardLearningPolicy.Status.REPLAY_FAILED)
            } else target.performAction(Node.ACTION_CLICK)
            if (!accepted) endCardLearning(MaxCardLearningPolicy.Status.REPLAY_FAILED)
        }
    }
    private fun profileButton(root: Node, title: Node): Node? {
        fun report(found: Boolean, semantic: Int = 0) = diagnostics.profileOpener(
            title.isClickable, title.actionList.any { it.id == Node.ACTION_CLICK }, semantic, found)
        report(false)
        store.learnedCard()?.let { rule ->
            val learned = learnedCardButton(title, rule)
            report(learned != null)
            return learned
        }
        // Prefer the title or a non-collection ancestor. Stop at the toolbar boundary;
        // do not click a whole container with back/call/menu buttons.
        var current: Node? = title
        var bar: Node? = null
        repeat(20) {
            val n = current ?: return@repeat
            if (n == root || collection(n)) { current = null; return@repeat }
            if (toolbar(n)) { bar = n; current = null; return@repeat }
            if (profileClickable(n) && nodes(n).none { it != n && profileClickable(it) }) { report(true); return n }
            current = n.parent
        }
        val headerBar = bar ?: return null
        val candidates = nodes(headerBar).filter { it != headerBar && !collection(it) &&
            profileClickable(it) && under(it, headerBar) }
        val priorities = candidates.map { MaxProfileActionPolicy.priority(it.text?.toString(), it.contentDescription?.toString()) }
        val index = MaxProfileActionPolicy.choose(priorities)
        if (index != null) { report(true, priorities.count { it != null }); return candidates[index] }
        // Do not bypass ambiguous spoken actions using an ID tie-breaker.
        if (priorities.any { it != null }) { report(false, priorities.count { it != null }); return null }
        // No manual event is needed for a uniquely identified, semantically explicit
        // avatar/profile action. The normal phone-card proof still runs afterwards.
        val byId = cardRules(title).keys.filter { n ->
            !toolbar(n) && MaxProfileActionPolicy.profileResource(n.viewIdResourceName.orEmpty()) &&
                nodes(n).none { it != n && profileClickable(it) }
        }.singleOrNull()
        report(byId != null)
        return byId
    }
    private fun openProfile(p: Pending, root: Node, title: Node, input: Node) {
        val rule = store.learnedCard()
        val button = profileButton(root, title)
        if (button == null) {
            // An unverified current chat need not be the recipient. Try global lookup once;
            // a candidate opened by that lookup still MUST pass the card phone check.
            if (p.query == null && p.selectedAt == null && p.listBackAt == null) {
                returnToChatList(p, root, title, input); return
            }
            finish("BLOCKED", "Не определено безопасное открытие карточки из заголовка чата: нет однозначного действия заголовка, профиля или аватара"); return
        }
        val fresh = rootInActiveWindow
        if (!unlocked() || !store.enabled || !app.settings.masterEnabled || fresh == null ||
            fresh.packageName?.toString() != MaxUiPolicy.PACKAGE || fresh.windowId != root.windowId ||
            nodes(fresh).any { it.isVisibleToUser && it.isPassword } ||
            !title.refresh() || !header(title) || title.viewIdResourceName != store.header ||
            !input.refresh() || !input.isVisibleToUser || !input.isEditable || input.isPassword || editableText(input).isNotEmpty() ||
            !button.refresh() || (rule?.gesture != true && !profileClickable(button)) || profileButton(fresh, title) != button) {
            finish("BLOCKED", "Экран изменился до открытия карточки MAX; нажатия не было"); return
        }
        val anchor = chatAnchor(title, input)
        if (anchor == null) { finish("BLOCKED", "MAX: структура исходного чата не определена до открытия карточки"); return }
        val visit = ProfileVisit(title, input, title.text.toString(), root.windowId, SystemClock.elapsedRealtime(),
            anchor = anchor, openerSources = profileEventSources(title, button, rule?.gesture == true))
        p.profile = visit
        trace(MaxUiDiagnostics.Stage.PROFILE_OPEN)
        visit.openedAt = SystemClock.uptimeMillis()
        val accepted = if (rule?.gesture == true) tapTitle(rule, root.windowId, title, input,
            beforeDispatch = { visit.openedAt = SystemClock.uptimeMillis() }) {
            if (pending === p) finish("BLOCKED", "Android отменил касание заголовка MAX. Повтора не будет")
        } else button.performAction(Node.ACTION_CLICK)
        if (!accepted) { finish("BLOCKED", "MAX не открыл карточку контакта: действие недоступно или область имени перекрыта"); return }
        // Do not try another button after an uncertain result. Phone proof is still mandatory.
        main.postDelayed(tick, 500)
    }
    /** A labelled phone field must belong to a small non-editable section, not chat history. */
    private fun profileField(all: List<Node>, expected: String): Pair<Boolean, Boolean> {
        val field = readProfilePhone(all)
        return field.found to MaxSearchPolicy.equivalent(field.phone, expected)
    }
    private fun readProfilePhone(all: List<Node>): MaxProfilePolicy.PhoneField {
        val labels = all.filter { it.isVisibleToUser && !it.isEditable && !it.isPassword && MaxProfilePolicy.phoneLabel(it.text?.toString()) }
        if (labels.size != 1) return MaxProfilePolicy.PhoneField(false, null)
        val label = labels.single()
        var section = label.parent
        repeat(3) {
            val parent = section ?: return MaxProfilePolicy.PhoneField(false, null)
            val fields = nodes(parent)
            if (fields.size > 18 || fields.any { it.isEditable } || collection(parent)) return MaxProfilePolicy.PhoneField(false, null)
            val visible = fields.filter { it.isVisibleToUser && !it.isPassword }
            val values = visible.mapNotNull { it.text?.toString() }
            val field = MaxProfilePolicy.readPhoneField(label.text?.toString(), values)
            if (field.found) return field
            section = parent.parent
        }
        return MaxProfilePolicy.PhoneField(false, null)
    }
    private fun keyboardVisible() = windows.any {
        it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD
    }
    private fun backWithinMax(): Boolean = unlocked() &&
        rootInActiveWindow?.packageName?.toString() == MaxUiPolicy.PACKAGE && performGlobalAction(GLOBAL_ACTION_BACK)
    private fun profileEventSources(title: Node, button: Node, gesture: Boolean): Set<Node> {
        if (!gesture) return setOf(button)
        // Snapshot ancestry BEFORE navigation: asking a destroyed title for its parent
        // after the card opens can misclassify our own delayed gesture as a user click.
        val result = mutableSetOf<Node>()
        var current: Node? = title
        repeat(20) {
            val n = current ?: return result
            if (collection(n)) return result
            result += n
            current = n.parent
        }
        return result
    }
    private fun chatAnchor(title: Node, input: Node): MaxProfileReturnPolicy.Anchor? {
        val bar = cardToolbar(title) ?: return null
        val ns = nodes(bar)
        if (ns.size >= 120) return null
        return MaxProfileReturnPolicy.Anchor(title.viewIdResourceName.orEmpty(), input.viewIdResourceName.orEmpty(),
            title.className?.toString().orEmpty(), input.className?.toString().orEmpty(), toolbarShape(ns))
    }
    private fun handleProfile(p: Pending, root: Node, all: List<Node>, title: Node?, input: Node?): Boolean {
        val visit = p.profile ?: return false
        if (visit.returnedAt != null) return false
        if (SystemClock.elapsedRealtime() - visit.started > 6000) {
            finish("BLOCKED", visit.returnIssue?.message ?: "Проверка карточки/возврата истекла. Отправки нет"); return true
        }
        if (!visit.returning) {
            trace(MaxUiDiagnostics.Stage.PROFILE_CHECK, all)
            if (all.any { it.isVisibleToUser && it.isEditable }) { main.postDelayed(tick, 400); return true }
            val (found, matches) = profileField(all, p.number)
            if (!found) { main.postDelayed(tick, 400); return true }
            visit.verified = matches
            visit.cardReadAt = SystemClock.uptimeMillis()
            visit.returning = true
            if (!backWithinMax()) { finish("BLOCKED", "Не удалось вернуться из карточки"); return true }
            main.postDelayed(tick, 500); return true
        }
        trace(MaxUiDiagnostics.Stage.PROFILE_RETURN, all)
        if (title == null || input == null) {
            visit.returnedTitle = null; visit.returnedEditor = null; visit.candidateAt = 0
            visit.returnIssue = MaxProfileReturnPolicy.Issue.LAYOUT
            main.postDelayed(tick, 300); return true
        }
        val issue = MaxProfileReturnPolicy.issue(root.windowId == visit.window, title.text?.toString() == visit.caption,
            visit.anchor, chatAnchor(title, input), all.count { it.isVisibleToUser && it.isEditable }, editableText(input))
        visit.returnIssue = issue
        if (issue != null) {
            visit.returnedTitle = null; visit.returnedEditor = null; visit.candidateAt = 0
            // Views and hint flags can still be assembling after Back. Wait within
            // the existing deadline without editing anything; never cross window/name changes.
            if (issue in setOf(MaxProfileReturnPolicy.Issue.WINDOW, MaxProfileReturnPolicy.Issue.CAPTION))
                finish("BLOCKED", issue.message)
            else main.postDelayed(tick, 300)
            return true
        }
        val now = SystemClock.elapsedRealtime()
        if (visit.returnedTitle != title || visit.returnedEditor != input) {
            visit.returnedTitle = title; visit.returnedEditor = input; visit.candidateAt = now
            main.postDelayed(tick, 300); return true
        }
        val stable = now - visit.candidateAt >= 300
        if (!stable) { main.postDelayed(tick, 300); return true }
        if (MaxProfileReturnPolicy.authorize(visit.verified, issue, stable)) {
            visit.returnedAt = now
            return false
        }
        // Wrong namesake: never enter message text. Return to the search before another candidate.
        p.profile = null
        p.skipCurrentProfile = true
        if (p.query != null && p.selectedAt != null) {
            p.returningSearch = true
            if (!backWithinMax()) { finish("BLOCKED", "Не удалось вернуться к поиску после несовпадения номера"); return true }
            main.postDelayed(tick, 500); return true
        }
        return false
    }
    private fun chatList(all: List<Node>): Boolean {
        val visible = all.filter { it.isVisibleToUser }
        val marker = visible.any { node ->
            if (!MaxChatListPolicy.chatsLabel(node.text?.toString()) &&
                !MaxChatListPolicy.chatsLabel(node.contentDescription?.toString())) false
            else {
                var selected = node.isSelected
                var parent = node.parent
                repeat(2) {
                    val ancestor = parent
                    if (ancestor != null && !collection(ancestor)) selected = selected || ancestor.isSelected
                    parent = ancestor?.parent
                }
                !node.isEditable && selected
            }
        }
        return MaxChatListPolicy.confirmed(
            visible.count { it.isEditable && it.viewIdResourceName == store.input },
            visible.count { MaxProfilePolicy.phoneLabel(it.text?.toString()) },
            visible.count(::collection), marker,
            visible.count { it.isEditable }, searchFields(all).size,
            visible.count { it.isPassword })
    }
    private fun openGlobalSearch(all: List<Node>): Boolean {
        val visible = all.filter { it.isVisibleToUser }
        val fields = searchFields(all).filter {
            val bounds = android.graphics.Rect().also { b -> it.getBoundsInScreen(b) }
            bounds.top >= 0 && bounds.bottom <= resources.displayMetrics.heightPixels / 3 &&
                (MaxSearchPolicy.isGlobalSearchLabel(it.hintText?.toString()) ||
                    MaxSearchPolicy.isGlobalSearchLabel(it.contentDescription?.toString()))
        }
        val section = visible.any { !it.isEditable && !it.isPassword &&
            (MaxSearchPolicy.isGlobalSearchSection(it.text?.toString()) ||
                MaxSearchPolicy.isGlobalSearchSection(it.contentDescription?.toString())) }
        val confirmed = MaxChatListPolicy.openSearch(
            visible.count { it.isEditable && it.viewIdResourceName == store.input },
            visible.count { MaxProfilePolicy.phoneLabel(it.text?.toString()) },
            visible.count { it.isEditable }, fields.size, section, visible.count { it.isPassword })
        diagnostics.globalSearchCheck(visible.count { it.isEditable }, fields.size, section, confirmed)
        return confirmed
    }
    private fun returnToChatList(p: Pending, root: Node, title: Node, input: Node) {
        if (p.listBackAt != null || p.query != null || p.edited || p.selectedAt != null) {
            finish("BLOCKED", "MAX: повторный выход из чата запрещён"); return
        }
        val fresh = rootInActiveWindow
        if (!store.enabled || !app.settings.masterEnabled || !unlocked() || fresh == null ||
            fresh.packageName?.toString() != MaxUiPolicy.PACKAGE || fresh.windowId != root.windowId ||
            nodes(fresh).any { it.isVisibleToUser && it.isPassword } ||
            !title.refresh() || !header(title) || title.viewIdResourceName != store.header ||
            !input.refresh() || !input.isEnabled || !input.isVisibleToUser || !input.isEditable || input.isPassword ||
            input.viewIdResourceName != store.input || editableText(input).isNotEmpty()) {
            finish("BLOCKED", "MAX: исходный чат изменился или содержит черновик; возврата не было"); return
        }
        if (nodes(fresh).count { it.isVisibleToUser && it.isEditable } != 1) {
            finish("BLOCKED", "MAX: перед выходом из чата обнаружено другое поле ввода"); return
        }
        p.keyboardReturn = if (keyboardVisible()) ProfileVisit(title, input, title.text.toString(), root.windowId, SystemClock.elapsedRealtime()) else null
        p.listBackAt = SystemClock.elapsedRealtime() // one navigation attempt; a proven IME dismissal is separate
        p.profile = null
        p.skipCurrentProfile = true
        trace(MaxUiDiagnostics.Stage.CHAT_LIST_BACK)
        if (!backWithinMax()) { finish("BLOCKED", "MAX: Android не принял возврат к списку чатов"); return }
        main.postDelayed(tick, 500)
    }
    private fun searchFields(all: List<Node>) = all.filter {
        it.isVisibleToUser && it.isEnabled && it.isEditable && !it.isPassword &&
            it.viewIdResourceName != store.input &&
            (MaxSearchPolicy.isSearchLabel(it.hintText?.toString()) ||
                MaxSearchPolicy.isSearchLabel(it.contentDescription?.toString()))
    }
    private fun collection(n: Node): Boolean = n.isScrollable || n.collectionInfo != null ||
        listOf("RecyclerView", "ListView", "ScrollView").any { n.className?.toString()?.contains(it) == true }
    /** Clickable result row must be inside a list, not a phone echoed in the search field. */
    private fun resultRow(phone: Node): Node? {
        if (!phone.isVisibleToUser || phone.isEditable || phone.isPassword) return null
        var current: Node? = phone
        var clickable: Node? = null
        repeat(12) {
            val n = current ?: return null
            if (collection(n)) return clickable
            if (n.isClickable && n.isVisibleToUser && n.isEnabled && !n.isEditable) clickable = n
            current = n.parent
        }
        return null
    }
    /** Explicit phone lookup action, not a call button and not a search result. */
    private fun findByPhoneButtons(root: Node, all: List<Node>): Pair<Boolean, List<Node>> {
        val labels = all.filter { it.isVisibleToUser && !it.isEditable && !it.isPassword &&
            MaxSearchPolicy.isFindByPhoneAction(it.text?.toString(), it.contentDescription?.toString()) }
        val buttons = mutableListOf<Node>()
        for (label in labels) {
            var current: Node? = label
            var button: Node? = null
            for (i in 0..3) {
                val n = current ?: break
                if (n == root || collection(n) || n.isEditable || n.isPassword) break
                if (profileClickable(n)) { button = n; break }
                current = n.parent
            }
            val target = button ?: return true to emptyList()
            // Do not click a broad container that contains another active control.
            if (nodes(target).any { it != target && profileClickable(it) }) return true to emptyList()
            buttons += target
        }
        return labels.isNotEmpty() to buttons.distinct()
    }
    private fun findByPhone(p: Pending, root: Node, all: List<Node>, field: Node): Boolean {
        if (!MaxSearchPolicy.canFindByPhone(editableText(field), p.query, p.number, p.findByPhoneAt != null)) return false
        val (present, buttons) = findByPhoneButtons(root, all)
        if (!present) return false
        val button = buttons.singleOrNull()
        if (button == null || p.inspectedTotal >= 3) {
            finish("BLOCKED", "MAX: действие «Найти по номеру» неоднозначно или исчерпан лимит кандидатов"); return true
        }
        val fresh = rootInActiveWindow
        if (!unlocked() || !store.enabled || !app.settings.masterEnabled || fresh == null ||
            fresh.packageName?.toString() != MaxUiPolicy.PACKAGE || fresh.windowId != root.windowId) {
            finish("BLOCKED", "MAX: экран изменился до поиска по номеру"); return true
        }
        val ns = nodes(fresh)
        val freshField = searchFields(ns).singleOrNull()
        if (ns.any { it.isVisibleToUser && it.isPassword } ||
            ns.count { it.isVisibleToUser && it.isEditable } != 1 || freshField != field ||
            !MaxSearchPolicy.canFindByPhone(freshField?.let(::editableText), p.query, p.number, false) ||
            findByPhoneButtons(fresh, ns).second.singleOrNull() != button || !button.refresh() || !profileClickable(button)) {
            finish("BLOCKED", "MAX: запрос или кнопка изменились до поиска по номеру"); return true
        }
        // Reserve BEFORE action. Never click again after a failure or unknown transition.
        val now = SystemClock.elapsedRealtime()
        p.findByPhoneAt = now
        p.selectedAt = now
        p.inspectedTotal++
        p.skipCurrentProfile = false
        trace(MaxUiDiagnostics.Stage.FIND_BY_PHONE, ns)
        if (!button.performAction(Node.ACTION_CLICK)) {
            finish("BLOCKED", "MAX не принял «Найти по номеру». Повторного нажатия не будет"); return true
        }
        main.removeCallbacks(tick); main.postDelayed(tick, 500)
        return true
    }
    private fun search(p: Pending, root: Node, all: List<Node>) {
        trace(if (p.selectedAt != null) MaxUiDiagnostics.Stage.VERIFY else if (p.query != null) MaxUiDiagnostics.Stage.RESULTS else MaxUiDiagnostics.Stage.SEARCH, all)
        val now = SystemClock.elapsedRealtime()
        fun waitForUi() { main.removeCallbacks(tick); main.postDelayed(tick, 500) }
        if (!p.globalSearchConfirmed) {
            if (chatList(all)) { p.globalSearchConfirmed = true; trace(MaxUiDiagnostics.Stage.CHAT_LIST, all) }
            else if (openGlobalSearch(all)) { p.globalSearchConfirmed = true; trace(MaxUiDiagnostics.Stage.GLOBAL_SEARCH, all) }
            else { finish("BLOCKED", "MAX: общий список чатов не подтверждён. Поиск внутри переписки не запускается"); return }
        }
        if (p.selectedAt != null) {
            val waitMs = if (p.findByPhoneAt == p.selectedAt) 6000 else 1800
            if (now - p.selectedAt!! < waitMs) waitForUi()
            else finish("BLOCKED", "Результат открыт, но номер в шапке чата не подтверждён. По имени отправлять нельзя")
            return
        }
        val fields = searchFields(all)
        if (fields.size > 1) { finish("BLOCKED", "Несколько полей поиска MAX — выбор остановлен"); return }
        val field = fields.singleOrNull()
        if (field == null) {
            if (p.query != null) { finish("BLOCKED", "Экран поиска изменился без выбора результата"); return }
            if (p.searchClicked) { waitForUi(); return }
            val buttons = all.filter {
                val rect = android.graphics.Rect().also { b -> it.getBoundsInScreen(b) }
                it.isVisibleToUser && it.isEnabled && it.isClickable && !it.isEditable && !it.isPassword &&
                    rect.bottom <= resources.displayMetrics.heightPixels / 3 &&
                    (MaxSearchPolicy.isSearchLabel(it.contentDescription?.toString()) || MaxSearchPolicy.isSearchLabel(it.text?.toString()))
            }
            val button = buttons.singleOrNull()
            if (button == null) { finish("BLOCKED", "Кнопка поиска MAX не определена однозначно. Требуется поддержка этого интерфейса"); return }
            p.searchClicked = true
            if (!button.performAction(Node.ACTION_CLICK)) { finish("BLOCKED", "MAX не принял открытие поиска"); return }
            waitForUi(); return
        }
        val queryText = editableText(field)
        val queries = MaxSearchPolicy.queries(p.number) + listOfNotNull(p.contactName)
        if (p.query == null) {
            if (queryText.isNotEmpty()) { finish("BLOCKED", "В поиске уже введён текст. Он не будет заменён автоматически"); return }
            p.query = queries.first()
            p.queryAt = now
            val args = Bundle().apply { putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p.query) }
            if (!field.performAction(Node.ACTION_SET_TEXT, args)) { finish("BLOCKED", "MAX не принял номер для поиска"); return }
            waitForUi(); return
        }
        if (queryText != p.query) { finish("BLOCKED", "Поисковый запрос изменился — сценарий остановлен"); return }
        if (now - p.queryAt < 700) { waitForUi(); return }
        if (findByPhone(p, root, all, field)) return
        val rows = all.filter {
            !it.isEditable && !it.isPassword && it.isVisibleToUser &&
                (MaxSearchPolicy.equivalent(it.text?.toString(), p.number) ||
                    (p.query == p.contactName && MaxProfilePolicy.candidateNameMatches(it.text?.toString(), p.contactName)))
        }.mapNotNull(::resultRow).distinct().filterNot { p.query != p.contactName && it in p.triedRows }
        if (rows.size > 1 && p.query != p.contactName) { finish("BLOCKED", "MAX показывает несколько результатов с этим номером — выбор неоднозначен"); return }
        if (p.inspectedTotal >= 3) { finish("BLOCKED", "Проверены 3 кандидата. Номер не подтверждён; отправки нет"); return }
        val row = if (p.query == p.contactName) rows.getOrNull(p.inspectedNames) else rows.singleOrNull()
        if (row != null) {
            // Root, query and row must still be active. The opened chat is verified again.
            if (!root.refresh() || rootInActiveWindow?.windowId != root.windowId || !field.refresh() || field.text?.toString() != p.query || !row.refresh()) {
                finish("BLOCKED", "Результаты поиска изменились до выбора"); return
            }
            p.triedRows += row
            p.inspectedTotal++
            if (p.query == p.contactName) p.inspectedNames++
            p.selectedAt = now
            p.skipCurrentProfile = false
            if (!row.performAction(Node.ACTION_CLICK)) { finish("BLOCKED", "MAX не открыл результат поиска"); return }
            waitForUi(); return
        }
        if (now - p.queryAt < 4000) { waitForUi(); return }
        if (p.queryIndex + 1 < queries.size) {
            p.queryIndex++
            p.query = queries[p.queryIndex]
            p.queryAt = now
            val args = Bundle().apply { putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p.query) }
            if (!field.performAction(Node.ACTION_SET_TEXT, args)) { finish("BLOCKED", "MAX не принял другой формат номера"); return }
            waitForUi()
        } else finish("BLOCKED", "MAX не показал единственный результат с полным номером. Проверены эквивалентные форматы; по имени не выбираем")
    }
    private fun finish(result: String, message: String) {
        trace(if (result == "UI_CHECKED") MaxUiDiagnostics.Stage.DRY_CHECK else MaxUiDiagnostics.Stage.STOP, outcome = result)
        val p = pending
        if (result == "UI_CHECKED" && p != null && p.routeReady) runCatching { routes.passed(p.accountId, p.route) }
        pending = null; busy = false
        main.removeCallbacks(tick); main.removeCallbacks(timeout)
        if (p != null) log(p.event, result, message)
    }
    private fun log(event: CallEvent, result: String, message: String, durable: Boolean = true) {
        val ownsCapture = pending == null || pending?.event === event
        if (ownsCapture && result == "UI_UNKNOWN") trace(MaxUiDiagnostics.Stage.CLICK, outcome = result)
        if (ownsCapture && result == "BLOCKED") {
            diagnostics.stopReason(message)
            trace(MaxUiDiagnostics.Stage.STOP, outcome = result)
        }
        if (result in setOf("UI_PENDING", "UI_CHECKED", "UI_UNKNOWN", "UI_SENT_LOCAL", "BLOCKED") &&
            ownsCapture) {
            runCatching { store.saveReport(diagnostics, MaxUiStore.ReportKind.ATTEMPT, durable) }
        }
        logs.trySend(event.copy(result = result, errorMessage = message, errorCode = if (result == "BLOCKED") "max_ui_blocked" else null))
    }
    private fun trace(stage: MaxUiDiagnostics.Stage, source: List<Node>? = null, outcome: String = "") {
        if (!diagnostics.active()) return
        runCatching {
            val root = rootInActiveWindow
            // Do not inspect or export another app, even on a failure callback.
            val ui = if (root?.packageName?.toString() == MaxUiPolicy.PACKAGE && unlocked()) source ?: nodes(root) else emptyList()
            val safe = ui.map { n ->
                val rect = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                val kind = if (n.isPassword) MaxUiDiagnostics.Label.PASSWORD else
                    MaxUiDiagnostics.label(false, n.text?.toString(), n.contentDescription?.toString(), n.hintText?.toString())
                MaxUiDiagnostics.Node(n.isVisibleToUser, n.isEditable, n.isClickable, n.isEnabled,
                    !n.viewIdResourceName.isNullOrBlank(), rect.bottom <= resources.displayMetrics.heightPixels / 3, kind)
            }
            diagnostics.record(stage, version(), unlocked(), store.header.isNotEmpty() && store.input.isNotEmpty() && store.version == version(), safe, outcome)
            // Persist transitions, not every polling frame. Never overwrite a terminal
            // snapshot with passive observation after returning to CallShift.
            if (pending != null && stage != lastSavedStage && stage in setOf(
                    MaxUiDiagnostics.Stage.ROUTE_PICK, MaxUiDiagnostics.Stage.ROUTE_READY,
                    MaxUiDiagnostics.Stage.PROFILE_OPEN, MaxUiDiagnostics.Stage.PROFILE_CHECK,
                    MaxUiDiagnostics.Stage.PROFILE_RETURN, MaxUiDiagnostics.Stage.INPUT,
                    MaxUiDiagnostics.Stage.CLICK, MaxUiDiagnostics.Stage.FIND_BY_PHONE,
                    MaxUiDiagnostics.Stage.GLOBAL_SEARCH, MaxUiDiagnostics.Stage.CHAT_LIST_BACK)) {
                store.saveReport(diagnostics, MaxUiStore.ReportKind.ATTEMPT)
                lastSavedStage = stage
            }
        }
    }
    data class Profile(val header: String, val input: String, val version: Long, val phone: String, val at: Long)
    companion object {
        fun report(context: android.content.Context): String {
            val uiStore = MaxUiStore(context)
            val saved = uiStore.savedReport()
            val current = diagnostics.report(android.os.Build.VERSION.SDK_INT, fi.callshift.app.BuildConfig.VERSION_NAME) +
                "\nLearned card method=" + (uiStore.learnedCard()?.let { if (it.gesture) "ANCHORED_TITLE_GESTURE" else "NODE_ACTION" } ?: "NONE") +
                "\nLearned send method=" + (uiStore.learnedSend()?.let { if (it.gesture) "ANCHORED_SEND_GESTURE" else "NODE_ACTION" } ?: "NONE")
            return if (saved == null) current else "$saved\n\nCurrent in-memory capture (may be a different operation):\n$current"
        }
        val diagnostics = MaxUiDiagnostics { SystemClock.elapsedRealtime() }
        @Volatile private var instance: MaxUiService? = null
        @Volatile var captureIssue = MaxProfileCapture.Issue.NOT_STARTED
            private set
        @Volatile var probeUntil = 0L
            private set
        @Volatile var candidate: Profile? = null
        val connected get() = instance != null
        @Volatile var cardLearningStatus = MaxCardLearningPolicy.Status.IDLE
            private set
        @Volatile var sendTrainingStatus = MaxSendTrainingPolicy.Status.IDLE
            private set
        val sendTrainingActive get() = instance?.sendTrainingUntil != 0L
        fun endSendTraining() { instance?.endSendLearning() }
        /** One deliberate user tap in MAX proves the send control. Nothing is clicked
         * automatically during training; recipient, chat and text are never stored. */
        fun startSendTraining(): Boolean {
            val service = instance
            if (service == null) { sendTrainingStatus = MaxSendTrainingPolicy.Status.NO_SERVICE; return false }
            if (service.pending != null || service.headerGestureBusy || service.cardUntil != 0L || service.sendTrainingUntil != 0L) {
                sendTrainingStatus = MaxSendTrainingPolicy.Status.BUSY
                return false
            }
            check(Looper.myLooper() == Looper.getMainLooper())
            return runCatching {
                service.stop("Остановлено для обучения кнопки отправки")
                diagnostics.start(120_000)
                if (service.store.header.isEmpty() || service.store.input.isEmpty() || service.store.version != service.version()) {
                    service.sendState(MaxSendTrainingPolicy.Status.NO_LAYOUT)
                    false
                } else {
                    service.sendTrainingUntil = SystemClock.elapsedRealtime() + 120_000
                    service.sendDraft = null; service.sendSnapshot = null; service.sendTrial = null; service.sendArmedAt = 0L
                    service.sendTapSeen = false; service.sendTapAt = -1L
                    service.sendTapChain = emptyList(); service.sendTapSource = false
                    service.sendEventUnresolved = false
                    service.sendAutoTypePending = false; service.sendAutoTypeTries = 0; service.sendAutoTypeAt = 0L
                    service.store.sendOutcome(MaxSendTrainingPolicy.Status.WAIT_CHAT)
                    service.sendState(MaxSendTrainingPolicy.Status.WAIT_CHAT)
                    service.main.post(service.sendTick)
                    android.widget.Toast.makeText(service, "Обучение запущено: откройте любой безопасный чат, введите текст и нажмите стрелку", android.widget.Toast.LENGTH_LONG).show()
                    true
                }
            }.getOrElse {
                service.endSendLearning(MaxSendTrainingPolicy.Status.ERROR)
                sendTrainingStatus = MaxSendTrainingPolicy.Status.ERROR
                false
            }
        }
        fun endCardTraining() { instance?.endCardLearning() }
        fun startCardTraining(gesture: Boolean = false): Boolean {
            val service = instance
            if (service?.headerGestureBusy == true) {
                cardLearningStatus = MaxCardLearningPolicy.Status.GESTURE_PENDING
                return false
            }
            if (service == null) { cardLearningStatus = MaxCardLearningPolicy.Status.NO_SERVICE; return false }
            check(Looper.myLooper() == Looper.getMainLooper())
            return runCatching {
                service.stop("Остановлено для обучения открытию карточки")
                diagnostics.start(60_000)
                if (gesture && service.serviceInfo.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0) {
                    service.learningState(MaxCardLearningPolicy.Status.GESTURE_UNAVAILABLE)
                    return@runCatching false
                }
                if (service.store.header.isEmpty() || service.store.input.isEmpty() || service.store.version != service.version()) {
                    service.learningState(MaxCardLearningPolicy.Status.NO_LAYOUT)
                    false
                } else {
                    service.cardProof.reset()
                    service.cardGeneration++; service.gestureTraining = gesture; service.gestureReadyAt = 0L
                    service.store.cardOutcome(MaxCardLearningPolicy.Status.WAIT_CHAT)
                    service.cardUntil = SystemClock.elapsedRealtime() + 60_000
                    service.learningState(MaxCardLearningPolicy.Status.WAIT_CHAT)
                    service.main.post(service.cardTick)
                    true
                }
            }.getOrElse {
                service.endCardLearning(MaxCardLearningPolicy.Status.ERROR)
                cardLearningStatus = MaxCardLearningPolicy.Status.ERROR
                false
            }
        }
        val running get() = instance?.pending != null || instance?.headerGestureBusy == true
        val gestureUnresolved get() = instance?.gestureFlight?.overdue(SystemClock.elapsedRealtime()) == true
        @Volatile var pickerCandidate: MaxRoutePolicy.Picker? = null
            private set
        @Volatile var pickerStatus = "Окно выбора ещё не изучено"
            private set
        fun endPickerProbe() { instance?.endPicker() }
        fun startPickerProbe(): Boolean {
            val service = instance
            if (service?.headerGestureBusy == true) {
                pickerStatus = MaxCardLearningPolicy.Status.GESTURE_PENDING.explanation
                return false
            }
            if (service == null) {
                pickerCandidate = null
                pickerStatus = MaxPickerInspection.Issue.SERVICE_UNAVAILABLE.explanation
                diagnostics.start(60_000)
                diagnostics.recordPicker(MaxPickerInspection.Report(MaxPickerInspection.Issue.SERVICE_UNAVAILABLE))
                return false
            }
            return runCatching {
                service.stop("Остановлено для изучения системного окна выбора MAX")
                pickerCandidate = null
                diagnostics.start(60_000)
                service.lastPickerIssue = MaxPickerInspection.Issue.STARTED
                service.pickerReport(MaxPickerInspection.Report(MaxPickerInspection.Issue.STARTED))
                service.pickerUntil = SystemClock.elapsedRealtime() + 60_000
                service.main.post(service.pickerTick)
                true
            }.getOrDefault(false)
        }
        fun startProfileProbe(): Boolean {
            val service = instance
            if (service?.headerGestureBusy == true) {
                captureIssue = MaxProfileCapture.Issue.ACTION_PENDING
                return false
            }
            if (service == null) {
                captureIssue = MaxProfileCapture.Issue.SERVICE_UNAVAILABLE
                diagnostics.start(60_000)
                diagnostics.recordCapture(captureIssue, -1, false, false)
                return false
            }
            check(Looper.myLooper() == Looper.getMainLooper())
            return runCatching { service.startProbe(); true }.getOrElse {
                service.endProbe()
                captureIssue = MaxProfileCapture.Issue.ERROR
                false
            }
        }
        fun endProfileProbe() { instance?.endProbe() }
        fun usableCandidate(context: android.content.Context): Profile? {
            val c = candidate ?: MaxUiStore(context).staged() ?: return null
            val installed = runCatching { context.packageManager.getPackageInfo(MaxUiPolicy.PACKAGE, 0).longVersionCode }.getOrDefault(-1)
            return c.takeIf { MaxProfileCapture.fresh(SystemClock.elapsedRealtime(), c.at, c.version, installed) }
        }
        fun collectDiagnostics(forCall: Boolean) {
            val service = instance ?: return
            val begin = Runnable {
                service.stop("Остановлено для безопасной диагностики")
                diagnostics.start(if (forCall) 180_000 else 60_000)
                service.store.modes(forCall, false)
            }
            // A UI button must cancel a pending send before returning, not enqueue behind it.
            if (Looper.myLooper() == Looper.getMainLooper()) begin.run() else service.main.post(begin)
        }
        fun stopNow() { instance?.main?.post { instance?.stop("Остановлено пользователем") } }
        suspend fun submit(context: android.content.Context, event: CallEvent, number: String, text: String, accountId: String?) {
            val name = withContext(Dispatchers.IO) {
                if (context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) null
                else runCatching {
                    val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
                    context.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        val names = mutableSetOf<String>()
                        while (cursor.moveToNext() && names.size < 2) cursor.getString(0)?.trim()?.takeIf { it.isNotBlank() && it.length <= 120 }?.let(names::add)
                        names.singleOrNull()
                    }
                }.getOrNull()
            }
            withContext(Dispatchers.Main) {
            val service = instance
            if (service == null) CallShiftApp.from(context).eventStore.record(event.copy(result = "BLOCKED", errorMessage = "Служба специальных возможностей MAX не подключена"))
            else service.begin(event, number, text, name, accountId)
            }
        }
    }
}
