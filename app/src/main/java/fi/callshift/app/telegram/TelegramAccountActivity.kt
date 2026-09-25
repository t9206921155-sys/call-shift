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
        val automatic = ui.add(SwitchMaterial(this).apply {
            text = "Разрешить автоматические ответы от моего аккаунта"
            setTextColor(getColor(fi.callshift.app.R.color.text_primary))
            isChecked = client.autoEnabled()
            setOnCheckedChangeListener { _, checked ->
                runCatching { client.enableAuto(checked) }.onFailure {
                    Toast.makeText(this@TelegramAccountActivity, "Не удалось сохранить разрешение", Toast.LENGTH_LONG).show()
                }
            }
        })
        ui.hint("После входа выберите в правиле «Telegram — автоматически (мой аккаунт)». Старые ручные правила не изменяются. Не чаще одной попытки на номер за 30 минут; без автоматических повторов, оплаты Stars и запасных SMS. Выключение разрешения останавливает новые попытки, но не отменяет уже принятые Telegram сообщения.")
        ui.add(MaterialButton(this).apply {
            text = "Выйти из Telegram и выключить автоответы"
            setOnClickListener {
                automatic.isChecked = false
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
            }
        }
        if (client.configured()) runCatching { client.start() }
    }
    override fun onStop() { secret.text.clear(); super.onStop() }
}
