package fi.callshift.app.telegram

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.TelegramUiPolicy
import fi.callshift.app.ui.FormUi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Authorization secrets exist only in fields/memory and are sent directly to TDLib. */
class TelegramAccountActivity : AppCompatActivity() {
    private val client by lazy { CallShiftApp.from(this).telegram }
    private lateinit var secret: EditText
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val ui = FormUi(this)
        ui.title("Telegram — личный аккаунт")
        ui.hint("Неофициальный клиент Telegram на TDLib. Отправляет от вашего аккаунта после сброса. Номер звонящего передаётся Telegram для поиска; контакты телефона не импортируются. Если адресат недоступен по номеру, сообщения не будет. Возможны ограничения Telegram.")
        ui.hint("Нужны собственные API ID и API hash приложения с my.telegram.org → API development tools. Это НЕ токен бота. Не присылайте их, коды входа или пароль в чат поддержки.")
        ui.add(MaterialButton(this).apply {
            text = "Открыть my.telegram.org"
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps"))) } }
        })
        fun field(hintText: String, type: Int) = ui.add(EditText(this).apply {
            hint = hintText; inputType = type; isSaveEnabled = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setTextColor(getColor(fi.callshift.app.R.color.text_primary))
        })
        val apiId = field("API ID (для первого подключения)", InputType.TYPE_CLASS_NUMBER)
        val apiHash = field("API hash", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val state = ui.add(TextView(this).apply { setTextColor(getColor(fi.callshift.app.R.color.text_primary)); textSize = 16f })
        val status = ui.add(TextView(this).apply { setTextColor(getColor(fi.callshift.app.R.color.text_secondary)) })
        ui.add(MaterialButton(this).apply {
            text = "Подключить / восстановить сессию"
            setOnClickListener {
                val id = apiId.text.toString().trim(); val hash = apiHash.text.toString().trim()
                apiHash.text.clear()
                lifecycleScope.launch {
                    runCatching { if (id.isNotEmpty() || hash.isNotEmpty()) client.configure(id, hash) else client.start() }
                        .onFailure { Toast.makeText(this@TelegramAccountActivity, it.message ?: "Ошибка подключения", Toast.LENGTH_LONG).show() }
                }
            }
        })
        secret = field("Сначала подключите Telegram", InputType.TYPE_CLASS_TEXT)
        ui.add(MaterialButton(this).apply {
            text = "Продолжить вход"
            setOnClickListener {
                val value = secret.text.toString()
                secret.text.clear()
                isEnabled = false
                lifecycleScope.launch {
                    try { client.authenticate(value) }
                    catch (error: Exception) { Toast.makeText(this@TelegramAccountActivity, error.message ?: "Ошибка входа", Toast.LENGTH_LONG).show() }
                    finally { isEnabled = true }
                }
            }
        })
        ui.hint("Код может прийти в приложение Telegram, не по SMS. Если включена двухэтапная защита, затем потребуется пароль. Код и пароль не сохраняются. Регистрация нового аккаунта, QR-авторизация и платный вход здесь не поддерживаются.")
        ui.hint("Для TDLib войдите в свой аккаунт ниже. Затем включите один общий переключатель автоответов для выбранного способа отправки. В правиле используйте канал «Telegram — автоматически (мой аккаунт)»; старые ручные правила не меняются. Повторных попыток и запасных SMS нет.")

        // ---- Имитация касаний: тот же принцип, что в MAX ----
        ui.title("Отправка имитацией касаний (как в MAX)")
        ui.hint("CallShift сам откроет Telegram, найдёт чат по номеру и нажмёт кнопку. Одна попытка, без повторов. Нужна служба спец. возможностей.")
        val uiStore = TelegramUiStore(this)
        var syncingSwitches = false
        val uiStatus = ui.add(TextView(this).apply { setTextColor(getColor(fi.callshift.app.R.color.text_primary)); textSize = 14f })
        val imitation = ui.add(SwitchMaterial(this).apply {
            text = "Использовать имитацию касаний вместо TDLib"
            setTextColor(getColor(fi.callshift.app.R.color.text_primary))
            isChecked = uiStore.enabled
        })
        val autoReplies = ui.add(SwitchMaterial(this).apply {
            text = "Разрешить автоответы Telegram"
            setTextColor(getColor(fi.callshift.app.R.color.text_primary))
            isChecked = if (uiStore.enabled) uiStore.live else client.autoEnabled()
        })
        fun syncSwitches() {
            val current = TelegramUiStore(this@TelegramAccountActivity)
            syncingSwitches = true
            imitation.isChecked = current.enabled
            autoReplies.isChecked = if (current.enabled) current.live else client.autoEnabled()
            syncingSwitches = false
        }
        imitation.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (checked && !TelegramUiService.connected) {
                syncSwitches()
                Toast.makeText(this@TelegramAccountActivity,
                    "Сначала включите службу «CallShift — Telegram (имитация касаний)» в специальных возможностях Android",
                    Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            runCatching {
                if (uiStore.enabled) TelegramUiService.stopNow()
                client.enableAuto(false)
                uiStore.modes(checked, false)
            }.onFailure {
                syncSwitches()
                Toast.makeText(this@TelegramAccountActivity, "Не удалось изменить способ отправки", Toast.LENGTH_LONG).show()
            }.onSuccess {
                syncSwitches()
                Toast.makeText(this@TelegramAccountActivity,
                    "Способ отправки изменён. Включите автоответы отдельным переключателем.", Toast.LENGTH_LONG).show()
            }
        }
        autoReplies.setOnCheckedChangeListener { _, checked ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (!checked) {
                runCatching {
                    if (uiStore.enabled) uiStore.modes(true, false) else client.enableAuto(false)
                }.onFailure {
                    Toast.makeText(this@TelegramAccountActivity, "Не удалось выключить автоответы", Toast.LENGTH_LONG).show()
                }
                syncSwitches()
                return@setOnCheckedChangeListener
            }
            if (uiStore.enabled) {
                val installed = TelegramUiPolicy.PACKAGES.any { pkg ->
                    runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
                }
                if (!installed || !TelegramUiService.connected) {
                    syncSwitches()
                    Toast.makeText(this@TelegramAccountActivity,
                        if (!installed) "Установите Telegram" else "Включите службу Telegram в специальных возможностях Android",
                        Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                runCatching { client.enableAuto(false); uiStore.modes(true, true) }
                    .onFailure {
                        syncSwitches()
                        Toast.makeText(this@TelegramAccountActivity, "Не удалось разрешить автоответы", Toast.LENGTH_LONG).show()
                    }.onSuccess { syncSwitches() }
            } else {
                if (!client.configured() || client.authorization.value != "authorizationStateReady") {
                    syncSwitches()
                    Toast.makeText(this@TelegramAccountActivity,
                        "Сначала подключите аккаунт Telegram и дождитесь статуса «Аккаунт подключён»",
                        Toast.LENGTH_LONG).show()
                    return@setOnCheckedChangeListener
                }
                runCatching { uiStore.modes(false, false); client.enableAuto(true) }
                    .onFailure {
                        syncSwitches()
                        Toast.makeText(this@TelegramAccountActivity, "Не удалось разрешить автоответы", Toast.LENGTH_LONG).show()
                    }.onSuccess { syncSwitches() }
            }
        }
        ui.hint("Выберите способ отправки. Один общий переключатель выше разрешает или останавливает автоответы этим способом. Переключение TDLib/имитация сбрасывает разрешение — его нужно включить отдельно.")
        ui.header("Запасное обучение после обновления Telegram")
        ui.add(MaterialButton(this).apply {
            text = "Обучить / переобучить кнопку Telegram"
            setOnClickListener {
                if (!TelegramUiService.startSendTraining()) {
                    Toast.makeText(this@TelegramAccountActivity, TelegramUiService.trainStatus(this@TelegramAccountActivity).explanation, Toast.LENGTH_LONG).show()
                }
            }
        })
        ui.add(MaterialButton(this).apply {
            text = "Сбросить обучение кнопки"
            setOnClickListener {
                runCatching {
                    uiStore.forgetSend()
                    uiStore.modes(uiStore.enabled, false)
                    syncSwitches()
                    Toast.makeText(this@TelegramAccountActivity, "Обучение сброшено; автоответы выключены", Toast.LENGTH_SHORT).show()
                }
            }
        })
        ui.hint("Обычно Telegram определяет кнопку отправки сам. Переобучайте её только если после обновления кнопка не распознаётся; обучение сохраняет только способ нажатия, не чат и не текст.")
        ui.add(MaterialButton(this).apply {
            text = "Выйти из Telegram и выключить автоответы"
            setOnClickListener {
                autoReplies.isChecked = false
                if (imitation.isChecked) imitation.isChecked = false
                lifecycleScope.launch {
                    runCatching { client.logout() }.onFailure {
                        Toast.makeText(this@TelegramAccountActivity, "Автоответы выключены. Если выход не завершился, отзовите сессию в Telegram → Устройства.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
        setContentView(ui.scroll)
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch { client.authorization.collect { auth ->
                    state.text = when (auth) {
                        "authorizationStateReady" -> "Аккаунт подключён"
                        "authorizationStateWaitPhoneNumber" -> "Введите номер вашего Telegram-аккаунта с +кодом страны"
                        "authorizationStateWaitCode" -> "Введите код входа из Telegram/SMS"
                        "authorizationStateWaitPassword" -> "Введите пароль двухэтапной защиты"
                        "authorizationStateWaitEmailAddress" -> "Введите адрес электронной почты для входа"
                        "authorizationStateWaitEmailCode" -> "Введите код из электронной почты"
                        "authorizationStateWaitTdlibParameters" -> "Настройка TDLib"
                        "authorizationStateLoggingOut", "authorizationStateClosing" -> "Выход из аккаунта"
                        "authorizationStateClosed", "notStarted" -> "Telegram не подключён"
                        else -> "Требуется неподдерживаемый этап входа: $auth. Автоотправка недоступна."
                    }
                    secret.text.clear(); secret.hint = state.text
                    secret.inputType = if (auth == "authorizationStateWaitPassword")
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } }
                launch { client.status.collect { status.text = it } }
                launch {
                    while (true) {
                        val s = TelegramUiStore(this@TelegramAccountActivity)
                        val repliesOn = if (s.enabled) s.live else client.autoEnabled()
                        val training = TelegramUiService.trainStatus(this@TelegramAccountActivity)
                        val trainingText = if (training == TelegramUiPolicy.TrainStatus.IDLE)
                            "обучение не требуется; автоматическое распознавание активно" else training.explanation
                        uiStatus.text = "Служба имитации: ${if (TelegramUiService.connected) "подключена" else "не подключена"}; способ ${
                            if (s.enabled) "имитация касаний" else "TDLib"}; автоответы ${
                            if (repliesOn) "разрешены" else "выключены"}; кнопка ${
                            if (s.learnedSend() != null) "обучена (запасное правило)" else "определяется автоматически"}\nСтатус обучения: $trainingText"
                        syncSwitches()
                        kotlinx.coroutines.delay(1000)
                    }
                }
            }
        }
        if (client.configured()) runCatching { client.start() }
    }
    override fun onStop() { secret.text.clear(); super.onStop() }
}
