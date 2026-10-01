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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every messenger in one place. Compact cards: status up front, the rest of the
 * story behind a «Подробнее» toggle so the screen stays readable at a glance. */
class MessengerHubActivity : AppCompatActivity() {
    private val app by lazy { CallShiftApp.from(this) }
    private val maxStore by lazy { MaxUiStore(this) }

    private class Card(
        val root: LinearLayout,
        val statusTitle: TextView,
        val statusLine: TextView,
        val usageLine: TextView,
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
        scroll.addView(root)

        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        title(text(22f, R.color.text_primary, true), "Мессенджеры", root)
        title(text(14f, R.color.text_secondary), "Все каналы автоответов: статус, настройка и суточный лимит — в одной карточке. Канал выбирается в правиле звонка; лимит — предохранитель от зацикливания, не ограничение работы.", root)

        // ---- MAX card ----
        val maxCard = makeCard("MAX", "Автоответ по звонку через интерфейс MAX", root)
        maxCard.limitButton.text = "Лимит MAX в сутки: ${maxStore.sendLimit()}"
        maxCard.limitButton.setOnClickListener {
            askLimit("Лимит автоответов MAX в сутки", maxStore.sendLimit()) { v ->
                runCatching { maxStore.setSendLimit(v) }.onFailure { toast("Не удалось сохранить") }
                refreshLimits()
            }
        }
        maxCard.details.text = """
            • Что это: CallShift сам открывает MAX, находит звонившего по номеру (проверка по карточке контакта), вводит текст ответа из правила и нажимает обученную кнопку отправки.
            • Отправитель: клон MAX привязан к SIM звонка — выбирается автоматически, «+7» и «8» равнозначны.
            • Кнопка отправки: обучается один раз касанием стрелки, затем нажимается автоматически. Переживает перезапуск приложения; заново обучать нужно только после обновления MAX.
            • Безопасность: получатель не привязывается; чужие черновики не трогаются; текст меняется только после подтверждения номера.
            • Итог каждой попытки: «Журнал» внизу экрана настройки.
        """.trimIndent()
        maxCard.root.addView(button("Настройка MAX: SIM, обучение кнопки, тест") {
            startActivity(Intent(this, fi.callshift.app.max.MaxSimpleActivity::class.java))
        })

        // ---- Telegram card ----
        val tgCard = makeCard("Telegram", "Автоответ по звонку через ваш аккаунт Telegram", root)
        tgCard.limitButton.text = "Лимит Telegram в сутки: ${app.settings.telegramDailyLimit}"
        tgCard.limitButton.setOnClickListener {
            askLimit("Лимит автоответов Telegram в сутки", app.settings.telegramDailyLimit) { v ->
                runCatching { app.settings.setTelegramDailyLimit(v) }.onFailure { toast("Не удалось сохранить") }
                refreshLimits()
            }
        }
        tgCard.details.text = """
            • Что это: ответ уходит личным сообщением Telegram с вашего аккаунта получателю, найденному по номеру.
            • Защита: отправка запрещена ботам, собственному аккаунту и номерам, которые Telegram не подтверждил.
            • Лимит: резерв списывается перед самой отправкой — ошибка поиска бюджет не тратит. При исчерпании — понятная остановка без повторов.
            • Вход: нужен однократный вход и включённая автоотправка на экране аккаунта. Выход из аккаунта отключает отправку автоматически.
            • Доставка: статус «доставлено» подтверждает получатель; CallShift не читает переписку.
        """.trimIndent()
        tgCard.root.addView(button("Аккаунт Telegram: вход и автоотправка") {
            startActivity(Intent(this, fi.callshift.app.telegram.TelegramAccountActivity::class.java))
        })

        // ---- SMS card ----
        val smsCard = makeCard("SMS", "Классические ответы SMS по правилам", root)
        smsCard.limitButton.text = "Лимит SMS в сутки: ${app.settings.smsDailyLimit} частей"
        smsLimitButtonBind(smsCard)
        smsCard.details.text = """
            • Что это: ответ обычной SMS с выбранной SIM по шаблону из правила. Каждая часть длинного текста может оплачиваться оператором.
            • Лимит: считается в частях за последние 24 часа, включая ручные и тестовые отправки. Изменение действует сразу.
            • Пауза: у каждой SIM своя пауза между ответами, чтобы не писать на каждый звонок.
            • Расходы и копия ответов: экран «SMS: защита, расходы и копия».
        """.trimIndent()
        smsCard.root.addView(button("SMS: правила и шаблоны ответов") {
            startActivity(Intent(this, SmsRuleActivity::class.java))
        })
        smsCard.root.addView(button("SMS: защита, расходы и копия") {
            startActivity(Intent(this, SmsSafetyActivity::class.java))
        })

        title(text(12f, R.color.text_muted), "Лимиты: от 1 до 1000 в сутки, действуют сразу и сохраняются. По умолчанию 20 — достаточно для обычного потока звонков и защищают от ошибочного цикла.", root)

        setContentView(scroll)
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

    private fun smsLimitButtonBind(card: Card) {
        card.limitButton.setOnClickListener {
            askLimit("Лимит SMS в сутки (частей)", app.settings.smsDailyLimit) { v ->
                runCatching { app.settings.setSmsSafety(v, app.settings.smsCooldownPerSim) }.onFailure { toast("Не удалось сохранить") }
                refreshLimits()
            }
        }
    }

    private fun title(v: TextView, s: String, root: LinearLayout) {
        v.text = s
        val p = (6 * resources.displayMetrics.density).toInt()
        v.setPadding(0, p, 0, p)
        root.addView(v)
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
        val header = TextView(this).apply {
            text = name
            textSize = 18f
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.text_primary))
            typeface = Typeface.DEFAULT_BOLD
        }
        val sub = TextView(this).apply {
            text = subtitle
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.text_secondary))
        }
        val statusTitle = TextView(this).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.text_primary))
            typeface = Typeface.DEFAULT_BOLD
        }
        val statusLine = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.text_secondary))
        }
        val usageLine = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.text_secondary))
        }
        val details = TextView(this).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.text_secondary))
            setLineSpacing(dp(3).toFloat(), 1f)
            visibility = View.GONE
        }
        val toggle = TextView(this).apply {
            text = "Подробнее ▸"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MessengerHubActivity, R.color.brand_accent))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(2))
            gravity = Gravity.END
            setOnClickListener {
                val c = cards[name]!!
                c.expanded = !c.expanded
                details.visibility = if (c.expanded) View.VISIBLE else View.GONE
                text = if (c.expanded) "Свернуть ▴" else "Подробнее ▸"
            }
        }
        val limitButton = MaterialButton(this).apply {
            isAllCaps = false
        }
        val limitRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        limitRow.addView(limitButton)
        card.addView(header)
        card.addView(sub)
        card.addView(statusTitle)
        card.addView(statusLine)
        card.addView(usageLine)
        card.addView(limitRow)
        card.addView(toggle)
        card.addView(details)
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = dp(6)
            setPadding(0, p, 0, p)
            addView(card)
        }
        root.addView(wrapper)
        return Card(wrapper, statusTitle, statusLine, usageLine, details, limitButton).also { cards[name] = it }
    }

    private fun askLimit(title: String, current: Int, save: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
        }
        AlertDialog.Builder(this).setTitle(title)
            .setMessage("Введите число от 1 до 1000. Действует сразу и сохраняется.")
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

    private fun refreshLimits() {
        cards["MAX"]?.limitButton?.text = "Лимит MAX в сутки: ${maxStore.sendLimit()}"
        cards["Telegram"]?.limitButton?.text = "Лимит Telegram в сутки: ${app.settings.telegramDailyLimit}"
        cards["SMS"]?.limitButton?.text = "Лимит SMS в сутки: ${app.settings.smsDailyLimit} частей"
    }

    private fun refreshStatuses() {
        fun set(name: String, ok: Boolean, line: String, usage: String) {
            val c = cards[name] ?: return
            c.statusTitle.text = if (ok) "● Готов" else "● Требует настройки"
            c.statusTitle.setTextColor(ContextCompat.getColor(this,
                if (ok) R.color.status_ok else R.color.status_warn))
            c.statusLine.text = line
            c.usageLine.text = usage
        }
        val maxReady = maxStore.enabled && maxStore.live && maxStore.learnedSend() != null && MaxUiService.connected
        val maxMode = when {
            !maxStore.enabled -> "выключен"
            maxStore.live -> "реальная отправка разрешена"
            else -> "только проверка без отправки"
        }
        set("MAX", maxReady, "Режим: $maxMode; служба ${if (MaxUiService.connected) "подключена" else "не подключена"}",
            "Кнопка отправки: ${if (maxStore.learnedSend() != null) "обучена" else "не обучена"}; лимит ${maxStore.sendLimit()}/24ч")

        val tgUi = fi.callshift.app.telegram.TelegramUiStore(this)
        val tgReady = if (tgUi.enabled)
            fi.callshift.app.telegram.TelegramUiService.connected && tgUi.live && tgUi.learnedSend() != null
        else runCatching { app.telegram.autoEnabled() }.getOrDefault(false)
        val tgMode = when {
            !tgUi.enabled -> "через аккаунт (TDLib): автоотправка выключена — нужен вход"
            !tgUi.live -> "имитация касаний: проверка без отправки"
            tgUi.learnedSend() == null -> "имитация касаний: не обучена кнопка отправки"
            else -> "имитация касаний: реальная отправка разрешена"
        }
        set("Telegram", tgReady, "Режим: $tgMode",
            "Кнопка отправки: ${if (tgUi.learnedSend() != null) "обучена" else "не обучена"}; лимит ${app.settings.telegramDailyLimit}/24ч")
        lifecycleScope.launch {
            val tgUsed = runCatching { TelegramLimitStore.used(this@MessengerHubActivity) }.getOrDefault(-1)
            val smsUsed = runCatching { SmsBudgetStore.used(this@MessengerHubActivity) }.getOrDefault(-1)
            withContext(Dispatchers.Main) {
                cards["Telegram"]?.usageLine?.text =
                    "Использовано: ${if (tgUsed >= 0) tgUsed else "счётчик недоступен"} / ${app.settings.telegramDailyLimit} за 24ч"
                cards["SMS"]?.usageLine?.text =
                    "Использовано: ${if (smsUsed >= 0) smsUsed else "счётчик недоступен"} / ${app.settings.smsDailyLimit} частей за 24ч"
            }
        }
    }
}
