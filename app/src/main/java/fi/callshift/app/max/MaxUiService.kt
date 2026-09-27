package fi.callshift.app.max

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.os.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo as Node
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.MaxUiPolicy
import fi.callshift.app.forward.CallEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** Deterministic, opt-in UI interaction. Never searches chats or clicks coordinates. */
class MaxUiService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val store by lazy { MaxUiStore(this) }
    private val app by lazy { CallShiftApp.from(this) }
    private data class Pending(val event: CallEvent, val number: String, val text: String, val dry: Boolean, var edited: Boolean = false)
    private var pending: Pending? = null
    private var busy = false
    private val tick = Runnable { step() }
    private val timeout = Runnable { finish("BLOCKED", "Время ожидания MAX истекло. Повтора не будет; проверьте черновик вручную") }

    private val logs = kotlinx.coroutines.channels.Channel<CallEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    override fun onCreate() {
        super.onCreate()
        app.appScope.launch { for (event in logs) runCatching { app.eventStore.record(event) } }
    }
    override fun onServiceConnected() {
        instance = this
        // Reconnection/reboot must not silently resume permission to send messages.
        store.modes(store.enabled, false)
    }
    override fun onInterrupt() { stop("Служба прервана") }
    override fun onDestroy() { stop("Служба отключена"); instance = null; logs.close(); super.onDestroy() }
    fun stop(reason: String) { finish("BLOCKED", reason); runCatching { store.modes(false, false) } }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != MaxUiPolicy.PACKAGE) return
        if (pending == null && SystemClock.elapsedRealtime() < probeUntil) capture()
        if (pending != null && !busy) { main.removeCallbacks(tick); main.postDelayed(tick, 300) }
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
    /** Only a phone label in a toolbar, never a phone number in message history. */
    private fun header(n: Node): Boolean {
        if (!n.isVisibleToUser || n.isEditable || n.isPassword || MaxUiPolicy.phone(n.text?.toString().orEmpty()) == null) return false
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
        val root = rootInActiveWindow ?: return
        if (!unlocked() || root.packageName?.toString() != MaxUiPolicy.PACKAGE) return
        val all = nodes(root)
        val h = all.filter { header(it) && !it.viewIdResourceName.isNullOrBlank() }.singleOrNull() ?: return
        val input = all.filter { it.isVisibleToUser && it.isEnabled && it.isEditable && !it.isPassword && !it.viewIdResourceName.isNullOrBlank() }.singleOrNull() ?: return
        candidate = Profile(h.viewIdResourceName, input.viewIdResourceName, version(), MaxUiPolicy.phone(h.text.toString())!!, SystemClock.elapsedRealtime())
    }
    private fun begin(event: CallEvent, number: String, text: String) {
        if (pending != null) { log(event, "BLOCKED", "MAX уже занят другой попыткой; очередь и повтор отключены"); return }
        if (!store.enabled || !unlocked() || store.version != version() || store.header.isEmpty() || store.input.isEmpty()) {
            log(event, "BLOCKED", "MAX UI: включите проверку, настройте профиль и разблокируйте экран"); return
        }
        pending = Pending(event, number, text, !store.live)
        log(event, "UI_PENDING", "Ожидаем проверку открытого чата MAX. Отправка не подтверждена")
        main.postDelayed(timeout, 15_000)
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
            val root = rootInActiveWindow ?: return
            if (root.packageName?.toString() != MaxUiPolicy.PACKAGE) return // timeout, not another application
            val all = nodes(root)
            val h = all.filter { it.viewIdResourceName == store.header && header(it) }
            val inputs = all.filter { it.viewIdResourceName == store.input && it.isEditable && it.isEnabled && it.isVisibleToUser && !it.isPassword }
            val input = inputs.singleOrNull()
            val draft = input?.text?.toString().orEmpty()
            val reason = MaxUiPolicy.block(MaxUiPolicy.Check(store.enabled, unlocked(), root.packageName.toString(),
                store.version == version(), h.size, h.singleOrNull()?.text?.toString()?.let(MaxUiPolicy::phone), p.number,
                inputs.size, if (p.edited && draft == p.text) "" else draft))
            if (reason != null) { finish("BLOCKED", reason); return }
            if (p.dry) { finish("UI_CHECKED", "Проверка: номер в шапке и пустое поле совпали. Текст не введён, кнопка не нажата; работа кнопки отправки ещё не проверена"); return }
            if (!store.live || !app.settings.masterEnabled) { finish("BLOCKED", "Разрешение отправки или главный переключатель выключены"); return }
            if (!p.edited) {
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
                        val recipient = ns.filter { it.viewIdResourceName == store.header && header(it) }.singleOrNull()
                        val field = ns.filter { it.viewIdResourceName == store.input && it.isVisibleToUser && it.isEnabled && it.isEditable && !it.isPassword }.singleOrNull()
                        if (MaxUiPolicy.phone(recipient?.text?.toString().orEmpty()) != p.number || field == null || !field.text.isNullOrEmpty()) {
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
            // Remove pending BEFORE clicking: no event, timeout or reconnection can retry.
            pending = null; main.removeCallbacks(tick); main.removeCallbacks(timeout)
            log(p.event, "UI_UNKNOWN", "Передано управление кнопке MAX. Результат неизвестен; повторов нет")
            val clicked = send.performAction(Node.ACTION_CLICK)
            log(p.event, "UI_UNKNOWN", if (clicked) "Нажата кнопка MAX. Отправка и доставка НЕ подтверждены; повторов нет"
                else "Результат нажатия MAX неизвестен. Проверьте чат вручную; повторов нет")
        } catch (_: Exception) { finish("UI_UNKNOWN", "Сценарий MAX остановлен с неопределённым результатом. Проверьте чат; повторов нет") }
    }
    private fun finish(result: String, message: String) {
        val p = pending
        pending = null; busy = false
        main.removeCallbacks(tick); main.removeCallbacks(timeout)
        if (p != null) log(p.event, result, message)
    }
    private fun log(event: CallEvent, result: String, message: String) {
        logs.trySend(event.copy(result = result, errorMessage = message, errorCode = if (result == "BLOCKED") "max_ui_blocked" else null))
    }
    data class Profile(val header: String, val input: String, val version: Long, val phone: String, val at: Long)
    companion object {
        @Volatile private var instance: MaxUiService? = null
        @Volatile var probeUntil = 0L
        @Volatile var candidate: Profile? = null
        val connected get() = instance != null
        fun stopNow() { instance?.main?.post { instance?.stop("Остановлено пользователем") } }
        suspend fun submit(context: android.content.Context, event: CallEvent, number: String, text: String) = withContext(Dispatchers.Main) {
            val service = instance
            if (service == null) CallShiftApp.from(context).eventStore.record(event.copy(result = "BLOCKED", errorMessage = "Служба специальных возможностей MAX не подключена"))
            else service.begin(event, number, text)
        }
    }
}
