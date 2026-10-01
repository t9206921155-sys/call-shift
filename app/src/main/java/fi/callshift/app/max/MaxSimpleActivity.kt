package fi.callshift.app.max

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.*
import fi.callshift.app.forward.CallEvent
import fi.callshift.app.ui.FormUi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The everyday MAX entry point. Old technical screens are optional, never wizard steps. */
class MaxSimpleActivity : AppCompatActivity() {
    private val app by lazy { CallShiftApp.from(this) }
    private val store by lazy { MaxUiStore(this) }
    private val routes by lazy { MaxRouteStore(this) }
    private val prefs by lazy { getSharedPreferences("max_ui_v1", MODE_PRIVATE) }
    private lateinit var sim: Spinner
    private lateinit var number: EditText
    private lateinit var text: EditText
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var mainButton: MaterialButton
    private var accounts = emptyList<Pair<String, String>>()
    private var waiting: String? = null
    private var inFlight = false
    private var event: CallEvent? = null
    private var sendEvent: CallEvent? = null
    private var next = MaxSetupPolicy.Step.SIM
    private var notice: String? = null
    private var displayedAccount: String? = null
    private val permission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        loadAccounts(); refresh()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waiting = savedInstanceState?.getString("waiting")
        val ui = FormUi(this)
        ui.title("Автоответ MAX")
        ui.hint("Одна SIM → один выбранный MAX. Существующие правила и SMS не меняются.")
        ui.header("На какую SIM звонят")
        sim = ui.add(Spinner(this))
        summary = ui.hint("")
        fun button(label: String, action: () -> Unit) = ui.add(MaterialButton(this).apply { this.text = label; setOnClickListener { action() } })
        button("Изменить MAX для этой SIM") { if (account() == null) message("Сначала выберите SIM") else chooseRoute() }
        ui.header("Проверочный получатель")
        number = ui.add(EditText(this).apply {
            hint = "+7… или 8…"; inputType = android.text.InputType.TYPE_CLASS_PHONE
            setTextColor(getColor(fi.callshift.app.R.color.text_primary)); setText(savedInstanceState?.getString("number").orEmpty())
        })
        ui.header("Сообщение для проверки")
        text = ui.add(EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(getColor(fi.callshift.app.R.color.text_primary))
            setText(savedInstanceState?.getString("text") ?: "Тестовый ответ CallShift")
        })
        ui.hint("Сначала — проверка без отправки. Реальное тестовое сообщение — только после отдельного подтверждения. Тексты ответов на звонки остаются в правилах.")
        status = ui.hint("")
        mainButton = button("Проверить без отправки") {
            runCatching { act() }.onFailure { message("Не удалось завершить действие. Автоматически не повторяем; проверьте состояние MAX.") }
        }
        button("Остановить MAX") {
            MaxUiService.stopNow(); runCatching { store.modes(false, false) }
            notice = "MAX выключен. SMS и правила не изменены."; refresh()
        }
        button("Обучить кнопку отправки MAX") { trainSend() }
        button("Дополнительно") {
            AlertDialog.Builder(this).setTitle("Дополнительно")
                .setItems(arrayOf("Журнал", "Тесты других каналов", "Технические настройки MAX", "Скопировать технический отчёт",
                    "Начать новую проверку без отправки", "Обучить кнопку отправки MAX",
                    "Скопировать настройку для нового телефона", "Вставить настройку с другого телефона")) { _, i ->
                    when (i) {
                        0 -> startActivity(Intent(this, fi.callshift.app.ui.LogActivity::class.java))
                        1 -> startActivity(Intent(this, fi.callshift.app.ui.ReplyTestActivity::class.java))
                        2 -> startActivity(Intent(this, MaxUiActivity::class.java))
                        3 -> {
                            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                                android.content.ClipData.newPlainText("MAX diagnostics", MaxUiService.report(this) +
                                    "\nSaved learning=" + store.cardOutcome().name + "\nSend learning=" + store.sendOutcome().name))
                            Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
                        }
                        4 -> test(false)
                        5 -> trainSend()
                        6 -> {
                            val code = store.exportSetup(routes.routes())
                            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                                android.content.ClipData.newPlainText("CallShift MAX setup", code))
                            notice = "Код настройки скопирован в буфер. На новом телефоне: Автоответ MAX → Дополнительно → «Вставить настройку с другого телефона»."
                            refresh()
                        }
                        7 -> {
                            val input = EditText(this).apply {
                                hint = "Вставьте код настройки"
                                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                            }
                            AlertDialog.Builder(this).setTitle("Перенос настройки MAX")
                                .setMessage("Код заменит профиль чата, кнопку отправки, маршруты SIM и лимит. Реальная отправка после переноса выключена — включите её после успешной проверки без отправки.")
                                .setView(input)
                                .setPositiveButton("Применить") { _, _ ->
                                    notice = store.importSetup(input.text.toString()) { a, r -> routes.put(a, r) }
                                    refresh()
                                }.setNegativeButton("Отмена", null).show()
                        }
                    }
                }.show()
        }
        setContentView(ui.scroll)
        loadAccounts(savedInstanceState?.getString("sim") ?: prefs.getString("simple_sim", null))
        sim.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (displayedAccount != account()) { event = null; sendEvent = null; notice = null; displayedAccount = account()
                    prefs.edit().putString("simple_sim", displayedAccount).apply() }
                refresh()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { refresh() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val id = account()
                    val reason = id?.let { prefs.getString("simple_test:$it", null) }
                    val sendReason = id?.let { prefs.getString("simple_send:$it", null) }
                    val events = app.eventStore.events()
                    val found = reason?.let { r -> events.firstOrNull { it.reason == r } }
                    if (id == account() && reason == id?.let { prefs.getString("simple_test:$it", null) }) event = found
                    if (id == account() && sendReason == id?.let { prefs.getString("simple_send:$it", null) })
                        sendEvent = sendReason?.let { r -> events.firstOrNull { it.reason == r } }
                    refresh()
                    delay(700)
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        if (!::sim.isInitialized) return
        loadAccounts()
        val action = waiting; waiting = null
        when (action) {
            "picker" -> {
                MaxUiService.endPickerProbe()
                if (MaxUiService.pickerCandidate != null) chooseRoute()
                else notice = "Окно выбора двух MAX не распознано. Настройка не сохранена."
            }
            "layout" -> {
                MaxUiService.endProfileProbe()
                val sample = MaxUiService.usableCandidate(this)
                if (sample == null) notice = MaxUiService.captureIssue.explanation
                else runCatching {
                    store.profile(sample.header, sample.input, sample.version); store.modes(true, false)
                    clearTest(); notice = "Экран чата сохранён. Можно проверить MAX."
                }.onFailure { notice = "Не удалось сохранить настройку." }
            }
            "send_train" -> if (MaxUiService.sendTrainingActive) {
                notice = "Обучение ещё идёт: вернитесь в MAX, дождитесь «Текст введён» и нажмите синюю стрелку. Результат придёт сам."
            } else {
                MaxUiService.endSendTraining()
                val state = MaxUiService.sendTrainingStatus.let { if (it == MaxSendTrainingPolicy.Status.IDLE) store.sendOutcome() else it }
                if (state == MaxSendTrainingPolicy.Status.SAVED) {
                    clearTest(); notice = "Кнопка отправки обучена и сохранена. Теперь нажмите «Проверить без отправки», затем тестовую отправку."
                } else if (state in setOf(MaxSendTrainingPolicy.Status.WAIT_CHAT, MaxSendTrainingPolicy.Status.WAIT_TAP, MaxSendTrainingPolicy.Status.VERIFY)) {
                    notice = state.explanation
                } else notice = state.explanation + "\nНичего не сохранено. Обучение можно повторить."
            }
            "card" -> {
                MaxUiService.endCardTraining()
                val state = MaxUiService.cardLearningStatus.let { if (it == MaxCardLearningPolicy.Status.IDLE) store.cardOutcome() else it }
                if (state in setOf(MaxCardLearningPolicy.Status.NO_TARGETS, MaxCardLearningPolicy.Status.SOURCE_MISSING,
                        MaxCardLearningPolicy.Status.REPLAY_FAILED, MaxCardLearningPolicy.Status.RETURN_UNVERIFIED)) {
                    account()?.let { prefs.edit().putString("simple_unsupported:$it", supportKey()).commit() }
                    notice = "${state.explanation}\nОтправка выключена. Повторять тот же шаг не нужно."
                } else if (state == MaxCardLearningPolicy.Status.SAVED) {
                    clearTest(); notice = "Открытие карточки настроено. Теперь можно проверить MAX."
                } else notice = "${state.explanation}\nСообщения не отправлялись."
            }
        }
        refresh()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("waiting", waiting); outState.putString("sim", account())
        outState.putString("number", number.text.toString()); outState.putString("text", text.text.toString())
    }
    private fun account() = accounts.getOrNull(sim.selectedItemPosition - 1)?.first
    private fun loadAccounts(restore: String? = account()) {
        accounts = app.telecom.phoneAccounts().toList()
        sim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Выберите SIM") + accounts.mapIndexed { i, a -> "${i + 1}. ${a.second}" })
        sim.setSelection(accounts.indexOfFirst { it.first == restore }.let { if (it < 0) 0 else it + 1 })
    }
    private fun supportKey() = "${version()}:${fi.callshift.app.BuildConfig.VERSION_CODE}:${account()?.let { routes.routes()[it] }?.hashCode()}"
    private fun configurationKey(): String = Json.encodeToString(listOf(
        supportKey(), account()?.let { routes.routes()[it] }?.let { Json.encodeToString(it) }.orEmpty(),
        store.header, store.input, store.version.toString(),
        store.learnedCard()?.let { Json.encodeToString(it) }.orEmpty(),
        store.learnedSend()?.let { Json.encodeToString(it) }.orEmpty()))
    private fun checked(): Boolean {
        val id = account() ?: return false
        val route = routes.routes()[id] ?: return false
        return MaxTestSessionPolicy.checked(event?.result, routes.tested(id, route),
            prefs.getString("simple_proof:$id", null), configurationKey())
    }
    private fun version() = runCatching { packageManager.getPackageInfo(MaxUiPolicy.PACKAGE, 0).longVersionCode }.getOrDefault(-1)
    private fun clearTest() {
        account()?.let { check(prefs.edit().remove("simple_test:$it").remove("simple_unsupported:$it").remove("simple_proof:$it").remove("simple_send:$it").commit()) }; event = null; sendEvent = null
    }
    private fun refresh() {
        if (!::mainButton.isInitialized) return
        val id = account()
        val route = id?.let { routes.routes()[it] }
        val layout = store.header.isNotEmpty() && store.input.isNotEmpty() && store.version == version()
        val cardFailure = event?.result == "BLOCKED" && event?.errorMessage?.startsWith("Не определено безопасное открытие карточки") == true
        val passed = checked()
        next = MaxSetupPolicy.next(checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
            id != null, MaxUiService.connected, route != null, layout, inFlight || MaxUiService.running,
            cardFailure, passed, store.enabled && store.live,
            id != null && prefs.getString("simple_unsupported:$id", null) == supportKey(), app.settings.masterEnabled)
        summary.text = "MAX: ${route?.label ?: if (route != null) "обычный, без клона" else "не выбран"}\nОтправка: ${if (store.enabled && store.live) "разрешена по вашим правилам" else "выключена"}"
        mainButton.text = if (next == MaxSetupPolicy.Step.LIVE && prefs.contains("simple_send:$id")) "Результат тестовой отправки" else next.button
        val waitMs = app.smsReplier.maxTestWaitMillis(app.normalizer.normalize(number.text.toString()).e164)
        val waitingCooldown = waitMs > 0 && (next == MaxSetupPolicy.Step.CHECK ||
            next == MaxSetupPolicy.Step.LIVE && !prefs.contains("simple_send:$id"))
        if (waitingCooldown) mainButton.text = "Доступно через ${(waitMs + 999) / 1000} с"
        mainButton.isEnabled = !waitingCooldown && next !in setOf(MaxSetupPolicy.Step.SIM, MaxSetupPolicy.Step.RUNNING, MaxSetupPolicy.Step.UNAVAILABLE)
        status.text = notice ?: when (next) {
            MaxSetupPolicy.Step.PHONE_PERMISSION -> "Нужен доступ к списку SIM. Сообщения это разрешение не отправляет."
            MaxSetupPolicy.Step.SIM -> "Выберите SIM, для которой нужен ответ в MAX."
            MaxSetupPolicy.Step.SERVICE -> "Один раз включите службу «CallShift — MAX» в специальных возможностях Android."
            MaxSetupPolicy.Step.ROUTE -> "Укажите, какой аккаунт MAX относится к этой SIM."
            MaxSetupPolicy.Step.LAYOUT -> "Нужно один раз распознать экран переписки. Приложение подскажет следующий шаг."
            MaxSetupPolicy.Step.RUNNING -> if (MaxUiService.gestureUnresolved)
                MaxCardLearningPolicy.Status.GESTURE_PENDING.explanation
                else "Выполняется сценарий MAX. Ничего не нажимайте в нём. Результат появится после завершения."
            MaxSetupPolicy.Step.MASTER -> "Главный переключатель CallShift выключен. Включение запустит все ваши активные правила, не только MAX."
            MaxSetupPolicy.Step.UNAVAILABLE -> "MAX пока не удалось настроить на этом устройстве. Автоматическая отправка не готова. Работающие SMS можно продолжать использовать; повторять тот же тест не нужно."
            MaxSetupPolicy.Step.CARD -> "Обычное нажатие недоступно. Можно проверить другой способ: касание распознанной области имени, без источника ручного события."
            MaxSetupPolicy.Step.ENABLE -> "Получатель и пустое поле проверены. Нажатие отправки и доставка этим тестом не проверялись."
            MaxSetupPolicy.Step.LIVE -> if (prefs.contains("simple_send:$id")) MaxTestSessionPolicy.result(sendEvent?.result, sendEvent?.errorMessage) +
                "\nДля проверки звонком используйте существующее правило с каналом «MAX — эксперимент UI» и этой SIM. Итог — в журнале."
                else "Реальные ответы разрешены. Теперь можно один раз отправить тестовый текст по номеру выше. Ответы на звонки зависят от ваших правил и выбранного в них канала."
            MaxSetupPolicy.Step.CHECK -> if (event == null) "Готово к проверке без отправки. Укажите номер выше." else when (event?.result) {
                "UI_PENDING" -> "Завершённого результата нет. Отправка не разрешена этой проверкой."
                "UI_UNKNOWN" -> "Результат неизвестен. Проверьте MAX вручную, не повторяйте отправку."
                else -> event?.errorMessage ?: "Проверка не завершена. Отправка не разрешена."
            }
        }
    }
    private fun act() {
        notice = null; refresh()
        when (next) {
            MaxSetupPolicy.Step.PHONE_PERMISSION -> permission.launch(arrayOf(Manifest.permission.READ_PHONE_STATE))
            MaxSetupPolicy.Step.SERVICE -> runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure { message("Откройте специальные возможности в настройках Android") }
            MaxSetupPolicy.Step.ROUTE -> chooseRoute()
            MaxSetupPolicy.Step.LAYOUT -> confirm("Настроить экран чата", "Откройте выбранную копию MAX и любую личную переписку с пустым полем сообщения. Подождите 3 секунды и вернитесь сюда. Настройка сохранится сама; ничего не отправляется.") {
                if (MaxUiService.startProfileProbe()) launchMax("layout") else message(MaxUiService.captureIssue.explanation)
            }
            MaxSetupPolicy.Step.CARD -> confirm("Разрешить касание имени?", "Это другой способ: Android имитирует касание в центре распознанного имени контакта. Источник ручного нажатия не нужен. Координаты не сохраняются — область определяется заново.\n\nОткройте нужную копию MAX и личный чат с пустым полем. Дальше НЕ нажимайте имя сами: CallShift дважды проверит открытие карточки и возврат, затем постарается сам вернуть этот экран с результатом.\n\nСохраняется способ открытия, не контакт. Во время настройки сообщения не отправляются. Разрешаете такой способ?", "Разрешить и проверить") {
                if (MaxUiService.startCardTraining(gesture = true)) launchMax("card") else message(MaxUiService.cardLearningStatus.explanation)
            }
            MaxSetupPolicy.Step.ENABLE -> confirm("Разрешить реальные ответы?", "Разрешение включается для MAX в целом. Отправитель выбирается по SIM звонка, а текст — по вашим существующим правилам. Проверка не доказывает доставку. Главный переключатель и правила должны быть включены; они не меняются автоматически.") {
                val id = account(); val route = id?.let { routes.routes()[it] }
                if (id == null || route == null || !checked() || !app.settings.masterEnabled || MaxUiService.running || store.version != version() || !MaxUiService.connected) {
                    message("Настройки изменились. Сначала повторите проверку."); return@confirm
                }
                runCatching { MaxUiService.diagnostics.clear(); store.modes(true, true); notice = null }
                    .onFailure { message("Не удалось сохранить разрешение.") }
                refresh()
            }
            MaxSetupPolicy.Step.MASTER -> confirm("Включить CallShift?", "Заработают все ваши активные правила: в том числе сброс звонков и SMS, если они настроены. Сами правила и их тексты не изменятся.") {
                app.settings.setMasterEnabled(true); refresh()
            }
            MaxSetupPolicy.Step.CHECK -> test(false)
            MaxSetupPolicy.Step.LIVE -> if (prefs.contains("simple_send:${account()}"))
                message(MaxTestSessionPolicy.result(sendEvent?.result, sendEvent?.errorMessage)) else test(true)
            else -> Unit
        }
    }
    private fun chooseRoute() {
        val id = account() ?: return
        if (MaxUiService.running) { message("Сначала дождитесь завершения проверки MAX."); return }
        val learned = MaxUiService.pickerCandidate ?: routes.routes().values.mapNotNull { it.picker }.distinct().singleOrNull()
        val choices = listOf("Только один MAX, без клона") + learned?.labels.orEmpty() + "Определить две копии MAX"
        AlertDialog.Builder(this).setTitle("MAX для выбранной SIM").setItems(choices.toTypedArray()) { _, index ->
            if (index == choices.lastIndex) {
                confirm("Определить копии", "В окне с двумя MAX ничего не выбирайте. Подождите 3 секунды, нажмите «Отмена» и вернитесь сюда. Читаются только признаки системного окна; сообщений не будет.") {
                    if (!MaxUiService.connected) { message("Сначала подключите службу MAX основной кнопкой."); return@confirm }
                    if (MaxUiService.startPickerProbe()) launchMax("picker") else message(MaxUiService.pickerStatus)
                }
            } else {
                val route = if (index == 0) MaxRoutePolicy.Route() else MaxRoutePolicy.Route(learned, learned!!.labels[index - 1])
                confirm("Подтвердите аккаунт", if (index == 0) "Этот вариант только для телефона БЕЗ второго MAX. При наличии клона выберите соответствующую копию." else "Использовать «${route.label}» для этой SIM? Аккаунт отправителя подтверждаете вы; Android не сообщает его регистрационный номер.") {
                    if (id !in app.telecom.phoneAccounts()) { message("SIM изменилась."); return@confirm }
                    runCatching { routes.put(id, route); clearTest() }.onFailure { message("Не удалось сохранить MAX для SIM.") }
                    refresh()
                }
            }
        }.show()
    }
    private fun test(live: Boolean) {
        val id = account(); val n = app.normalizer.normalize(number.text.toString()); val body = text.text.toString().trim()
        val error = ReplyTestPolicy.error(MaxUiPolicy.CHANNEL, n.e164, body, id, app.telecom.phoneAccounts().keys)
        if (error != null || n.isEmergency) { message(error ?: "Экстренные номера не используются для теста."); return }
        if (!app.settings.masterEnabled) { message("Главный переключатель CallShift выключен. Включите его на главной, если хотите проверить действующую автоматизацию. Самостоятельно его не включаем."); return }
        val waitMs = app.smsReplier.maxTestWaitMillis(n.e164)
        if (waitMs > 0) { message("Защита от повторов: подождите ${(waitMs + 999) / 1000} с. Автоматической отправки по таймеру не будет."); return }
        val configuration = configurationKey()
        val description = if (live) "ОДНО реальное сообщение.\nSIM: ${app.telecom.phoneAccounts()[id]}\nMAX: ${routes.routes()[id]?.label ?: "без клона"}\nПолучатель: ${n.e164}\nТекст: $body\nДержите экран разблокированным. Нажатие отправки не доказывает доставку — проверьте её у получателя. Автоповтора нет."
            else "Получатель: ${n.e164}\nMAX выберет копию, может искать контакт и открывать карточку. Держите экран разблокированным и не вмешивайтесь. Сообщение не отправляется. Реальная отправка выключится; автоматически обратно не включится."
        confirm(if (live) "Отправить тестовое сообщение?" else "Проверка без отправки", description, if (live) "Отправить один раз" else "Проверить") {
            if (MaxUiService.running || inFlight || id !in app.telecom.phoneAccounts()) { message("MAX занят или SIM изменилась."); return@confirm }
            if (id != account() || configuration != configurationKey() || !MaxUiService.connected || !app.settings.masterEnabled) {
                message("Настройки изменились. Действие не запущено."); return@confirm
            }
            if (live && !MaxTestSessionPolicy.canStartLive(checked(), store.enabled, store.live, prefs.contains("simple_send:$id"))) {
                message("Для отправки нужна завершённая проверка и отдельное разрешение. Повтора уже начатой отправки не будет."); return@confirm
            }
            if (app.smsReplier.maxTestWaitMillis(n.e164) > 0) {
                message("Для этого номера недавно начат другой сценарий. Дождитесь окончания интервала."); return@confirm
            }
            val reason = "manual_channel_test:${java.util.UUID.randomUUID()}"
            val edit = prefs.edit()
            if (live) edit.putString("simple_send:$id", reason)
            else edit.putString("simple_test:$id", reason).putString("simple_proof:$id", configuration).remove("simple_send:$id")
            if (!edit.commit()) { message("Не удалось сохранить начало проверки."); return@confirm }
            if (live) sendEvent = null else { event = null; sendEvent = null }
            notice = null
            currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0); it.clearFocus() }
            if (live) MaxUiService.diagnostics.clear() else MaxUiService.collectDiagnostics(true)
            inFlight = true; refresh()
            val context = CallContext(e164 = n.e164, phoneAccount = PhoneAccountRef(id!!, app.telecom.phoneAccounts()[id].orEmpty()))
            val decision = Decision(Verdict.DISALLOW_REJECT, reason = reason, ruleName = "Проверка MAX",
                matchedAction = ReplyTestPolicy.action(MaxUiPolicy.CHANNEL, body))
            app.appScope.launch(Dispatchers.Main) {
                try { app.smsReplier.maybeReply(context, decision, body) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { notice = "Результат не получен. Отправка не подтверждена." }
                finally { inFlight = false; if (!isDestroyed) refresh() }
            }
        }
    }
    private fun launchMax(kind: String) {
        val intent = packageManager.getLaunchIntentForPackage(MaxUiPolicy.PACKAGE)
        if (intent == null) { message("MAX не установлен."); return }
        waiting = kind
        runCatching { startActivity(intent) }.onFailure { waiting = null; message("Android не разрешил открыть MAX.") }
    }
    private fun trainSend() {
        confirm("Обучить кнопку отправки MAX?",
            "Всё остальное CallShift сделает сам. Ваше действие одно: нажать синюю стрелку отправки.\n\n1. Нажмите «Начать обучение».\n2. В MAX откройте любой безопасный чат (с самим собой или проверочный номер) — больше ничего не нужно.\n3. CallShift сам введёт короткий тестовый текст и напишет об этом.\n4. Вы нажмите синюю стрелку отправки ОДИН раз в течение 30 секунд. Сообщение уйдёт по-настоящему — поэтому только в безопасный чат.\n5. CallShift сам сохранит кнопку, вернётся и покажет результат.\n\nСохраняется только способ нажатия кнопки — не получатель, не чат и не текст. После обучения запустите «Проверить без отправки», затем тестовую отправку.",
            "Начать обучение") {
            if (MaxUiService.startSendTraining()) launchMax("send_train") else message(MaxUiService.sendTrainingStatus.explanation)
        }
    }
    private fun confirm(title: String, body: String, positive: String = "Продолжить", action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(positive) { _, _ ->
            runCatching { action() }.onFailure { message("Действие не завершено. Проверьте результат в журнале; автоматически не повторяем.") }
        }.setNegativeButton("Отмена", null).show()
    }
    private fun message(body: String) { AlertDialog.Builder(this).setMessage(body).setPositiveButton("Понятно", null).show() }
}
