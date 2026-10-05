package fi.callshift.app.ui

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R
import fi.callshift.app.max.MaxRouteStore
import fi.callshift.app.max.MaxUiService
import fi.callshift.app.max.MaxUiStore
import fi.callshift.app.sms.SmsBudgetStore
import fi.callshift.app.telegram.TelegramLimitStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Раздел «Мессенджеры» — единственный сводный экран. Собран на FormUi, том же
 * компоненте, что и проверенные экраны настройки. Все журналы — одна кнопка. */
class MessengerHubActivity : AppCompatActivity() {
    private val app by lazy { CallShiftApp.from(this) }
    private val maxStore by lazy { MaxUiStore(this) }
    private val maxRoutes by lazy { MaxRouteStore(this) }
    private val maxInstalled by lazy {
        runCatching { packageManager.getPackageInfo(fi.callshift.app.domain.MaxUiPolicy.PACKAGE, 0).longVersionCode }.getOrDefault(-1)
    }
    private lateinit var summaryLine: TextView
    private lateinit var maxLine: TextView
    private lateinit var maxDetails: TextView
    private lateinit var tgLine: TextView
    private lateinit var tgDetails: TextView
    private lateinit var smsLine: TextView
    private lateinit var smsDetails: TextView
    private lateinit var faultLine: TextView
    private lateinit var maxLimitButton: MaterialButton
    private lateinit var tgLimitButton: MaterialButton
    private lateinit var smsLimitButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { buildScreen() }.onFailure { fail ->
            setContentView(TextView(this).apply {
                val p = (20 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.text_primary))
                text = "Раздел не открылся: ${fail.javaClass.simpleName}: ${fail.message}.\nВерсия ${fi.callshift.app.BuildConfig.VERSION_NAME} (${fi.callshift.app.BuildConfig.VERSION_CODE})"
            })
        }
    }

    private fun buildScreen() {
        val ui = FormUi(this)
        ui.title("Мессенджеры — всё в одном месте")
        summaryLine = ui.hint("Обновляем…")
        ui.add(MaterialButton(this).apply {
            text = "Скопировать всё для поддержки"
            isAllCaps = false
            setOnClickListener { copyEverything() }
        })

        ui.header("MAX — автоответ звонком в приложении MAX")
        maxLine = ui.hint("")
        maxDetails = ui.hint("CallShift сам открывает MAX, находит звонившего по номеру и вводит текст из правила. Кнопка отправки распознаётся по доступному действию/подписи; обучение нужно только если проверка не может определить её однозначно. Чужие черновики не трогаются. После переподключения службы разрешение реальной отправки выключается.")
            .apply { visibility = View.GONE }
        ui.add(MaterialButton(this).apply {
            text = "Настройка MAX: SIM, автоответы, тест"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MessengerHubActivity, fi.callshift.app.max.MaxSimpleActivity::class.java)) }
        })
        maxLimitButton = ui.add(MaterialButton(this).apply {
            isAllCaps = false
            setOnClickListener { askLimit("Лимит MAX в сутки", maxStore.sendLimit()) { v -> runCatching { maxStore.setSendLimit(v) }.onFailure { toast("Не удалось сохранить") } } }
        })
        ui.add(toggle(maxDetails))

        ui.header("Telegram — автоответ через приложение Telegram")
        tgLine = ui.hint("")
        tgDetails = ui.hint("В настройках Telegram — способ отправки и общий переключатель автоответов. При имитации CallShift сам находит чат по номеру; обучение кнопки — запасной вариант после изменения интерфейса.")
            .apply { visibility = View.GONE }
        ui.add(MaterialButton(this).apply {
            text = "Telegram: способ отправки и автоответы"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MessengerHubActivity, fi.callshift.app.telegram.TelegramAccountActivity::class.java)) }
        })
        tgLimitButton = ui.add(MaterialButton(this).apply {
            isAllCaps = false
            setOnClickListener { askLimit("Лимит Telegram в сутки", app.settings.telegramDailyLimit) { v -> runCatching { app.settings.setTelegramDailyLimit(v) }.onFailure { toast("Не удалось сохранить") } } }
        })
        ui.add(toggle(tgDetails))

        ui.header("SMS — ответы по правилам")
        smsLine = ui.hint("")
        smsDetails = ui.hint("Ответ обычной SMS с SIM звонка по шаблону правила. Лимит в частях за 24 часа, включая тестовые. Длинный текст может тарифицироваться частями.")
            .apply { visibility = View.GONE }
        ui.add(MaterialButton(this).apply {
            text = "Правила SMS"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MessengerHubActivity, SmsRuleActivity::class.java)) }
        })
        ui.add(MaterialButton(this).apply {
            text = "SMS: защита, расходы и копия"
            isAllCaps = false
            setOnClickListener { startActivity(Intent(this@MessengerHubActivity, SmsSafetyActivity::class.java)) }
        })
        smsLimitButton = ui.add(MaterialButton(this).apply {
            isAllCaps = false
            setOnClickListener { askLimit("Лимит SMS в сутки (частей)", app.settings.smsDailyLimit) { v -> runCatching { app.settings.setSmsSafety(v, app.settings.smsCooldownPerSim) }.onFailure { toast("Не удалось сохранить") } } }
        })
        ui.add(toggle(smsDetails))

        faultLine = ui.hint("").apply { setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.status_error)) }
        ui.hint("CallShift ${fi.callshift.app.BuildConfig.VERSION_NAME} (${fi.callshift.app.BuildConfig.VERSION_CODE})")

        setContentView(ui.scroll)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    runCatching { refresh() }.onFailure { f ->
                        faultLine.text = "Ошибка обновления: ${f.javaClass.simpleName}: ${f.message}"
                    }
                    delay(1000)
                }
            }
        }
    }

    private fun toggle(details: TextView) = TextView(this).apply {
        text = "Подробнее ▸"
        textSize = 13f
        setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.brand_accent))
        setOnClickListener {
            val open = details.visibility == View.VISIBLE
            details.visibility = if (open) View.GONE else View.VISIBLE
            text = if (open) "Подробнее ▸" else "Свернуть ▴"
        }
    }

    private fun maxIssues(): List<String> {
        val out = mutableListOf<String>()
        if (maxInstalled < 0) return listOf("установите приложение MAX")
        if (!MaxUiService.connected) out += "включите службу MAX в спец. возможностях Android"
        if (maxStore.header.isEmpty() || maxStore.input.isEmpty()) out += "сохраните профиль чата на экране настройки MAX"
        else if (maxStore.version != maxInstalled) out += "MAX обновился — повторите профиль чата"
        val accounts = app.telecom.phoneAccounts().keys
        val routes = maxRoutes.routes()
        if (accounts.isEmpty()) out += "разрешите доступ к SIM и выберите SIM"
        else if (accounts.any { routes[it] == null }) out += "выберите MAX для используемой SIM"
        else if (accounts.any { id -> routes[id]?.let { !maxRoutes.tested(id, it) } == true })
            out += "завершите проверку MAX без отправки для используемой SIM"
        if (!app.settings.masterEnabled) out += "включите главный переключатель CallShift"
        if (!maxStore.enabled) out += "включите режим на экране настройки MAX"
        else if (!maxStore.live) out += "разрешите реальную отправку; после переподключения службы это нужно сделать снова"
        return out
    }

    private fun tgIssues(): List<String> {
        val tgUi = fi.callshift.app.telegram.TelegramUiStore(this)
        val out = mutableListOf<String>()
        if (!tgUi.enabled) {
            if (!runCatching { app.telegram.autoEnabled() }.getOrDefault(false)) {
                out += "выберите режим и включите автоответы на экране «Аккаунт Telegram»"
            } else if (!runCatching { app.telegram.configured() }.getOrDefault(false)) {
                out += "подключите личный аккаунт Telegram на экране «Аккаунт Telegram»"
            }
        } else {
            val installed = fi.callshift.app.domain.TelegramUiPolicy.PACKAGES.any { pkg ->
                runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess
            }
            if (!installed) out += "установите Telegram"
            if (!fi.callshift.app.telegram.TelegramUiService.connected) out += "включите службу Telegram в спец. возможностях Android"
            if (!tgUi.live) out += "разрешите реальную отправку; после переподключения службы это нужно сделать снова"
        }
        if (!app.settings.masterEnabled) out += "включите главный переключатель CallShift"
        return out
    }

    private fun smsIssues(): List<String> =
        if (!app.smsReplier.hasPermission()) listOf("выдайте разрешение на SMS на главном экране") else emptyList()

    private fun refresh() {
        fun line(v: TextView, issues: List<String>, ok: String) {
            v.text = if (issues.isEmpty()) "● Готов — $ok" else "● Настроить — Дальше: ${issues.first()}"
            v.setTextColor(ContextCompat.getColor(this, if (issues.isEmpty()) R.color.status_ok else R.color.status_warn))
        }
        line(maxLine, maxIssues(), "SIM и режим отправки настроены")
        line(tgLine, tgIssues(), "режим отправки настроен")
        line(smsLine, smsIssues(), "разрешение есть")
        maxLimitButton.text = "Лимит MAX в сутки: ${maxStore.sendLimit()}"
        tgLimitButton.text = "Лимит Telegram в сутки: ${app.settings.telegramDailyLimit}"
        smsLimitButton.text = "Лимит SMS в сутки: ${app.settings.smsDailyLimit} частей"
        val ready = listOf(maxIssues().isEmpty(), tgIssues().isEmpty(), smsIssues().isEmpty()).count { it }
        summaryLine.text = "Готовых каналов: $ready из 3. Канал отправки выбирается в правиле звонка."
        lifecycleScope.launch {
            val tgUsed = runCatching { TelegramLimitStore.used(this@MessengerHubActivity) }.getOrDefault(-1)
            val smsUsed = runCatching { SmsBudgetStore.used(this@MessengerHubActivity) }.getOrDefault(-1)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (isDestroyed) return@withContext
                maxLimitButton.isEnabled = true
                tgLimitButton.text = "Лимит Telegram в сутки: ${app.settings.telegramDailyLimit} (использовано ${if (tgUsed >= 0) tgUsed else "—"})"
                smsLimitButton.text = "Лимит SMS в сутки: ${app.settings.smsDailyLimit} частей (использовано ${if (smsUsed >= 0) smsUsed else "—"})"
            }
        }
    }

    /** Одна кнопка вместо разбросанных журналов: версия, устройство, состояние
     * каналов и последние события — единым текстом в буфер обмена. */
    private fun copyEverything() {
        val d = resources.displayMetrics
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val head = buildString {
            appendLine("CallShift ${fi.callshift.app.BuildConfig.VERSION_NAME} (${fi.callshift.app.BuildConfig.VERSION_CODE})")
            appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.SDK_INT}, экран ${d.widthPixels}x${d.heightPixels} dpi=${d.densityDpi}, тема=${if (night == android.content.res.Configuration.UI_MODE_NIGHT_YES) "тёмная" else "светлая"}")
            appendLine("MAX: служба=${MaxUiService.connected}; Telegram имитация: служба=${fi.callshift.app.telegram.TelegramUiService.connected}")
            appendLine("MAX: ${maxLine.text}; Telegram: ${tgLine.text}; SMS: ${smsLine.text}")
            appendLine("— Последние события: —")
        }
        lifecycleScope.launch {
            val tail = runCatching {
                app.eventStore.events(limit = 60).takeLast(12).joinToString("\n") { e ->
                    val time = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.ROOT).format(java.util.Date(e.ts))
                    "$time [${e.strategy}] ${e.result}: ${e.errorMessage?.take(160) ?: "—"}"
                }
            }.getOrDefault("журнал недоступен")
            getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                ClipData.newPlainText("CallShift support", head + "\n" + tail))
            toast("Скопировано — пришлите текст в чат")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun askLimit(title: String, current: Int, save: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
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
