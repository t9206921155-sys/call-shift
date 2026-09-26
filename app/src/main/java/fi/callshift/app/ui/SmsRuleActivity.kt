package fi.callshift.app.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R
import fi.callshift.app.domain.*
import kotlinx.coroutines.launch

/** Focused editor. Complex rules stay in the advanced editor; never flatten them silently. */
class SmsRuleActivity : AppCompatActivity() {
    private val app by lazy { CallShiftApp.from(this) }
    private var original: Rule? = null
    private lateinit var ui: FormUi
    private val permissions = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) {
        Toast.makeText(this, "Правило сохранено. Готовность и разрешения — в разделе «Статус».", Toast.LENGTH_LONG).show(); finish()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra(RuleEditActivity.EXTRA_RULE_ID, 0)
        lifecycleScope.launch {
            original = app.ruleStore.rules().firstOrNull { it.id == id }
            if (id != 0L && (original == null || !SimpleSmsRule.supports(original!!))) {
                startActivity(Intent(this@SmsRuleActivity, RuleEditActivity::class.java).putExtra(RuleEditActivity.EXTRA_RULE_ID, id))
                finish(); return@launch
            }
            render(savedInstanceState)
        }
    }
    private fun render(state: Bundle?) {
        val rule = original ?: Rule(name = "Сброс + SMS", action = Action(verdict = VerdictSpec.DISALLOW_REJECT))
        ui = FormUi(this); ui.title("Сброс + SMS")
        if (app.settings.autoReply.isActiveAt(System.currentTimeMillis())) {
            ui.hint("ВНИМАНИЕ: включён отдельный автоответчик. Если его условия подходят, он сработает РАНЬШЕ этого правила.")
            ui.add(MaterialButton(this).apply {
                text = "Выключить отдельный автоответчик"
                setOnClickListener { app.settings.setAutoReplyEnabled(false); text = "Автоответчик выключен"; isEnabled = false }
            })
        }
        fun field(key: String, hintText: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText = ui.add(EditText(this).apply {
            hint = hintText; inputType = type; setTextColor(getColor(R.color.text_primary)); setText(state?.getString(key) ?: value)
            tag = key
        }).also { fields[key] = it }
        val name = field("name", "Название правила", rule.name)
        val accounts = app.telecom.phoneAccounts()
        val sims = mutableListOf("ANY" to "Любая SIM — обе карты")
        accounts.forEach { (id, label) -> sims += "HANDLE:$id" to label.ifBlank { "SIM" } }
        val chosen = state?.getString("sim") ?: rule.simSelector
        if (sims.none { it.first == chosen }) sims += chosen to "Сохранённая SIM не найдена — выберите карту"
        ui.header("На какую SIM")
        simSpinner = ui.add(Spinner(this).apply { adapter = darkSpinnerAdapter(this@SmsRuleActivity, sims.map { it.second }); setSelection(sims.indexOfFirst { it.first == chosen }.coerceAtLeast(0)) })
        simKeys = sims.map { it.first }
        ui.header("Кому отвечать")
        val inContacts = rule.conditions.anyOf.flatten().firstOrNull { it.type == RuleEngine.TYPE_IN_CONTACTS }?.let { it.value ?: true }
        whoSpinner = ui.add(Spinner(this).apply {
            adapter = darkSpinnerAdapter(this@SmsRuleActivity, listOf("Все звонящие", "Только контакты", "Только незнакомые"))
            setSelection(state?.getInt("who") ?: when (inContacts) { true -> 1; false -> 2; null -> 0 })
        })
        val pattern = field("pattern", "Номер / маска (пусто — любой)", rule.conditions.anyOf.flatten().firstOrNull { it.type == RuleEngine.TYPE_NUMBER_MATCH }?.pattern.orEmpty(), InputType.TYPE_CLASS_PHONE)
        ui.header("Текст SMS")
        val message = field("message", "Например: Я в отпуске, перезвоню позже", rule.action.autoReplySms.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        message.filters = arrayOf(InputFilter.LengthFilter(201)); message.minLines = 2
        SmsParts.attach(message, ui.add(TextView(this).apply { setTextColor(getColor(R.color.text_secondary)) }))
        ui.header("Готовый текст (не включает режим)")
        listOf("На работе" to "Я на работе, перезвоню позже.", "За рулём" to "Я за рулём, перезвоню, когда остановлюсь.",
            "В отпуске" to "Я в отпуске. По срочному вопросу напишите SMS.").forEach { (title, body) ->
            ui.add(MaterialButton(this).apply { text = title; setOnClickListener { name.setText(title); message.setText(body) } })
        }
        ui.header("Когда закончить режим")
        durationSpinner = ui.add(Spinner(this).apply {
            adapter = darkSpinnerAdapter(this@SmsRuleActivity, listOf("Сохранить текущий срок", "Без даты окончания", "Через 1 час", "Через 3 часа", "Через сутки", "Через 7 дней"))
            setSelection(state?.getInt("duration") ?: 0)
        })
        ui.hint("Срок отсчитывается от сохранения. После окончания правило перестанет срабатывать; SMS по таймеру не отправляется. Кнопки сценариев меняют только название и текст — SIM и получателей выберите сами.")
        ui.header("Повторный ответ этому номеру")
        intervalKeys = ReplyOptions.intervals.keys.toMutableList().also { if (rule.action.replyCooldownMinutes !in it) it += rule.action.replyCooldownMinutes }
        intervalSpinner = ui.add(Spinner(this).apply {
            adapter = darkSpinnerAdapter(this@SmsRuleActivity, intervalKeys.map(ReplyOptions::intervalLabel))
            setSelection(intervalKeys.indexOf(state?.getInt("interval") ?: rule.action.replyCooldownMinutes).coerceAtLeast(0))
        })
        ui.hint("Пауза не отправляет SMS по таймеру: нужен НОВЫЙ подходящий звонок. По умолчанию у каждой SIM своя пауза. Общий лимит расходов — ${app.settings.smsDailyLimit} SMS-частей за последние 24 часа.")
        ui.header("Когда действует")
        val selectedDays = state?.getIntegerArrayList("days") ?: rule.schedule?.days.orEmpty()
        listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEachIndexed { index, label ->
            dayChecks += ui.add(MaterialCheckBox(this).apply { text = label; setTextColor(getColor(R.color.text_primary)); isChecked = index + 1 in selectedDays })
        }
        ui.hint("Ничего не отмечено — каждый день. Оба времени пусты — весь день. Ночной интервал может переходить через полночь.")
        val from = field("from", "С (09:00)", rule.schedule?.from.orEmpty())
        val to = field("to", "До (18:00)", rule.schedule?.to.orEmpty())
        if (rule.validFrom != null || rule.validTo != null) ui.hint("Сохранённый срок действия: ${ScheduleEditor.shortText(rule)}. Изменить даты можно в дополнительных настройках.")
        ui.add(MaterialButton(this).apply {
            text = "Сохранить правило"
            setOnClickListener {
                val body = message.text.toString().trim()
                val mask = pattern.text.toString().trim().replace(" ", "")
                val f = from.text.toString().trim().ifEmpty { null }; val t = to.text.toString().trim().ifEmpty { null }
                val error = when {
                    name.text.isBlank() -> "Укажите название"
                    body.isBlank() -> "Введите текст ответа"
                    mask.isNotEmpty() && !NumberMatcher.isValidPattern(mask) -> "Некорректная маска номера"
                    (f == null) != (t == null) -> "Укажите оба времени либо оставьте оба пустыми"
                    f != null && (ScheduleMatcher.parse(f) == null || ScheduleMatcher.parse(t) == null) -> "Время должно быть ЧЧ:ММ"
                    else -> null
                }
                if (error != null) { Toast.makeText(this@SmsRuleActivity, error, Toast.LENGTH_LONG).show(); return@setOnClickListener }
                val expiry = when (durationSpinner.selectedItemPosition) {
                    0 -> rule.validTo
                    1 -> null
                    else -> System.currentTimeMillis() + listOf(0L, 0L, 1L, 3L, 24L, 168L)[durationSpinner.selectedItemPosition] * 3_600_000L
                }
                if (expiry != null && rule.validFrom != null && expiry < rule.validFrom) {
                    Toast.makeText(this@SmsRuleActivity, "Окончание раньше сохранённого начала. Измените даты в полном редакторе.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val conditions = buildList {
                    if (mask.isNotEmpty()) add(ConditionSpec(RuleEngine.TYPE_NUMBER_MATCH, pattern = mask))
                    if (whoSpinner.selectedItemPosition != 0) add(ConditionSpec(RuleEngine.TYPE_IN_CONTACTS, value = whoSpinner.selectedItemPosition == 1))
                }
                val draft = rule.copy(validTo = expiry, name = name.text.toString().trim().take(256),
                    simSelector = simKeys[simSpinner.selectedItemPosition], conditions = ConditionGroup(listOf(conditions)),
                    schedule = ScheduleSpec(dayChecks.mapIndexedNotNull { i, c -> (i + 1).takeIf { c.isChecked } }, f, t),
                    action = rule.action.copy(verdict = VerdictSpec.DISALLOW_REJECT, strategy = StrategySpec.NONE, autoReplySms = body,
                        replyChannel = "SMS", replyChannels = listOf("SMS"), replyCooldownMinutes = intervalKeys[intervalSpinner.selectedItemPosition]))
                fun save() { lifecycleScope.launch {
                    runCatching { app.ruleStore.save(draft) }.onSuccess {
                        val missing = (ReplyPermissions.missing(this@SmsRuleActivity, listOf("SMS")).toList() +
                            if (whoSpinner.selectedItemPosition != 0 && checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                                listOf(android.Manifest.permission.READ_CONTACTS) else emptyList()).toTypedArray()
                        if (missing.isNotEmpty()) permissions.launch(missing) else {
                            Toast.makeText(this@SmsRuleActivity, "Правило сохранено", Toast.LENGTH_SHORT).show(); finish()
                        }
                    }.onFailure { Toast.makeText(this@SmsRuleActivity, "Не удалось сохранить правило", Toast.LENGTH_LONG).show() }
                } }
                val parts = runCatching { SmsParts.count(body) }.getOrDefault(1)
                if (parts > 1 || draft.action.replyCooldownMinutes == 0) AlertDialog.Builder(this@SmsRuleActivity)
                    .setTitle("Проверьте расходы SMS")
                    .setMessage("Один ответ: примерно $parts SMS-частей. ${ReplyOptions.intervalLabel(draft.action.replyCooldownMinutes)}. Каждая часть может оплачиваться. Лимит за 24 ч: ${app.settings.smsDailyLimit} частей.")
                    .setPositiveButton("Сохранить") { _, _ -> save() }.setNegativeButton("Отмена", null).show()
                else save()
            }
        })
        ui.add(MaterialButton(this).apply {
            text = "Статус / тест SMS"
            setOnClickListener { startActivity(Intent(this@SmsRuleActivity, DiagnosticsActivity::class.java)) }
        })
        ui.add(MaterialButton(this).apply {
            text = "Дополнительно: полный редактор"
            setOnClickListener {
                AlertDialog.Builder(this@SmsRuleActivity).setMessage("Несохранённые изменения этого экрана не перенесутся. Открыть полный редактор?")
                    .setPositiveButton("Открыть") { _, _ ->
                        startActivity(Intent(this@SmsRuleActivity, RuleEditActivity::class.java).putExtra(RuleEditActivity.EXTRA_RULE_ID, rule.id)); finish()
                    }.setNegativeButton("Отмена", null).show()
            }
        })
        setContentView(ui.scroll)
        SystemBarsInsets.apply(this)
    }
    private val fields = mutableMapOf<String, EditText>()
    private val dayChecks = mutableListOf<MaterialCheckBox>()
    private lateinit var durationSpinner: Spinner
    private lateinit var simSpinner: Spinner
    private lateinit var whoSpinner: Spinner
    private lateinit var intervalSpinner: Spinner
    private var simKeys = emptyList<String>()
    private var intervalKeys = emptyList<Int>()
    override fun onSaveInstanceState(outState: Bundle) {
        fields.forEach { (key, value) -> outState.putString(key, value.text.toString()) }
        if (::simSpinner.isInitialized) {
            outState.putInt("duration", durationSpinner.selectedItemPosition)
            outState.putString("sim", simKeys[simSpinner.selectedItemPosition]); outState.putInt("who", whoSpinner.selectedItemPosition)
            outState.putInt("interval", intervalKeys[intervalSpinner.selectedItemPosition])
            outState.putIntegerArrayList("days", ArrayList(dayChecks.mapIndexedNotNull { i, c -> (i + 1).takeIf { c.isChecked } }))
        }
        super.onSaveInstanceState(outState)
    }
}
