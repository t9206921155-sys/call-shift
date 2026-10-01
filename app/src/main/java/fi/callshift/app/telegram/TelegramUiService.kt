package fi.callshift.app.telegram

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo as Node
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.TelegramUiPolicy
import fi.callshift.app.forward.CallEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Имитация касаний в приложении Telegram — тот же принцип, что в MAX: открыть
 * приложение, найти чат по номеру звонившего, подтвердить номер, ввести текст,
 * нажать кнопку отправки. Отказ при любой неоднозначности, одна попытка,
 * без повторов, без привязки к получателю. */
class TelegramUiService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val app by lazy { CallShiftApp.from(this) }
    private val store by lazy { TelegramUiStore(this) }

    private enum class Phase { OPEN, RESULTS, COMPOSE, SEND }
    private class Pending(val event: CallEvent, val number: String, val text: String, val dry: Boolean,
        val started: Long = SystemClock.elapsedRealtime()) {
        var phase: Phase = Phase.OPEN
        var queryAt: Long = 0
        var edited = false
        var clearedLeftover = false
        var composerTried = false
        var sendWaitAt: Long = 0
        var proofChecks = 0
        var proofAt: Long = 0
        var gestureAt: Long = 0
    }

    private var pending: Pending? = null
    private var busy = false
    private val tick = Runnable { step() }
    private val timeout = Runnable { finish("BLOCKED", "Время ожидания Telegram истекло. Повтора не будет; проверьте чат вручную") }

    // ---- training state ----
    private var trainUntil = 0L
    private var trainArmed = false
    private var trainToastShown = false
    private var trainTypeTried = false
    private var trainFocusAt = 0L
    private var lastArmedDraft = ""
    private var soleId: String? = null
    private var soleClass = ""
    private var soleDesc = ""
    private var trainEditorSeenAt = 0L
    private var trainVerifyAt = 0L
    private var trainTrial: Trial? = null
    private class Trial(val id: String, val className: String, val desc: String, val clickable: Boolean)
    private val trainTick = Runnable { trainStep() }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

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
    override fun onDestroy() { stop("Служба отключена"); instance = null; super.onDestroy() }

    fun stop(reason: String) {
        trainUntil = 0L; trainArmed = false; trainTrial = null
        main.removeCallbacks(trainTick)
        finish("BLOCKED", reason)
        runCatching { store.modes(false, false) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() !in TelegramUiPolicy.PACKAGES) return
        if (trainUntil != 0L) {
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                runCatching { handleTrainClick(event) }.onFailure { endTraining(TelegramUiPolicy.TrainStatus.ERROR) }
            }
            main.removeCallbacks(trainTick); main.postDelayed(trainTick, 300)
        }
        if (pending != null && !busy) { main.removeCallbacks(tick); main.postDelayed(tick, 300) }
    }

    private fun unlocked() = getSystemService(android.os.PowerManager::class.java).isInteractive &&
        !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
    private fun version(): Long = TelegramUiPolicy.PACKAGES.firstNotNullOfOrNull { pkg -> runCatching { packageManager.getPackageInfo(pkg, 0).longVersionCode }.getOrNull() } ?: -1

    private fun isTelegram(pkg: String?) = pkg != null && pkg in TelegramUiPolicy.PACKAGES

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
    private fun textOf(n: Node?): String = n?.text?.toString() ?: ""
    private fun descOf(n: Node): String = n.contentDescription?.toString() ?: ""
    /** The single visible editable field of the current Telegram screen: search box
     * on the list, message editor in a chat. Never a password field. */
    private fun editor(all: List<Node>): Node? = all
        .filter { it.isVisibleToUser && it.isEnabled && it.isEditable && !it.isPassword }
        .singleOrNull()

    // ===================== send =====================
    private fun begin(event: CallEvent, number: String, text: String) {
        if (pending != null || trainUntil != 0L) {
            record(event, "BLOCKED", "Telegram занят отправкой или обучением. Очередь и повтор отключены")
            return
        }
        val startupIssue = when {
            !store.enabled -> "Telegram: режим имитации выключен. Включите его в настройках Telegram"
            !unlocked() -> "Telegram: разблокируйте экран"
            !app.settings.masterEnabled -> "Отключён главный переключатель CallShift"
            else -> null
        }
        if (startupIssue != null) { record(event, "BLOCKED", startupIssue); return }
        val dry = !store.live
        pending = Pending(event, number, text, dry)
        record(event, "TG_UI_PENDING", if (dry) "Telegram: проверка имитации без отправки" else "Telegram: открываем приложение для отправки")
        main.postDelayed(timeout, 45_000)
        if (dry) { launchTelegram(); return }
        // Limit is reserved before any window interaction, like the TDLib path:
        // process death or an error never leads to an unaccounted message.
        app.appScope.launch {
            val ok = runCatching { TelegramLimitStore.reserve(this@TelegramUiService) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (!ok) { finish("BLOCKED", "Лимит Telegram: ${app.settings.telegramDailyLimit} автоответов за 24 часа исчерпан или счётчик недоступен. Повторов нет") }
                else launchTelegram()
            }
        }
    }

    private fun launchTelegram() {
        try {
            val launch = TelegramUiPolicy.PACKAGES.firstNotNullOfOrNull { packageManager.getLaunchIntentForPackage(it) } ?: error("Telegram отсутствует")
            startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main.postDelayed(tick, 800)
        } catch (_: Exception) { finish("BLOCKED", "Android не разрешил открыть Telegram") }
    }

    private fun step() {
        if (busy) return
        val p = pending ?: return
        if (p.gestureAt != 0L) {
            if (SystemClock.elapsedRealtime() - p.gestureAt > 3000) {
                finish("BLOCKED", "Android не подтвердил завершение касания Telegram. Продолжения и повтора не будет")
            } else main.postDelayed(tick, 150)
            return
        }
        try {
            if (!store.enabled || !unlocked() || !app.settings.masterEnabled) {
                finish("BLOCKED", "Telegram: режим выключен или экран заблокирован"); return
            }
            val root = rootInActiveWindow
            if (root == null) { main.postDelayed(tick, 500); return }
            if (!isTelegram(root.packageName?.toString())) {
                if (SystemClock.elapsedRealtime() - p.started > 15_000) {
                    finish("BLOCKED", "Telegram не открылся за отведённое время"); return
                }
                main.postDelayed(tick, 500); return
            }
            val all = nodes(root)
            if (all.any { it.isVisibleToUser && it.isPassword }) {
                finish("BLOCKED", "Telegram: экран пароля или защищённое поле; навигация и отправка запрещены"); return
            }
            when (p.phase) {
                Phase.SEND -> sendPhase(p, all)
                Phase.COMPOSE -> composePhase(p, all)
                Phase.RESULTS -> resultsPhase(p, all)
                else -> openPhase(p, all)
            }
        } catch (_: Exception) {
            finish("BLOCKED", "Telegram: непредвиденная структура экрана; отправка остановлена")
        }
    }

    private fun openPhase(p: Pending, all: List<Node>) {
        // Opened straight into a chat (restored state): typing there would touch a
        // foreign conversation. Refuse and ask the user to return to the chat list.
        if (all.any { it.isVisibleToUser && TelegramUiPolicy.looksLikeSend(descOf(it), it.viewIdResourceName ?: "") }) {
            finish("BLOCKED", "Telegram открылся внутри чата. Вернитесь к списку чатов и повторите вызов; отправка отменена")
            return
        }
        val field = editor(all)
        if (field != null) {
            // The list screen with its search box is in front: type the number.
            val args = Bundle().apply { putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p.number) }
            if (runCatching { field.performAction(Node.ACTION_SET_TEXT, args) }.getOrDefault(false)) {
                p.queryAt = SystemClock.elapsedRealtime(); p.phase = Phase.RESULTS
                main.postDelayed(tick, 1500); return
            }
        }
        val entry = all.firstOrNull { it.isVisibleToUser && it.isEnabled &&
            (it.viewIdResourceName?.lowercase()?.contains("search") == true ||
                descOf(it).lowercase().contains("поиск") || descOf(it).lowercase().contains("search")) }
        if (entry != null && SystemClock.elapsedRealtime() - p.started > 1200) {
            if (entry.isClickable && entry.performAction(Node.ACTION_CLICK)) { main.postDelayed(tick, 900); return }
        }
        if (SystemClock.elapsedRealtime() - p.started > 14_000) {
            finish("BLOCKED", "Не найдено поле поиска Telegram. Откройте список чатов Telegram и повторите вызов")
            return
        }
        main.postDelayed(tick, 700)
    }

    private fun resultsPhase(p: Pending, all: List<Node>) {
        if (editor(all) == null && SystemClock.elapsedRealtime() - p.queryAt > 4000) {
            finish("BLOCKED", "Поле поиска Telegram закрылось во время поиска номера"); return
        }
        if (all.any { textOf(it).contains("ничего не найдено", true) || textOf(it).contains("нет результатов", true) }) {
            finish("BLOCKED", "Telegram не находит адресата по номеру. Номер должен быть в телефонной книге с аккаунтом Telegram")
            return
        }
        val digits = TelegramUiPolicy.digits(p.number)
        val rows = all.filter { it.isVisibleToUser && TelegramUiPolicy.digits(textOf(it)) == digits }
            .mapNotNull { clickableAncestor(it) }.filter { it.refresh() && it.isVisibleToUser && it.isEnabled }
            .distinctBy { Rect().also { r -> it.getBoundsInScreen(r) } }
        when {
            rows.size == 1 -> {
                val row = rows.single()
                if (!row.performAction(Node.ACTION_CLICK)) {
                    finish("BLOCKED", "Android не позволил открыть найденный чат Telegram"); return
                }
                p.phase = Phase.COMPOSE
                main.postDelayed(tick, 1200)
            }
            rows.isEmpty() -> {
                if (SystemClock.elapsedRealtime() - p.queryAt > 9_000) {
                    finish("BLOCKED", "Telegram не находит чат по номеру. Номер должен быть в телефонной книге с аккаунтом Telegram")
                    return
                }
                main.postDelayed(tick, 700)
            }
            else -> finish("BLOCKED", "По номеру найдено несколько чатов; выбор неоднозначен, отправка запрещена")
        }
    }

    private fun clickableAncestor(n: Node): Node? {
        var cur: Node? = n
        repeat(6) { cur = cur?.parent ?: return null; if (cur!!.isClickable) return cur }
        return null
    }

    private fun composePhase(p: Pending, all: List<Node>) {
        val input = editor(all) ?: run {
            val ph = composerPlaceholder(all)
            if (ph != null && !p.composerTried) {
                p.composerTried = true
                runCatching { (if (ph.isClickable) ph else clickableAncestor(ph))?.performAction(Node.ACTION_CLICK) }
                main.postDelayed(tick, 900); return
            }
            if (SystemClock.elapsedRealtime() - p.queryAt > 12_000) {
                finish("BLOCKED", "Чат Telegram не открылся; поле сообщения не найдено")
            } else main.postDelayed(tick, 700)
            return
        }
        val draft = textOf(input)
        if (draft.isNotEmpty()) {
            // A draft byte-identical to this run's text is CallShift's own leftover:
            // clear it once. Anything else belongs to the user and is never touched.
            if (!p.edited && !p.clearedLeftover && draft == p.text) {
                val args = Bundle().apply { putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
                if (runCatching { input.performAction(Node.ACTION_SET_TEXT, args) }.getOrDefault(false)) {
                    p.clearedLeftover = true
                    record(p.event, "TG_UI_PENDING", "Очищен остаточный черновик предыдущей попытки CallShift")
                    main.postDelayed(tick, 600); return
                }
            }
            finish("BLOCKED", "В поле сообщения Telegram есть текст, который не принадлежит CallShift. Отправка запрещена; чужой черновик не тронут")
            return
        }
        val args = Bundle().apply { putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, p.text) }
        if (!runCatching { input.performAction(Node.ACTION_SET_TEXT, args) }.getOrDefault(false)) {
            if (SystemClock.elapsedRealtime() - p.sendWaitAt > 6000 && p.sendWaitAt != 0L) {
                finish("BLOCKED", "Android не позволил ввести текст в поле Telegram"); return
            }
            if (p.sendWaitAt == 0L) p.sendWaitAt = SystemClock.elapsedRealtime()
            main.postDelayed(tick, 600); return
        }
        p.edited = true; p.sendWaitAt = SystemClock.elapsedRealtime(); p.phase = Phase.SEND
        main.postDelayed(tick, 700)
    }

    private fun sendPhase(p: Pending, all: List<Node>) {
        val input = editor(all)
        if (input == null || textOf(input) != p.text) {
            if (SystemClock.elapsedRealtime() - p.sendWaitAt > 6000) {
                finish("BLOCKED", if (input == null) "Поле сообщения Telegram исчезло до нажатия; отправка остановлена"
                    else "Введённый текст не удержался в поле Telegram; отправка остановлена")
                return
            }
            main.postDelayed(tick, 600); return
        }
        val candidates = sendCandidates(all, input)
        val rule = store.learnedSend()
        val installed = version()
        val target: Node? = when {
            rule != null -> {
                if (rule.version != installed) {
                    finish("BLOCKED", "Telegram обновился: сохранённая кнопка не проверена для новой версии. Повторите обучение кнопки отправки")
                    return
                }
                val matched = candidates.filter { it.viewIdResourceName == rule.targetId &&
                    (it.className?.toString() == rule.targetClass) &&
                    (rule.desc.isEmpty() || descOf(it) == rule.desc) }
                when (matched.size) {
                    1 -> matched.single()
                    0 -> { finish("BLOCKED", "Обученная кнопка отправки не найдена на экране. Откройте чат заново или повторите обучение"); return }
                    else -> { finish("BLOCKED", "Обученная кнопка отправки соответствует нескольким элементам; отправка запрещена"); return }
                }
            }
            else -> when (candidates.size) {
                1 -> candidates.single()
                0 -> { finish("BLOCKED", "Кнопка отправки Telegram не распознана. Обучите кнопку отправки в настройках Telegram"); return }
                else -> { finish("BLOCKED", "Возле поля несколько кнопок отправки; выбор неоднозначен. Обучите кнопку отправки в настройках Telegram"); return }
            }
        }
        if (!target!!.refresh() || !target.isVisibleToUser || !target.isEnabled) {
            finish("BLOCKED", "Кнопка отправки стала недоступной в момент нажатия"); return
        }
        if (p.dry) {
            finish("TG_UI_CHECKED", "Проверка пройдена: чат найден по номеру, текст введён, кнопка отправки распознана. Реальная отправка выключена, черновик остался в поле")
            return
        }
        val pressed = run {
            val useGesture = rule?.gesture ?: !target.isClickable
            if (!useGesture) target.performAction(Node.ACTION_CLICK) else tapGesture(p, target)
        }
        if (!pressed) { finish("BLOCKED", "Android не позволил нажать кнопку отправки Telegram"); return }
        scheduleProof(p)
    }

    private fun sendCandidates(all: List<Node>, input: Node): List<Node> {
        val bounds = Rect(); input.getBoundsInScreen(bounds)
        return all.filter { n ->
            if (!n.isVisibleToUser || !n.isEnabled || n.isEditable || n.isPassword) return@filter false
            if (!TelegramUiPolicy.looksLikeSend(descOf(n), n.viewIdResourceName ?: "")) return@filter false
            val b = Rect(); n.getBoundsInScreen(b)
            b.width() > 0 && b.height() > 0 && b.left >= bounds.left && b.top >= bounds.top - bounds.height() * 2 &&
                b.top <= bounds.bottom + bounds.height() * 2
        }
    }

    private fun tapGesture(p: Pending, target: Node): Boolean {
        val accessibility = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (accessibility == null || accessibility.isTouchExplorationEnabled) return false
        val scale = runCatching {
            if (Build.VERSION.SDK_INT >= 33) magnificationController.magnificationConfig?.scale
            else magnificationController.scale
        }.getOrNull()
        if (scale != 1f) return false
        val bounds = Rect(); target.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0, 60)).build()
        p.gestureAt = SystemClock.elapsedRealtime()
        val submitted = runCatching { dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription) { p.gestureAt = 0; main.postDelayed(tick, 400) }
            override fun onCancelled(description: GestureDescription) { p.gestureAt = 0; main.postDelayed(tick, 400) }
        }, main) }.getOrDefault(false)
        if (!submitted) p.gestureAt = 0
        return submitted
    }

    /** Read-only post-click proof, same as MAX: the draft vanishing is local evidence
     * only. No further actions, no retries. */
    private fun scheduleProof(p: Pending) {
        p.proofChecks = 0
        main.postDelayed(proofTick, 2000)
    }
    private val proofTick = object : Runnable {
        override fun run() {
            val p = pending ?: return
            val editor = rootInActiveWindow
                ?.takeIf { unlocked() && isTelegram(it.packageName?.toString()) }
                ?.let { editor(nodes(it)) }
            if (editor == null) {
                finish("TG_UI_UNKNOWN", "Нажатие выполнено; подтвердить результат по экрану не удалось. Проверьте сообщение у получателя; повторов нет")
                return
            }
            if (textOf(editor).isEmpty()) {
                finish("TG_UI_SENT_LOCAL", "Нажатие выполнено; текст исчез из поля — сообщение предположительно отправлено. Доставку подтвердите у получателя; повторов нет")
                return
            }
            p.proofChecks++
            if (p.proofChecks >= 3) {
                finish("TG_UI_UNKNOWN", "Нажатие выполнено; текст остался в поле — вероятно, сообщение не ушло. Проверьте чат вручную; повторов нет")
                return
            }
            main.postDelayed(this, 2000)
        }
    }

    private fun finish(result: String, message: String) {
        val p = pending
        pending = null; busy = false
        main.removeCallbacks(tick); main.removeCallbacks(timeout); main.removeCallbacks(proofTick)
        if (p != null) record(p.event, result, message)
    }

    private fun record(event: CallEvent, result: String, message: String) {
        app.appScope.launch {
            runCatching { app.eventStore.record(event.copy(result = result, errorMessage = message,
                errorCode = if (result in listOf("BLOCKED", "TG_UI_UNKNOWN")) "telegram_ui_$result" else null)) }
        }
    }

    // ===================== send-button training =====================
    private fun trainStep() {
        if (trainUntil == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now > trainUntil) { endTraining(TelegramUiPolicy.TrainStatus.TIMEOUT); return }
        if (trainTrial != null) { verifyTick(); main.postDelayed(trainTick, 700); return }
        val root = rootInActiveWindow ?: run { main.postDelayed(trainTick, 700); return }
        if (!isTelegram(root.packageName?.toString())) { main.postDelayed(trainTick, 700); return }
        val all = runCatching { nodes(root) }.getOrDefault(emptyList())
        val input = editor(all)
        if (input == null) {
            if (trainArmed) { endTraining(TelegramUiPolicy.TrainStatus.NO_EDITOR); return }
            trainTypeTried = false; trainEditorSeenAt = 0
            store.sendOutcome(TelegramUiPolicy.TrainStatus.WAIT_CHAT)
            // Некоторые сборки Telegram держат поле ввода как некликабельную заглушку,
            // пока в него не тапнут: будим настоящий редактор вместо вечного ожидания.
            val ph = composerPlaceholder(all)
            if (ph != null && now - trainFocusAt > 2500) {
                trainFocusAt = now
                runCatching { (if (ph.isClickable) ph else clickableAncestor(ph))?.performAction(Node.ACTION_CLICK) }
            }
            main.postDelayed(trainTick, 700); return
        }
        if (trainEditorSeenAt == 0L) trainEditorSeenAt = now
        val draft = textOf(input)
        // Черновик исчез сразу после подготовки: нажатие отправило сообщение, хотя
        // Android не передал событие нажатия. Сохраняем кнопку, которую видели одной
        // у поля ввода, — как жест, тем же способом, что в MAX.
        if (trainArmed && lastArmedDraft == TelegramUiPolicy.TRAIN_TEXT && draft.isEmpty()) {
            if (soleId != null) {
                store.learnSend(TelegramUiPolicy.SendRule(version(), soleId!!, soleClass, soleDesc, true))
                store.markVersion(version())
                endTraining(TelegramUiPolicy.TrainStatus.SAVED)
                toast("Кнопка отправки Telegram распознана и сохранена")
            } else endTraining(TelegramUiPolicy.TrainStatus.SOURCE_MISSING)
            return
        }
        lastArmedDraft = draft
        if (trainArmed) {
            val cands = sendCandidates(all, input)
            if (cands.size == 1) {
                soleId = cands.single().viewIdResourceName ?: ""
                soleClass = cands.single().className?.toString() ?: ""
                soleDesc = descOf(cands.single())
            } else { soleId = null; soleClass = ""; soleDesc = "" }
        }
        when {
            draft == TelegramUiPolicy.TRAIN_TEXT -> {
                trainArmed = true
                store.sendOutcome(TelegramUiPolicy.TrainStatus.WAIT_TAP)
                if (!trainToastShown) { trainToastShown = true; toast("Черновик готов. Нажмите кнопку отправки Telegram ОДИН раз") }
            }
            draft.isEmpty() && !trainTypeTried && now - trainEditorSeenAt > 1500 -> {
                val args = Bundle().apply { putCharSequence(Node.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, TelegramUiPolicy.TRAIN_TEXT) }
                if (runCatching { input.performAction(Node.ACTION_SET_TEXT, args) }.getOrDefault(false)) trainTypeTried = true
                else {
                    val anc = clickableAncestor(input)
                    if (anc != null && now - trainFocusAt > 2000) { trainFocusAt = now; runCatching { anc.performAction(Node.ACTION_CLICK) } }
                }
            }
        }
        main.postDelayed(trainTick, 600)
    }

    /** Некликабельная заглушка поля ввода, которую некоторые сборки Telegram показывают до первого касания. */
    private fun composerPlaceholder(all: List<Node>): Node? = all.firstOrNull { n ->
        n.isVisibleToUser && n.isEnabled && (
            descOf(n).contains("сообщ", true) || descOf(n).contains("message", true) ||
            textOf(n).contains("сообщ", true) || textOf(n).contains("Написать", true))
    }

    private fun handleTrainClick(event: AccessibilityEvent) {
        if (trainTrial != null) return
        val source = event.source
        val chain = mutableListOf<Pair<String, String>>()
        var cur = source
        repeat(5) {
            val n = cur ?: return@repeat
            chain += (n.viewIdResourceName ?: "") to (n.className?.toString() ?: "")
            cur = n.parent
        }

        val root = rootInActiveWindow
        val all = if (root != null && isTelegram(root.packageName?.toString())) nodes(root) else emptyList()
        val input = editor(all)
        val draft = textOf(input)
        if (input == null || (draft != TelegramUiPolicy.TRAIN_TEXT && !trainArmed)) {
            endTraining(TelegramUiPolicy.TrainStatus.FOREIGN); return
        }
        val candidates = sendCandidates(all, input!!)
        // Отсутствие источника события допустимо только при однозначном ответе.
        if (chain.isEmpty() && candidates.size != 1) { endTraining(TelegramUiPolicy.TrainStatus.SOURCE_MISSING); return }
        val target: Node? = run {
            val direct = chain.mapNotNull { step -> candidates.singleOrNull {
                it.viewIdResourceName == step.first && it.className?.toString() == step.second } }
            when {
                direct.size == 1 -> direct.single()
                candidates.size == 1 -> candidates.single()
                else -> null
            }
        }
        if (target == null) { endTraining(TelegramUiPolicy.TrainStatus.FOREIGN); return }
        if (target == input) { endTraining(TelegramUiPolicy.TrainStatus.FOREIGN); return }
        trainTrial = Trial(target.viewIdResourceName ?: "", target.className?.toString() ?: "",
            descOf(target), target.isClickable)
        trainVerifyAt = SystemClock.elapsedRealtime()
        store.sendOutcome(TelegramUiPolicy.TrainStatus.VERIFY)
    }

    private fun verifyTick() {
        val trial = trainTrial ?: return
        val root = rootInActiveWindow
        val all = if (root != null && isTelegram(root.packageName?.toString())) nodes(root) else emptyList()
        val input = editor(all)
        if (input == null) {
            if (SystemClock.elapsedRealtime() - trainVerifyAt > 4000) { endTraining(TelegramUiPolicy.TrainStatus.NOT_SENT); return }
            return
        }
        if (textOf(input).isEmpty()) {
            val rule = TelegramUiPolicy.SendRule(version(), trial.id, trial.className, trial.desc, !trial.clickable)
            store.learnSend(rule); store.markVersion(version())
            endTraining(TelegramUiPolicy.TrainStatus.SAVED)
            toast("Кнопка отправки Telegram распознана и сохранена")
            return
        }
        if (SystemClock.elapsedRealtime() - trainVerifyAt > 8000) endTraining(TelegramUiPolicy.TrainStatus.NOT_SENT)
    }

    private fun endTraining(status: TelegramUiPolicy.TrainStatus) {
        trainUntil = 0L; trainArmed = false; trainToastShown = false; trainTypeTried = false
        trainEditorSeenAt = 0; trainVerifyAt = 0; trainTrial = null
        lastArmedDraft = ""; soleId = null; soleClass = ""; soleDesc = ""
        main.removeCallbacks(trainTick)
        store.sendOutcome(status)
    }

    companion object {
        @Volatile private var instance: TelegramUiService? = null
        @Volatile private var startIssue: TelegramUiPolicy.TrainStatus? = null
        val connected get() = instance != null
        val running get() = instance?.pending != null
        val trainingActive get() = instance?.trainUntil != 0L

        /** Live explanation for the settings screen: an active session wins, then the
         * most recent start refusal, then the persisted outcome. */
        fun trainStatus(context: android.content.Context): TelegramUiPolicy.TrainStatus =
            startIssue ?: instance?.takeIf { it.trainUntil != 0L }?.let { TelegramUiStore(it).sendOutcome() }
                ?: TelegramUiStore(context).sendOutcome()

        fun startSendTraining(): Boolean {
            val service = instance
            if (service == null) { startIssue = TelegramUiPolicy.TrainStatus.NO_SERVICE; return false }
            if (service.pending != null || service.trainUntil != 0L) {
                startIssue = TelegramUiPolicy.TrainStatus.BUSY; return false
            }
            check(Looper.myLooper() == Looper.getMainLooper())
            return runCatching {
                startIssue = null
                service.trainUntil = SystemClock.elapsedRealtime() + 120_000
                service.trainArmed = false; service.trainToastShown = false; service.trainTypeTried = false
                service.trainEditorSeenAt = 0; service.trainVerifyAt = 0; service.trainTrial = null
                service.store.sendOutcome(TelegramUiPolicy.TrainStatus.WAIT_CHAT)
                service.main.post(service.trainTick)
                service.toast("Обучение запущено: откройте любой безопасный чат Telegram, например «Избранное»")
                true
            }.getOrElse { service.endTraining(TelegramUiPolicy.TrainStatus.ERROR); false }
        }
        fun endSendTraining() { instance?.main?.post { instance?.endTraining(TelegramUiPolicy.TrainStatus.STOPPED) } }
        fun stopNow() { instance?.main?.post { instance?.stop("Остановлено пользователем") } }
        fun submit(event: CallEvent, number: String, text: String) {
            val service = instance ?: return
            service.main.post { service.begin(event, number, text) }
        }
    }
}
