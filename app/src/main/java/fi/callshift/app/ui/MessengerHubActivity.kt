package fi.callshift.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.SmsSafety
import fi.callshift.app.max.MaxUiService
import fi.callshift.app.max.MaxUiStore
import fi.callshift.app.sms.SmsBudgetStore
import fi.callshift.app.telegram.TelegramLimitStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import fi.callshift.app.ui.FormUi

/** Every messenger in one place: MAX, Telegram, SMS - status, setup, daily limits. */
class MessengerHubActivity : AppCompatActivity() {
    private val app by lazy { CallShiftApp.from(this) }
    private val maxStore by lazy { MaxUiStore(this) }
    private lateinit var maxStatus: TextView
    private lateinit var telegramStatus: TextView
    private lateinit var smsStatus: TextView
    private lateinit var maxLimitButton: MaterialButton
    private lateinit var tgLimitButton: MaterialButton
    private lateinit var smsLimitButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = FormUi(this)
        ui.title("Мессенджеры")
        ui.hint("Все каналы автоответов в одном месте: статус, настройка и суточные лимиты. Правила звонков выбирают канал; лимиты — защита от зацикливания.")

        ui.header("MAX — автоответ по звонку (интерфейс)")
        maxStatus = ui.hint("")
        fun hubButton(label: String, action: (MaterialButton) -> Unit): MaterialButton =
            ui.add(MaterialButton(this).apply { text = label; setOnClickListener { action(this) } })
        maxLimitButton = hubButton("Лимит MAX в сутки: ${maxStore.sendLimit()}") {
            askLimit("Лимит автоответов MAX в сутки", maxStore.sendLimit()) { v ->
                runCatching { maxStore.setSendLimit(v) }
                refreshLimits()
            }
        }
        hubButton("Настройка MAX: SIM, обучение кнопки, тест") {
            startActivity(Intent(this, fi.callshift.app.max.MaxSimpleActivity::class.java))
        }

        ui.header("Telegram — автоответ по звонку (аккаунт)")
        telegramStatus = ui.hint("")
        tgLimitButton = hubButton("Лимит Telegram в сутки: ${app.settings.telegramDailyLimit}") {
            askLimit("Лимит автоответов Telegram в сутки", app.settings.telegramDailyLimit) { v ->
                runCatching { app.settings.setTelegramDailyLimit(v) }
                refreshLimits()
            }
        }
        hubButton("Аккаунт Telegram: вход и автоотправка") {
            startActivity(Intent(this, fi.callshift.app.telegram.TelegramAccountActivity::class.java))
        }

        ui.header("SMS — ответы по правилам")
        smsStatus = ui.hint("")
        smsLimitButton = hubButton("Лимит SMS в сутки: ${app.settings.smsDailyLimit} частей") {
            askLimit("Лимит SMS в сутки (частей)", app.settings.smsDailyLimit) { v ->
                runCatching { app.settings.setSmsSafety(v, app.settings.smsCooldownPerSim) }
                refreshLimits()
            }
        }
        hubButton("SMS: правила и шаблоны ответов") {
            startActivity(Intent(this, SmsRuleActivity::class.java))
        }
        hubButton("SMS: защита, расходы и копия") {
            startActivity(Intent(this, SmsSafetyActivity::class.java))
        }
        ui.hint("Лимиты: от 1 до 1000 в сутки. Это предохранитель от зацикливания сценария, а не ограничение нормальной работы. Изменение действует сразу и сохраняется.")

        setContentView(ui.scroll)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refreshStatuses()
                    refreshLimits()
                    delay(1000)
                }
            }
        }
    }

    private fun askLimit(title: String, current: Int, save: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
        }
        AlertDialog.Builder(this).setTitle(title)
            .setMessage("Введите число от 1 до 1000. Действует сразу.")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v !in 1..1000) Toast.makeText(this, "Введите число от 1 до 1000", Toast.LENGTH_SHORT).show()
                else {
                    save(v)
                    Toast.makeText(this, "Сохранено: $v в сутки", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Отмена", null).show()
    }

    private fun refreshLimits() {
        maxLimitButton.text = "Лимит MAX в сутки: ${maxStore.sendLimit()}"
        tgLimitButton.text = "Лимит Telegram в сутки: ${app.settings.telegramDailyLimit}"
        smsLimitButton.text = "Лимит SMS в сутки: ${app.settings.smsDailyLimit} частей"
    }

    private fun refreshStatuses() {
        val maxMode = when {
            !maxStore.enabled -> "выключен"
            maxStore.live -> "реальная отправка разрешена"
            else -> "проверка без отправки"
        }
        val maxUi = MaxUiStore(this)
        maxStatus.text = "Режим: $maxMode\\nКнопка отправки: ${if (maxUi.learnedSend() != null) "обучена" else "не обучена"}\\nСлужба: ${if (MaxUiService.connected) "подключена" else "не подключена"}"
        val tgAuto = runCatching { app.telegram.autoEnabled() }.getOrDefault(false)
        lifecycleScope.launch {
            val tgUsed = runCatching { TelegramLimitStore.used(this@MessengerHubActivity) }.getOrDefault(-1)
            val smsUsed = runCatching { SmsBudgetStore.used(this@MessengerHubActivity) }.getOrDefault(-1)
            telegramStatus.text = "Автоотправка: ${if (tgAuto) "включена" else "выключена (нужен вход)"}\\nИспользовано за 24 ч: ${if (tgUsed >= 0) tgUsed else "счётчик недоступен"} / ${app.settings.telegramDailyLimit}"
            smsStatus.text = "Резерв за 24 ч: ${if (smsUsed >= 0) smsUsed else "счётчик недоступен"} / ${app.settings.smsDailyLimit} частей"
        }
    }
}
