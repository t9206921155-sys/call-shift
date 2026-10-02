package fi.callshift.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
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
import fi.callshift.app.max.MaxUiService
import fi.callshift.app.max.MaxUiStore
import fi.callshift.app.sms.SmsBudgetStore
import fi.callshift.app.telegram.TelegramLimitStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every messenger in one place: a compact card per channel with a concrete
 * "what to configure next" line; all background is behind «Подробнее». */
class MessengerHubActivity : AppCompatActivity() {
    private val app by lazy { CallShiftApp.from(this) }
    private val maxStore by lazy { MaxUiStore(this) }
    private val maxInstalled by lazy {
        runCatching { packageManager.getPackageInfo(fi.callshift.app.domain.MaxUiPolicy.PACKAGE, 0).longVersionCode }.getOrDefault(-1)
    }

    private class Card(
        val root: LinearLayout,
        val chip: TextView,
        val next: TextView,
        val usage: TextView,
        val details: TextView,
        val limitButton: MaterialButton,
        var expanded: Boolean = false,
    )

    private val cards = LinkedHashMap<String, Card>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.brand_primary))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }

        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        text(22f, R.color.text_primary, true).let { it.text = "Мессенджеры"; root.addView(it) }
        text(13f, R.color.text_secondary).let {
            it.text = "Статус, лимит и настройка каждого канала. Канал выбирается в правиле звонка."
            it.setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
            root.addView(it)
        }

        // ---- MAX ----
        val maxCard = makeCard("MAX", "Автоответ через приложение MAX", root)
        maxCard.limitButton.setOnClickListener {
            askLimit("Лимит MAX в сутки", maxStore.sendLimit()) { v ->
                runCatching { maxStore.setSendLimit(v) }.onFailure { toast("Не удалось сохранить") }
            }
        }
        maxCard.details.text = """
            • CallShift сам открывает MAX, находит звонившего по номеру, вводит текст из правила и нажимает обученную кнопку. Клон MAX выбирается по SIM звонка, «+7» и «8» равнозначны.
            • Чужие черновики не трогаются; получатель нигде не привязывается.
            • Итог каждой попытки — в «Журнале» на экране настройки MAX. «Предположительно отправлено» проверяйте у получателя.
            • Кнопка отправки — константа: обучается один раз и переносится на новый телефон кодом настройки (экран MAX → «Дополнительно»).
        """.trimIndent()
        maxCard.root.addView(button("Настройка MAX") {
            startActivity(Intent(this, fi.callshift.app.max.MaxSimpleActivity::class.java))
        })

        // ---- Telegram ----
        val tgCard = makeCard("Telegram", "Автоответ через Telegram", root)
        tgCard.limitButton.setOnClickListener {
            askLimit("Лимит Telegram в сутки", app.settings.telegramDailyLimit) { v ->
                runCatching { app.settings.setTelegramDailyLimit(v) }.onFailure { toast("Не удалось сохранить") }
            }
        }
        tgCard.details.text = """
            • Два режима: имитация касаний (как MAX: приложение само открывает Telegram, находит чат по номеру и нажимает обученную кнопку; аккаунт в CallShift не нужен) или ваш аккаунт (TDLib).
            • Имитация включается на экране «Аккаунт Telegram»: включите службу и разрешите реальную отправку. Кнопка определяется автоматически, обучение не нужно (запасной режим для нестандартных сборок).
            • Массовое развёртывание: на каждом телефоне — только эти два переключателя; обучение и перенос кодом не требуются.
            • Номер подтверждается перед отправкой; боты, свой аккаунт и непроверенные номера запрещены. Лимит списывается до отправки; ошибкам поиска бюджет не тратится.
        """.trimIndent()
        tgCard.root.addView(button("Аккаунт Telegram и имитация") {
            startActivity(Intent(this, fi.callshift.app.telegram.TelegramAccountActivity::class.java))
        })

        // ---- SMS ----
        val smsCard = makeCard("SMS", "Ответы SMS по правилам", root)
        smsCard.limitButton.setOnClickListener {
            askLimit("Лимит SMS в сутки (частей)", app.settings.smsDailyLimit) { v ->
                runCatching { app.settings.setSmsSafety(v, app.settings.smsCooldownPerSim) }
                    .onFailure { toast("Не удалось сохранить") }
            }
        }
        smsCard.details.text = """
            • Ответ обычной SMS с SIM звонка по шаблону правила; длинный текст считается частями, возможна плата оператора.
            • Лимит — в частях за 24 часа, включая тестовые отправки. Пауза между ответами настраивается на каждой SIM.
            • Расходы и копия ответов — на экране «Защита, расходы и копия».
        """.trimIndent()
        smsCard.root.addView(button("Правила SMS") {
            startActivity(Intent(this, SmsRuleActivity::class.java))
        })
        smsCard.root.addView(button("Защита, расходы и копия") {
            startActivity(Intent(this, SmsSafetyActivity::class.java))
        })

        text(12f, R.color.text_muted).let {
            it.text = "Лимиты: 1–1000 в сутки, по умолчанию 20. Изменение действует сразу."
            it.setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
            root.addView(it)
        }

        setContentView(scroll)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(1000)
                }
            }
        }
    }

    // ---- concrete "what to configure next" per channel ----
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
        fun set(name: String, issues: List<String>, okLine: String, usage: String) {
            val c = cards[name] ?: return
            val done = issues.isEmpty()
            c.chip.text = if (done) "● Готов" else "● Настроить"
            c.chip.setTextColor(ContextCompat.getColor(this, if (done) R.color.status_ok else R.color.status_warn))
            c.next.text = if (done) okLine else "Дальше: ${issues.first()}"
            c.next.setTextColor(ContextCompat.getColor(this,
                if (done) R.color.text_secondary else R.color.status_warn))
            c.usage.text = usage
        }
        val m = maxIssues()
        set("MAX", m,
            "Режим: ${if (maxStore.live) "реальная отправка" else if (maxStore.enabled) "проверка без отправки" else "выключен"} · кнопка обучена",
            "Лимит ${maxStore.sendLimit()}/24ч")

        val tgUi = fi.callshift.app.telegram.TelegramUiStore(this)
        set("Telegram", tgIssues(),
            if (tgUi.enabled) "Имитация касаний: ${if (tgUi.live) "реальная отправка" else "проверка без отправки"}"
            else "Режим аккаунта (TDLib)",
            "Кнопка отправки: определяется автоматически")

        set("SMS", smsIssues(), "Разрешение есть", "Лимит ${app.settings.smsDailyLimit} частей/24ч")

        lifecycleScope.launch {
            val tgUsed = runCatching { TelegramLimitStore.used(this@MessengerHubActivity) }.getOrDefault(-1)
            val smsUsed = runCatching { SmsBudgetStore.used(this@MessengerHubActivity) }.getOrDefault(-1)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                cards["Telegram"]?.usage?.text =
                    "Использовано: ${if (tgUsed >= 0) tgUsed else "—"} / ${app.settings.telegramDailyLimit} за 24ч"
                cards["SMS"]?.usage?.text =
                    "Использовано: ${if (smsUsed >= 0) smsUsed else "—"} / ${app.settings.smsDailyLimit} частей за 24ч"
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun button(label: String, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun makeCard(name: String, subtitle: String, root: LinearLayout): Card {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.surface_card))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), ContextCompat.getColor(this@MessengerHubActivity, R.color.card_stroke))
            }
        }
        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val header = TextView(this).apply {
            text = name; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.text_primary))
        }
        val chip = TextView(this).apply {
            textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(3), dp(10), dp(3))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }
        }
        headerRow.addView(header)
        headerRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        headerRow.addView(chip)
        val sub = TextView(this).apply {
            text = subtitle; textSize = 13f
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.text_muted))
        }
        val next = TextView(this).apply {
            textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, 0)
        }
        val usage = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        }
        val details = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.text_secondary))
            setLineSpacing(dp(3).toFloat(), 1f)
            visibility = View.GONE
            setPadding(0, dp(4), 0, 0)
        }
        val toggle = TextView(this).apply {
            text = "Подробнее ▸"; textSize = 13f
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.brand_accent))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(2))
            setOnClickListener {
                val c = cards[name]!!
                c.expanded = !c.expanded
                details.visibility = if (c.expanded) View.VISIBLE else View.GONE
                text = if (c.expanded) "Свернуть ▴" else "Подробнее ▸"
            }
        }
        val limitButton = MaterialButton(this).apply {
            isAllCaps = false
            setPadding(0, dp(8), 0, dp(8))
        }
        val limitRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            setPadding(0, dp(8), 0, 0)
        }
        limitRow.addView(limitButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        card.addView(headerRow)
        card.addView(sub)
        card.addView(next)
        card.addView(usage)
        card.addView(limitRow)
        card.addView(toggle)
        card.addView(details)
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(card)
        }
        root.addView(wrapper)
        return Card(wrapper, chip, next, usage, details, limitButton).also { cards[name] = it }
    }

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
