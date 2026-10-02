package fi.callshift.app.ui

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R
import fi.callshift.app.databinding.ActivityMessengerHubBinding
import fi.callshift.app.max.MaxUiService
import fi.callshift.app.max.MaxUiStore
import fi.callshift.app.sms.SmsBudgetStore
import fi.callshift.app.telegram.TelegramLimitStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Раздел «Мессенджеры» на классической XML-разметке: тот же способ, что и все
 * проверенные экраны приложения. Любая ошибка отображается текстом на экране. */
class MessengerHubActivity : AppCompatActivity() {
    private val app by lazy { CallShiftApp.from(this) }
    private val maxStore by lazy { MaxUiStore(this) }
    private val maxInstalled by lazy {
        runCatching { packageManager.getPackageInfo(fi.callshift.app.domain.MaxUiPolicy.PACKAGE, 0).longVersionCode }.getOrDefault(-1)
    }
    private lateinit var binding: ActivityMessengerHubBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            binding = ActivityMessengerHubBinding.inflate(layoutInflater)
            binding.footerVersion.text = "CallShift ${fi.callshift.app.BuildConfig.VERSION_NAME} (${fi.callshift.app.BuildConfig.VERSION_CODE})"
            wire()
            setContentView(binding.root)
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        runCatching { refresh() }.onFailure { f ->
                            binding.faultText.visibility = android.view.View.VISIBLE
                            binding.faultText.text = "Ошибка обновления: ${f.javaClass.simpleName}: ${f.message}"
                        }
                        delay(1000)
                    }
                }
            }
        }.onFailure { fail ->
            setContentView(TextView(this).apply {
                val p = (20 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.text_primary))
                text = "Раздел «Мессенджеры» не открылся: ${fail.javaClass.simpleName}: ${fail.message}.\nПришлите этот текст целиком.\n\nВерсия ${fi.callshift.app.BuildConfig.VERSION_NAME} (${fi.callshift.app.BuildConfig.VERSION_CODE})"
            })
        }
    }

    private fun wire() {
        binding.maxToggle.setOnClickListener { flip(binding.maxToggle, binding.maxDetails) }
        binding.tgToggle.setOnClickListener { flip(binding.tgToggle, binding.tgDetails) }
        binding.smsToggle.setOnClickListener { flip(binding.smsToggle, binding.smsDetails) }
        binding.maxDetails.text = """
            • CallShift сам открывает MAX, находит звонившего по номеру, вводит текст из правила и нажимает обученную кнопку. Клон MAX выбирается по SIM звонка, «+7» и «8» равнозначны.
            • Чужие черновики не трогаются; получатель нигде не привязывается.
            • Кнопка отправки — константа: обучается один раз и переносится на новый телефон кодом настройки.
            • Итог каждой попытки — в «Журнале» на экране настройки MAX.
        """.trimIndent()
        binding.tgDetails.text = """
            • Два режима: имитация касаний (приложение само открывает Telegram, находит чат по номеру и нажимает кнопку; аккаунт в CallShift не нужен) или ваш аккаунт (TDLib).
            • Кнопка отправки определяется автоматически — обучение не нужно (запасной режим для нестандартных сборок).
            • Массовое развёртывание: на каждом телефоне — служба и два переключателя.
            • Номер подтверждается перед отправкой; одна попытка, без повторов.
        """.trimIndent()
        binding.smsDetails.text = """
            • Ответ обычной SMS с SIM звонка по шаблону правила; длинный текст считается частями, возможна плата оператора.
            • Лимит — в частях за 24 часа, включая тестовые отправки.
            • Расходы и копия ответов — на экране «Защита, расходы и копия».
        """.trimIndent()
        binding.maxOpen.setOnClickListener { startActivity(Intent(this, fi.callshift.app.max.MaxSimpleActivity::class.java)) }
        binding.tgOpen.setOnClickListener { startActivity(Intent(this, fi.callshift.app.telegram.TelegramAccountActivity::class.java)) }
        binding.smsRules.setOnClickListener { startActivity(Intent(this, SmsRuleActivity::class.java)) }
        binding.smsSafety.setOnClickListener { startActivity(Intent(this, SmsSafetyActivity::class.java)) }
        binding.maxLimit.setOnClickListener {
            askLimit("Лимит MAX в сутки", maxStore.sendLimit()) { v ->
                runCatching { maxStore.setSendLimit(v) }.onFailure { toast("Не удалось сохранить") }
            }
        }
        binding.tgLimit.setOnClickListener {
            askLimit("Лимит Telegram в сутки", app.settings.telegramDailyLimit) { v ->
                runCatching { app.settings.setTelegramDailyLimit(v) }.onFailure { toast("Не удалось сохранить") }
            }
        }
        binding.smsLimit.setOnClickListener {
            askLimit("Лимит SMS в сутки (частей)", app.settings.smsDailyLimit) { v ->
                runCatching { app.settings.setSmsSafety(v, app.settings.smsCooldownPerSim) }
                    .onFailure { toast("Не удалось сохранить") }
            }
        }
    }

    private fun flip(toggle: TextView, details: android.view.View) {
        val open = details.visibility == android.view.View.VISIBLE
        details.visibility = if (open) android.view.View.GONE else android.view.View.VISIBLE
        toggle.text = if (open) "Подробнее ▸" else "Свернуть ▴"
    }

    private fun maxIssues(): List<String> {
        val out = mutableListOf<String>()
        if (maxInstalled < 0) return listOf("установите приложение MAX")
        if (!MaxUiService.connected) out += "включите службу MAX в спец. возможностях Android"
        if (maxStore.header.isEmpty() || maxStore.input.isEmpty()) out += "сохраните профиль чата на экране настройки MAX"
        else if (maxStore.version != maxInstalled) out += "MAX обновился — повторите профиль чата"
        if (maxStore.learnedSend() == null) out += "обучите кнопку отправки"
        if (!maxStore.enabled) out += "включите режим на экране настройки MAX"
        else if (!maxStore.live) out += "разрешите реальную отправку"
        return out
    }

    private fun tgIssues(): List<String> {
        val tgUi = fi.callshift.app.telegram.TelegramUiStore(this)
        val out = mutableListOf<String>()
        if (!tgUi.enabled) {
            if (!runCatching { app.telegram.autoEnabled() }.getOrDefault(false))
                out += "выберите режим: имитация или аккаунт — на экране «Аккаунт Telegram»"
        } else {
            if (!fi.callshift.app.telegram.TelegramUiService.connected) out += "включите службу Telegram в спец. возможностях Android"
            if (!tgUi.live) out += "разрешите реальную отправку"
        }
        return out
    }

    private fun smsIssues(): List<String> =
        if (!app.smsReplier.hasPermission()) listOf("выдайте разрешение на SMS на главном экране") else emptyList()

    private fun refresh() {
        fun set(chip: TextView, next: TextView, usage: TextView, issues: List<String>, okLine: String, usageText: String) {
            val done = issues.isEmpty()
            chip.text = if (done) "● Готов" else "● Настроить"
            chip.setTextColor(ContextCompat.getColor(this, if (done) R.color.status_ok else R.color.status_warn))
            next.text = if (done) okLine else "Дальше: ${issues.first()}"
            next.setTextColor(ContextCompat.getColor(this, if (done) R.color.text_secondary else R.color.status_warn))
            usage.text = usageText
        }
        set(binding.maxChip, binding.maxNext, binding.maxUsage, maxIssues(),
            "Режим: ${if (maxStore.live) "реальная отправка" else if (maxStore.enabled) "проверка без отправки" else "выключен"} · кнопка обучена",
            "Лимит ${maxStore.sendLimit()}/24ч")
        val tgUi = fi.callshift.app.telegram.TelegramUiStore(this)
        set(binding.tgChip, binding.tgNext, binding.tgUsage, tgIssues(),
            if (tgUi.enabled) "Имитация касаний: ${if (tgUi.live) "реальная отправка" else "проверка без отправки"}"
            else "Режим аккаунта (TDLib)",
            "Кнопка отправки: определяется автоматически")
        set(binding.smsChip, binding.smsNext, binding.smsUsage, smsIssues(),
            "Разрешение есть", "Лимит ${app.settings.smsDailyLimit} частей/24ч")
        binding.maxLimit.text = "Лимит MAX в сутки: ${maxStore.sendLimit()}"
        binding.tgLimit.text = "Лимит Telegram в сутки: ${app.settings.telegramDailyLimit}"
        binding.smsLimit.text = "Лимит SMS в сутки: ${app.settings.smsDailyLimit} частей"
        lifecycleScope.launch {
            val tgUsed = runCatching { TelegramLimitStore.used(this@MessengerHubActivity) }.getOrDefault(-1)
            val smsUsed = runCatching { SmsBudgetStore.used(this@MessengerHubActivity) }.getOrDefault(-1)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isDestroyed) return@withContext
                binding.tgUsage.text = "Использовано: ${if (tgUsed >= 0) tgUsed else "—"} / ${app.settings.telegramDailyLimit} за 24ч · кнопка автоматически"
                binding.smsUsage.text = "Использовано: ${if (smsUsed >= 0) smsUsed else "—"} / ${app.settings.smsDailyLimit} частей за 24ч"
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun askLimit(title: String, current: Int, save: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
        }
        AlertDialog.Builder(this).setTitle(title)
            .setMessage("Число от 1 до 1000. Действует сразу.")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val v = input.text.toString().trim().toIntOrNull()
                if (v == null || v !in 1..1000) toast("Введите число от 1 до 1000")
                else {
                    save(v)
                    toast("Сохранено: $v в сутки")
                }
            }.setNegativeButton("Отмена", null).show()
    }
}
