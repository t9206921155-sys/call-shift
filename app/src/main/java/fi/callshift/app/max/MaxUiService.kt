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
import fi.callshift.app.domain.MaxProfileCapture
import fi.callshift.app.domain.MaxProfilePolicy
import fi.callshift.app.domain.MaxChatListPolicy
import fi.callshift.app.domain.MaxSearchPolicy
import fi.callshift.app.forward.CallEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** Opt-in bounded phone search and UI interaction. Never matches names or clicks coordinates. */
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
        var edited: Boolean = false, var searchClicked: Boolean = false, var query: String? = null,
        var queryAt: Long = 0, var queryIndex: Int = 0, var selectedAt: Long? = null,
        val contactName: String? = null, val triedRows: MutableSet<Node> = mutableSetOf(),
        var inspectedNames: Int = 0, var inspectedTotal: Int = 0,
        var profile: ProfileVisit? = null, var skipCurrentProfile: Boolean = false, var returningSearch: Boolean = false)
    private data class ProfileVisit(val title: Node, val editor: Node, val caption: String, val window: Int,
        val started: Long, var returning: Boolean = false, var verified: Boolean = false, var returnedAt: Long? = null)
    private var pending: Pending? = null
    private var busy = false
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
        if (pending?.profile?.returnedAt != null && event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            finish("BLOCKED", "Действие пользователя изменило проверенный экран; отправка остановлена"); return
        }
        // Profile probing polls independently: quiet MAX screens need not emit events.
        if (pending == null && diagnostics.active()) trace(MaxUiDiagnostics.Stage.OBSERVE)
        if (pending != null && !busy) { main.removeCallbacks(tick); main.postDelayed(tick, 300) }
    }
    private fun editableText(n: Node?): String = MaxUiPolicy.editableText(
        n?.text?.toString(), n?.hintText?.toString(), n?.isShowingHintText == true)
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
        trace(MaxUiDiagnostics.Stage.START)
        if (pending != null) { log(event, "BLOCKED", "MAX уже занят другой попыткой; очередь и повтор отключены"); return }
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
        val dry = !store.live || diagnostics.active()
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
    private fun step() {
        if (busy) return
        val p = pending ?: return
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
                if (chatList(all)) p.globalSearchConfirmed = true
                else {
                    if (SystemClock.elapsedRealtime() - p.listBackAt!! >= 4000) {
                        finish("BLOCKED", "MAX: после возврата общий список чатов не подтверждён. Поиск внутри переписки не запускается")
                    } else main.postDelayed(tick, 400)
                    return
                }
            }
            if (!p.edited && input != null && h.size == 1 && searchFields(all).isEmpty() &&
                MaxUiPolicy.phone(h.single().text.toString()) == null && !p.skipCurrentProfile && p.profile == null) {
                if (draft.isNotEmpty()) { finish("BLOCKED", "В чате есть черновик — карточка не открывается"); return }
                openProfile(p, root, h.single(), input)
                return
            }
            if (!p.edited) {
                val correctChat = h.size == 1 && MaxSearchPolicy.equivalent(checkedRecipient(p, h.singleOrNull(), input), p.number)
                if (!correctChat || (p.query != null && p.selectedAt == null) || searchFields(all).isNotEmpty()) {
                    if (draft.isNotEmpty()) { finish("BLOCKED", "В открытом чате есть черновик — поиск не запускается"); return }
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
                        if (!reserved) { finish("BLOCKED", "Лимит MAX: 5 попыток за 24 часа, либо хранилище недоступно"); return@withContext }
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
                        main.postDelayed(tick, 500)
                    }
                }
                return
            }
            if (draft != p.text) { finish("BLOCKED", "Текст изменился; отправка остановлена"); return }
            val inputBounds = android.graphics.Rect().also { input!!.getBoundsInScreen(it) }
            val send = all.filter {
                val label = it.contentDescription?.toString()?.trim()?.lowercase().orEmpty().ifEmpty { it.text?.toString()?.trim()?.lowercase().orEmpty() }
                val bounds = android.graphics.Rect().also { b -> it.getBoundsInScreen(b) }
                kotlin.math.abs(bounds.centerY() - inputBounds.centerY()) <= (96 * resources.displayMetrics.density).toInt() &&
                    it.isVisibleToUser && it.isEnabled && it.isClickable && !it.isEditable && !it.isPassword &&
                    label in listOf("отправить", "отправить сообщение", "send", "send message") && !it.viewIdResourceName.isNullOrBlank()
            }.singleOrNull()
            if (send == null) { finish("BLOCKED", "Кнопка отправки не определена однозначно. Черновик оставлен в MAX"); return }
            trace(MaxUiDiagnostics.Stage.CLICK, all)
            // Remove pending BEFORE clicking: no event, timeout or reconnection can retry.
            pending = null; main.removeCallbacks(tick); main.removeCallbacks(timeout)
            log(p.event, "UI_UNKNOWN", "Передано управление кнопке MAX. Результат неизвестен; повторов нет")
            val clicked = send.performAction(Node.ACTION_CLICK)
            log(p.event, "UI_UNKNOWN", if (clicked) "Нажата кнопка MAX. Отправка и доставка НЕ подтверждены; повторов нет"
                else "Результат нажатия MAX неизвестен. Проверьте чат вручную; повторов нет")
        } catch (_: Exception) { finish("UI_UNKNOWN", "Сценарий MAX остановлен с неопределённым результатом. Проверьте чат; повторов нет") }
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
        // A matching display name alone is insufficient: return must preserve original node identities.
        return p.number.takeIf { title != null && title == proof.title && editor == proof.editor &&
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
    private fun profileButton(root: Node, title: Node): Node? {
        fun report(found: Boolean, semantic: Int = 0) = diagnostics.profileOpener(
            title.isClickable, title.actionList.any { it.id == Node.ACTION_CLICK }, semantic, found)
        report(false)
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
        report(index != null, priorities.count { it != null })
        return index?.let(candidates::get)
    }
    private fun openProfile(p: Pending, root: Node, title: Node, input: Node) {
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
            !button.refresh() || !profileClickable(button) || profileButton(fresh, title) != button) {
            finish("BLOCKED", "Экран изменился до открытия карточки MAX; нажатия не было"); return
        }
        p.profile = ProfileVisit(title, input, title.text.toString(), root.windowId, SystemClock.elapsedRealtime())
        trace(MaxUiDiagnostics.Stage.PROFILE_OPEN)
        if (!button.performAction(Node.ACTION_CLICK)) { finish("BLOCKED", "MAX не открыл карточку контакта"); return }
        // Do not try another button after an uncertain result. Phone proof is still mandatory.
        main.postDelayed(tick, 500)
    }
    /** A labelled phone field must belong to a small non-editable section, not chat history. */
    private fun profileField(all: List<Node>, expected: String): Pair<Boolean, Boolean> {
        val labels = all.filter { it.isVisibleToUser && !it.isEditable && !it.isPassword && MaxProfilePolicy.phoneLabel(it.text?.toString()) }
        if (labels.size != 1) return false to false
        val label = labels.single()
        var section = label.parent
        repeat(3) {
            val parent = section ?: return false to false
            val fields = nodes(parent)
            if (fields.size > 18 || fields.any { it.isEditable } || collection(parent)) return false to false
            val visible = fields.filter { it.isVisibleToUser && !it.isPassword }
            val values = visible.mapNotNull { it.text?.toString() }
            val phones = values.mapNotNull(MaxUiPolicy::phone).distinct()
            if (phones.isNotEmpty()) return true to (MaxProfilePolicy.verifiedPhone(label.text?.toString(), values, expected) != null)
            section = parent.parent
        }
        return false to false
    }
    private fun backWithinMax(): Boolean = unlocked() &&
        rootInActiveWindow?.packageName?.toString() == MaxUiPolicy.PACKAGE && performGlobalAction(GLOBAL_ACTION_BACK)
    private fun handleProfile(p: Pending, root: Node, all: List<Node>, title: Node?, input: Node?): Boolean {
        val visit = p.profile ?: return false
        if (visit.returnedAt != null) return false
        if (SystemClock.elapsedRealtime() - visit.started > 6000) { finish("BLOCKED", "Проверка карточки/возврата истекла. Отправки нет"); return true }
        if (!visit.returning) {
            trace(MaxUiDiagnostics.Stage.PROFILE_CHECK, all)
            if (all.any { it.isVisibleToUser && it.isEditable }) { main.postDelayed(tick, 400); return true }
            val (found, matches) = profileField(all, p.number)
            if (!found) { main.postDelayed(tick, 400); return true }
            visit.verified = matches
            visit.returning = true
            if (!backWithinMax()) { finish("BLOCKED", "Не удалось вернуться из карточки"); return true }
            main.postDelayed(tick, 500); return true
        }
        trace(MaxUiDiagnostics.Stage.PROFILE_RETURN, all)
        if (title == null || input == null) { main.postDelayed(tick, 400); return true }
        if (title != visit.title || input != visit.editor || root.windowId != visit.window || title.text?.toString() != visit.caption || editableText(input).isNotEmpty()) {
            finish("BLOCKED", "После карточки исходный чат не подтверждён или изменён черновик"); return true
        }
        if (visit.verified) {
            visit.returnedAt = SystemClock.elapsedRealtime()
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
        p.listBackAt = SystemClock.elapsedRealtime() // one attempt even if Android reports failure
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
    private fun search(p: Pending, root: Node, all: List<Node>) {
        trace(if (p.selectedAt != null) MaxUiDiagnostics.Stage.VERIFY else if (p.query != null) MaxUiDiagnostics.Stage.RESULTS else MaxUiDiagnostics.Stage.SEARCH, all)
        val now = SystemClock.elapsedRealtime()
        fun waitForUi() { main.removeCallbacks(tick); main.postDelayed(tick, 500) }
        if (!p.globalSearchConfirmed) {
            if (chatList(all)) { p.globalSearchConfirmed = true; trace(MaxUiDiagnostics.Stage.CHAT_LIST, all) }
            else { finish("BLOCKED", "MAX: общий список чатов не подтверждён. Поиск внутри переписки не запускается"); return }
        }
        if (p.selectedAt != null) {
            if (now - p.selectedAt!! < 1800) waitForUi()
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
    private fun log(event: CallEvent, result: String, message: String) {
        if (result == "BLOCKED") {
            diagnostics.stopReason(message)
            trace(MaxUiDiagnostics.Stage.STOP, outcome = result)
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
        }
    }
    data class Profile(val header: String, val input: String, val version: Long, val phone: String, val at: Long)
    companion object {
        val diagnostics = MaxUiDiagnostics { SystemClock.elapsedRealtime() }
        @Volatile private var instance: MaxUiService? = null
        @Volatile var captureIssue = MaxProfileCapture.Issue.NOT_STARTED
            private set
        @Volatile var probeUntil = 0L
            private set
        @Volatile var candidate: Profile? = null
        val connected get() = instance != null
        val running get() = instance?.pending != null
        @Volatile var pickerCandidate: MaxRoutePolicy.Picker? = null
            private set
        @Volatile var pickerStatus = "Окно выбора ещё не изучено"
            private set
        fun endPickerProbe() { instance?.endPicker() }
        fun startPickerProbe(): Boolean {
            val service = instance
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
