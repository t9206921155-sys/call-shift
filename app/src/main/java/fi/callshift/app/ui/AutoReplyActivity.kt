package fi.callshift.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R
import fi.callshift.app.domain.AutoReplySettings
import fi.callshift.app.domain.SimSelector
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Настройка режима «Автоответчик» + исключения (белый список, повторный звонок). */
class AutoReplyActivity : AppCompatActivity() {

    private val app by lazy { CallShiftApp.from(this) }
    private lateinit var ui: FormUi
    private lateinit var swEnabled: SwitchMaterial
    private lateinit var etText: EditText
    private lateinit var rgScope: RadioGroup
    private lateinit var rgUntil: RadioGroup
    private lateinit var rgSim: RadioGroup
    private lateinit var swRepeat: SwitchMaterial
    private lateinit var btnWhitelist: MaterialButton
    private var simOptions: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Автоответчик"
        ui = FormUi(this)
        val s = app.settings.autoReply
        val primary = ContextCompat.getColor(this, R.color.text_primary)

        ui.title("Автоответчик")
        ui.hint("Быстрый режим «я занят»: все подходящие звонки сбрасываются, звонящему уходит SMS с текстом ниже. " +
            "Работает поверх правил — пока включён, правила не проверяются.")

        swEnabled = ui.add(SwitchMaterial(this).apply { text = "Включён"; setTextColor(primary); textSize = 17f; isChecked = s.enabled })

        ui.header("Текст SMS")
        etText = ui.add(EditText(this).apply {
            setText(s.text); setTextColor(primary); minLines = 2
            filters = arrayOf(InputFilter.LengthFilter(201))
        })
        ui.add(chipRow(listOf("Не могу говорить, перезвоню позже.", "Я за рулём, перезвоню.", "На встрече, напишите SMS.")) { etText.setText(it) })

        ui.header("Для каких звонков")
        rgScope = ui.add(radioGroup(listOf(
            AutoReplySettings.SCOPE_ALL to "Все звонки (кроме белого списка)",
            AutoReplySettings.SCOPE_UNKNOWN to "Только номера не из контактов",
        ), s.scope))

        ui.header("Сколько действует")
        val untilOpts = listOf("keep" to (s.untilMs?.let { "Как сейчас: до ${fmt(it)}" } ?: "Пока не выключу"),
            "1h" to "1 час", "2h" to "2 часа", "tomorrow" to "До утра (08:00)", "forever" to "Пока не выключу")
            .distinctBy { it.second }
        rgUntil = ui.add(radioGroup(untilOpts, "keep"))

        ui.header("На какой SIM")
        simOptions = buildSimOptions(s.simSelector)
        rgSim = ui.add(radioGroup(simOptions, s.simSelector))

        ui.add(MaterialButton(this).apply { text = "Сохранить"; setOnClickListener { save() } }, top = 12)

        ui.header("Исключения — эти звонки никогда не сбрасываются")
        swRepeat = ui.add(SwitchMaterial(this).apply {
            text = "Повторный звонок в течение ${app.settings.repeatCallMinutes} мин — пропустить (значит, срочно)"
            setTextColor(primary); isChecked = app.settings.repeatCallEnabled
            setOnCheckedChangeListener { _, on -> app.settings.setRepeatCall(on, app.settings.repeatCallMinutes) }
        })
        ui.hint("Действует и для автоответчика, и для всех правил.")
        btnWhitelist = ui.add(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            setOnClickListener { startActivity(Intent(this@AutoReplyActivity, WhitelistActivity::class.java)) }
        })

        setContentView(ui.scroll)
    }

    override fun onResume() {
        super.onResume()
        btnWhitelist.text = "Белый список (${app.settings.whitelist.size})"
    }

    private fun save() {
        val text = etText.text.toString().trim()
        if (text.isEmpty()) { Toast.makeText(this, "Введите текст SMS", Toast.LENGTH_SHORT).show(); return }
        val old = app.settings.autoReply
        val now = System.currentTimeMillis()
        val until = when (tagOf(rgUntil)) {
            "1h" -> now + 3_600_000L
            "2h" -> now + 7_200_000L
            "tomorrow" -> Calendar.getInstance().apply {
                if (get(Calendar.HOUR_OF_DAY) >= 8) add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
            }.timeInMillis
            "forever" -> null
            else -> old.untilMs
        }
        app.settings.setAutoReply(AutoReplySettings(
            enabled = swEnabled.isChecked, text = text,
            scope = tagOf(rgScope) ?: AutoReplySettings.SCOPE_ALL,
            untilMs = until, simSelector = tagOf(rgSim) ?: SimSelector.ANY,
        ))
        AutoReplyTileService.requestUpdate(this)
        Toast.makeText(this, if (swEnabled.isChecked) "Автоответчик включён" else "Сохранено", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun tagOf(g: RadioGroup): String? = g.findViewById<RadioButton>(g.checkedRadioButtonId)?.tag as? String

    private fun radioGroup(opts: List<Pair<String, String>>, selected: String) = RadioGroup(this).apply {
        opts.forEach { (key, label) ->
            addView(RadioButton(this@AutoReplyActivity).apply {
                id = android.view.View.generateViewId(); tag = key; text = label; textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            })
        }
        (0 until childCount).map { getChildAt(it) as RadioButton }
            .let { rbs -> (rbs.firstOrNull { it.tag == selected } ?: rbs.first()).let { check(it.id) } }
    }

    private fun chipRow(texts: List<String>, onPick: (String) -> Unit) =
        com.google.android.material.chip.ChipGroup(this).apply {
            texts.forEach { t ->
                addView(com.google.android.material.chip.Chip(this@AutoReplyActivity).apply {
                    text = t
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    chipBackgroundColor = ContextCompat.getColorStateList(context, R.color.chip_bg)
                    setOnClickListener { onPick(t) }
                })
            }
        }

    private fun buildSimOptions(selected: String): List<Pair<String, String>> {
        val accounts = runCatching { app.telecom.phoneAccounts() }.getOrDefault(emptyMap())
        val opts = mutableListOf(SimSelector.ANY to "Любая SIM")
        if (accounts.size > 1) accounts.entries.forEachIndexed { i, (id, label) ->
            opts += (SimSelector.HANDLE_PREFIX + id) to "SIM ${i + 1}: ${label.ifBlank { id }}"
        }
        if (opts.none { it.first == selected }) opts += selected to "Сохранённая SIM (сейчас не найдена)"
        return opts
    }

    companion object {
        fun fmt(ms: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale("ru")).format(Date(ms))

        /** Короткое описание для карточки на главном экране. */
        fun summary(s: AutoReplySettings, now: Long = System.currentTimeMillis()): String = when {
            !s.enabled -> "Выключен. Включите, когда заняты — звонки сбросятся, звонящим уйдёт SMS."
            !s.isActiveAt(now) -> "Время действия истекло"
            else -> buildString {
                append(if (s.scope == AutoReplySettings.SCOPE_UNKNOWN) "Незнакомые номера" else "Все звонки")
                append(" → SMS «").append(s.text.take(40)).append(if (s.text.length > 40) "…»" else "»")
                s.untilMs?.let { append("\nДо ").append(fmt(it)) }
            }
        }
    }
}
