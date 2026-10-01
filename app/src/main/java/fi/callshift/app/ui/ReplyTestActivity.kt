package fi.callshift.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.*
import fi.callshift.app.max.*
import kotlinx.coroutines.launch

/** Every execution is explicit and single-channel. Readiness never sends a message. */
class ReplyTestActivity : AppCompatActivity() {
    private val app by lazy { CallShiftApp.from(this) }
    private lateinit var sim: Spinner
    private lateinit var channel: Spinner
    private lateinit var number: EditText
    private lateinit var message: EditText
    private lateinit var readiness: TextView
    private lateinit var result: TextView
    private var accounts = emptyList<Pair<String, String>>()
    private val channels = ReplyChannel.labels.keys.toList()
    private var lastReason: String? = null
    private var submitting = false
    private var lastManual: fi.callshift.app.forward.CallEvent? = null
    private val permission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        loadAccounts(); refresh()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastReason = savedInstanceState?.getString("test_reason")
        val ui = FormUi(this)
        ui.title("Проверка каналов и тестовый ответ")
        ui.hint("Без входящего звонка. Выберите SIM, как будто позвонили на неё: MAX использует сохранённое соответствие SIM → обычный MAX / копия. Для SMS это SIM отправки. Telegram и ручные мессенджеры не переключают аккаунт по SIM: проверьте свой аккаунт отдельно.")
        ui.hint("Это тест канала, а не проверка условий правила, сброса звонка или фонового запуска. Проверка настроек ничего не отправляет. Реальная отправка — только после отдельного подтверждения, по одному каналу. Автоматического повтора и запасной SMS нет.")
        fun button(label: String, action: () -> Unit) = ui.add(MaterialButton(this).apply { text = label; setOnClickListener { action() } })
        ui.header("SIM входящего звонка / SIM теста")
        sim = ui.add(Spinner(this))
        ui.header("Канал")
        channel = ui.add(Spinner(this).apply {
            adapter = ArrayAdapter(this@ReplyTestActivity, android.R.layout.simple_spinner_dropdown_item, channels.map { ReplyChannel.labels.getValue(it) })
        })
        number = ui.add(EditText(this).apply {
            hint = "Номер получателя"; inputType = InputType.TYPE_CLASS_PHONE
            setText(savedInstanceState?.getString("number").orEmpty())
            setTextColor(getColor(fi.callshift.app.R.color.text_primary))
        })
        message = ui.add(EditText(this).apply {
            hint = "Текст — до 201 символа"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(savedInstanceState?.getString("message") ?: "Тестовый ответ CallShift")
            setTextColor(getColor(fi.callshift.app.R.color.text_primary))
        })
        button("Выдать разрешения для SIM и выбранного канала") {
            val needed = (ReplyPermissions.missing(this, listOf(selectedChannel())).toList() + Manifest.permission.READ_PHONE_STATE)
                .distinct().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (needed.isEmpty()) { loadAccounts(); refresh() } else permission.launch(needed.toTypedArray())
        }
        button("Настроить SIM → основной MAX / копия") { startActivity(Intent(this, MaxRoutesActivity::class.java)) }
        button("Настройки автоматизации MAX") { startActivity(Intent(this, MaxSimpleActivity::class.java)) }
        button("Настройки аккаунта Telegram") { startActivity(Intent(this, fi.callshift.app.telegram.TelegramAccountActivity::class.java)) }
        readiness = ui.hint("")
        button("Проверить настройки всех каналов — без отправки") { loadAccounts(); refresh() }
        button("MAX: тест навигации БЕЗ сообщения") { prepare(dryMax = true) }
        button("Тест выбранного канала / подготовить ручной ответ") { prepare(dryMax = false) }
        result = ui.hint(if (lastReason == null) "Тест ещё не запущен. Результат появится здесь и в журнале."
            else "Тест уже был запрошен. Ожидаем данные журнала; не повторяйте при неизвестном результате.")
        button("Открыть подготовленный ручной ответ") {
            val e = lastManual
            if (e == null) { tell("Сначала подготовьте тест ручного канала. Если процесс был закрыт, ответ остаётся в журнале."); return@button }
            startActivity(Intent(this, fi.callshift.app.messaging.MessengerReplyActivity::class.java)
                .putExtra("number", e.numberE164).putExtra("text", e.replyText).putExtra("channel", e.replyChannel))
        }
        button("Журнал всех результатов") { startActivity(Intent(this, LogActivity::class.java)) }
        ui.hint("SMS расходует тариф и лимит SMS-частей. Тесты учитывают минутную паузу по каналу/номеру и обычные лимиты; история не очищается. MAX без отправки всё равно может выбрать копию, выйти из чата, искать номер и открыть карточку. Пауза MAX сейчас общая для номера на разных SIM — повторный тест в течение минуты может быть пропущен.")
        setContentView(ui.scroll)
        loadAccounts()
        savedInstanceState?.getString("sim")?.let { id -> accounts.indexOfFirst { it.first == id }.takeIf { it >= 0 }?.let { sim.setSelection(it + 1) } }
        channel.setSelection(channels.indexOf(savedInstanceState?.getString("channel")).coerceAtLeast(0))
        val changed = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) { refresh() }
            override fun onNothingSelected(parent: AdapterView<*>?) { refresh() }
        }
        sim.onItemSelectedListener = changed; channel.onItemSelectedListener = changed
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.eventStore.changes.collect { updateResult() }
            }
        }
    }
    override fun onResume() { super.onResume(); if (::readiness.isInitialized) { loadAccounts(); refresh(); lifecycleScope.launch { updateResult() } } }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("test_reason", lastReason)
        outState.putString("number", number.text.toString()); outState.putString("message", message.text.toString())
        outState.putString("sim", accountId()); outState.putString("channel", selectedChannel())
    }
    private fun accountId(): String? = accounts.getOrNull(sim.selectedItemPosition - 1)?.first
    private fun selectedChannel() = channels.getOrElse(channel.selectedItemPosition) { ReplyChannel.SMS }
    private fun loadAccounts() {
        val old = accountId()
        accounts = app.telecom.phoneAccounts().toList()
        sim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Выберите SIM явно") + accounts.mapIndexed { i, item -> "${i + 1}. ${item.second}" })
        sim.setSelection(accounts.indexOfFirst { it.first == old }.let { if (it < 0) 0 else it + 1 })
    }
    private fun installed(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
    private fun refresh() {
        if (!::readiness.isInitialized) return
        val id = accountId()
        val route = id?.let { MaxRouteStore(this).routes()[it] }
        val max = MaxUiStore(this)
        val maxVersion = runCatching { packageManager.getPackageInfo(MaxUiPolicy.PACKAGE, 0).longVersionCode }.getOrDefault(-1)
        readiness.text = buildString {
            appendLine("Локальная проверка — не гарантия отправки или доставки:")
            appendLine("SIM: ${accounts.firstOrNull { it.first == id }?.second ?: "не выбрана / нет разрешения Телефон"}")
            appendLine("SMS: разрешение ${if (app.smsReplier.hasPermission()) "есть" else "нужно"}; SIM для отправки ${if (app.smsReplier.canUseAccount(id)) "определена" else "не определена"}. Лимит проверяется при отправке.")
            appendLine("MAX UI: приложение ${if (maxVersion >= 0) "есть" else "не найдено"}; служба ${if (MaxUiService.connected) "подключена" else "отключена"}; профиль ${if (max.header.isNotEmpty() && max.input.isNotEmpty() && max.version == maxVersion) "актуален" else "нужно настроить"}.")
            appendLine("MAX для этой SIM: ${if (route == null) "не настроен" else route.label ?: "единственный аккаунт"}; проверка маршрута ${if (route != null && id != null && MaxRouteStore(this@ReplyTestActivity).tested(id, route)) "пройдена" else "нужна"}.")
            appendLine("MAX: главный переключатель ${app.settings.masterEnabled}; режим ${if (!max.enabled) "выключен" else if (max.live) "реальная отправка" else "без отправки"}; диагностика ${MaxUiService.diagnostics.active()}.")
            appendLine("Контакты для поиска имени MAX: ${if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) "разрешены" else "нет разрешения; доступен поиск по номеру"}.")
            appendLine("Telegram API: настройки ${if (app.telegram.configured()) "есть" else "не заданы"}; автоотправка ${app.telegram.autoEnabled()}; текущая авторизация ${if (app.telegram.authorization.value == "authorizationStateReady") "готова" else "не подтверждена в этом процессе"}. SIM не выбирает аккаунт Telegram.")
            run {
                val tgUi = fi.callshift.app.telegram.TelegramUiStore(this)
                appendLine("Telegram имитация: режим ${if (!tgUi.enabled) "выключен (работает TDLib)" else if (tgUi.live) "реальная отправка" else "проверка без отправки"}; служба ${if (fi.callshift.app.telegram.TelegramUiService.connected) "подключена" else "не подключена"}; кнопка ${if (tgUi.learnedSend() != null) "обучена" else "не обучена"}.")
            }
            for (c in listOf("WHATSAPP", "TELEGRAM", "MAX")) appendLine("${ReplyChannel.labels[c]}: ${if (ManualReply.packages(c).any(::installed)) "приложение обнаружено" else "приложение не обнаружено"}; аккаунт и отправка вручную.")
            append("Другой мессенджер: системное меню отправки, получатель и аккаунт вручную.")
        }
    }
    private fun prepare(dryMax: Boolean) {
        if (submitting) return
        val c = if (dryMax) MaxUiPolicy.CHANNEL else selectedChannel()
        val id = accountId()
        val n = app.normalizer.normalize(number.text.toString())
        val text = message.text.toString().trim()
        val error = ReplyTestPolicy.error(c, n.e164, text, id, app.telecom.phoneAccounts().keys)
        if (error != null || n.isEmergency) { tell(error ?: "Экстренный номер не используется для теста"); return }
        if (c == MaxUiPolicy.CHANNEL && MaxUiService.running) { tell("MAX уже выполняет сценарий. Дождитесь результата или остановите его в настройках MAX."); return }
        if (c == MaxUiPolicy.CHANNEL && !MaxUiService.connected) { tell("Сначала подключите службу MAX"); return }
        if (c == MaxUiPolicy.CHANNEL && !dryMax && (!MaxUiStore(this).enabled || !MaxUiStore(this).live || MaxUiService.diagnostics.active())) {
            tell("Для реальной отправки MAX сначала пройдите тест без сообщения, завершите диагностику и отдельно разрешите отправку в настройках MAX."); return
        }
        val label = app.telecom.phoneAccounts()[id] ?: return
        val description = when {
            dryMax -> "Будет проверена навигация MAX с выбранной SIM, без ввода сообщения. Реальная отправка MAX выключится и сама не восстановится."
            ReplyChannel.isManual(c) -> "Будет подготовлен ручной ответ. Приложение не отправит сообщение само и не выберет аккаунт по SIM."
            c == ReplyChannel.SMS -> "Будет запрошена реальная SMS с этой SIM. Возможна плата за несколько SMS-частей."
            c == MaxUiPolicy.CHANNEL -> "Будет запрошена реальная отправка из MAX, настроенного для этой SIM. При неизвестном результате повторять опасно."
            tgImitation -> "Будет выполнена имитация касаний в приложении Telegram: поиск чата по номеру, ввод текста, нажатие обученной кнопки. Одна попытка, без повторов."
            else -> "Будет запрошена реальная отправка из подключённого личного аккаунта Telegram, независимо от SIM."
        }
        AlertDialog.Builder(this).setTitle("Подтвердите тест")
            .setMessage("${ReplyChannel.labels[c]}\nSIM: $label\nПолучатель: ${n.e164}\nТекст: $text\n\n$description\nТест не проверяет правила звонков. Автоматических повторов нет. Повтор при неизвестном результате может создать дубликат.")
            .setPositiveButton("Запустить один раз") { _, _ ->
                if (submitting) return@setPositiveButton
                if (c == MaxUiPolicy.CHANNEL && MaxUiService.running) { tell("MAX уже занят другой попыткой; новая не запущена."); return@setPositiveButton }
                if (id !in app.telecom.phoneAccounts()) { tell("SIM изменилась. Повторно выберите карту."); return@setPositiveButton }
                if (c == MaxUiPolicy.CHANNEL && !dryMax && (!MaxUiStore(this).live || MaxUiService.diagnostics.active())) { tell("Режим MAX изменился. Отправка не запущена."); return@setPositiveButton }
                submitting = true; lastManual = null
                val reason = "manual_channel_test:${java.util.UUID.randomUUID()}"
                lastReason = reason
                result.text = "Тест запрошен. Ожидаем запись результата; это ещё не отправка."
                if (dryMax) MaxUiService.collectDiagnostics(true)
                val ctx = CallContext(e164 = n.e164, phoneAccount = PhoneAccountRef(id!!, label))
                val decision = Decision(Verdict.DISALLOW_REJECT, reason = reason,
                    ruleName = "Тест из приложения", matchedAction = ReplyTestPolicy.action(c, text))
                // Survives opening MAX/rotation; no automatic restart on Activity recreation.
                app.appScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    try { app.smsReplier.maybeReply(ctx, decision, text) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { if (!isDestroyed) result.text = "Не удалось завершить тест. Проверьте журнал; не повторяйте при неизвестном результате." }
                    finally { submitting = false; if (!isDestroyed) updateResult() }
                }
            }.setNegativeButton("Отмена", null).show()
    }
    private suspend fun updateResult() {
        val reason = lastReason ?: return
        val e = app.eventStore.events().firstOrNull { it.reason == reason } ?: return
        if (isDestroyed) return
        lastManual = e.takeIf { ManualReply.valid(it.replyChannel, it.numberE164, it.replyText) }
        result.text = "${EventView.kind(e).title}\n${e.errorMessage.orEmpty()}\nSIM: ${e.sim}\nКод: ${e.result}\nОткрытие приложения или нажатие кнопки не доказывает доставку."
    }
    private fun tell(text: String) { AlertDialog.Builder(this).setMessage(text).setPositiveButton("Понятно", null).show() }
}
