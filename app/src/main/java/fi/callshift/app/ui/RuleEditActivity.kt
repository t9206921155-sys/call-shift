package fi.callshift.app.ui

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R
import fi.callshift.app.databinding.ActivityRuleEditBinding
import fi.callshift.app.domain.Action
import fi.callshift.app.domain.CallContext
import fi.callshift.app.domain.ConditionGroup
import fi.callshift.app.domain.ConditionSpec
import fi.callshift.app.domain.NumberMatcher
import fi.callshift.app.domain.Rule
import fi.callshift.app.domain.RuleEngine
import fi.callshift.app.domain.StrategySpec
import fi.callshift.app.domain.VerdictSpec
import kotlinx.coroutines.launch

class RuleEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRuleEditBinding
    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }
    private var ruleId: Long = 0L
    private var existingRule: Rule? = null
    private lateinit var scheduleEditor: ScheduleEditor

    private val smsPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Toast.makeText(
            this,
            if (granted) "Правило сохранено, SMS разрешены" else "Правило сохранено, но без разрешения SMS автоответ не уйдёт",
            Toast.LENGTH_LONG,
        ).show()
        finish()
    }

    private val notificationPermLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Toast.makeText(this, if (granted) "Правило сохранено, уведомления разрешены"
            else "Правило сохранено, но уведомление для ответа недоступно", Toast.LENGTH_LONG).show()
        finish()
    }

    private val strategyKeys = RuleLabels.strategies.keys.toList()
    private val verdictKeys = RuleLabels.verdicts.keys.toList()
    private val strategies = strategyKeys.map { it.name }
    private val verdicts = verdictKeys.map { it.name }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRuleEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ruleId = intent.getLongExtra(EXTRA_RULE_ID, 0L)
        setupSpinners()
        setupListeners()
        scheduleEditor = ScheduleEditor(this, binding).also { it.setup() }

        if (ruleId != 0L) {
            loadRule(ruleId)
        }
    }

    private fun orderIndexFor(priority: Int): Int =
        ORDER_OPTIONS.indices.minBy { kotlin.math.abs(ORDER_OPTIONS[it].second - priority) }

    /** Пары (значение simSelector, подпись). */
    private var simOptions: List<Pair<String, String>> = emptyList()

    private fun setupSimSpinner(selected: String) {
        val accounts = app.telecom.phoneAccounts() // id → название (нужно разрешение «Телефон»)
        val opts = mutableListOf(fi.callshift.app.domain.SimSelector.ANY to "Любая SIM")
        if (accounts.isNotEmpty()) {
            accounts.entries.forEachIndexed { i, (id, label) ->
                opts += (fi.callshift.app.domain.SimSelector.HANDLE_PREFIX + id) to "SIM ${i + 1}: ${label.ifBlank { id }}"
            }
        } else {
            opts += fi.callshift.app.domain.SimSelector.SIM1 to "SIM 1"
            opts += fi.callshift.app.domain.SimSelector.SIM2 to "SIM 2"
        }
        val prefix = fi.callshift.app.domain.SimSelector.HANDLE_PREFIX
        val resolvedSelection = if (selected.startsWith(prefix)) {
            app.telecom.canonicalAccountId(selected.removePrefix(prefix))?.let { prefix + it } ?: selected
        } else selected
        if (opts.none { it.first == resolvedSelection }) {
            opts += resolvedSelection to "Сохранённая SIM не определена — выберите нужную карту"
        }
        simOptions = opts
        binding.spinnerSim.adapter = darkSpinnerAdapter(this, opts.map { it.second })
        binding.spinnerSim.setSelection(opts.indexOfFirst { it.first == resolvedSelection }.coerceAtLeast(0))
        binding.spinnerSim.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                binding.tvSimWarn.visibility = if (pos > 0) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
    }

    private fun setupSpinners() {
        binding.spinnerReplyChannel.adapter = darkSpinnerAdapter(this,
            fi.callshift.app.domain.ReplyChannel.labels.values.toList())
        setupSimSpinner(fi.callshift.app.domain.SimSelector.ANY)
        binding.spinnerOrder.adapter = darkSpinnerAdapter(this, ORDER_OPTIONS.map { it.first },
        )
        binding.spinnerOrder.setSelection(1)
        val stratAdapter = darkSpinnerAdapter(this, strategyKeys.map { RuleLabels.strategies.getValue(it).title },
        )
        binding.spinnerStrategy.adapter = stratAdapter

        val verdAdapter = darkSpinnerAdapter(this, verdictKeys.map { RuleLabels.verdicts.getValue(it).title },
        )
        binding.spinnerVerdict.adapter = verdAdapter

        binding.spinnerStrategy.onItemSelectedListener = onSelected { pos ->
            val label = RuleLabels.strategies.getValue(strategyKeys[pos])
            binding.tvStrategyHint.text = label.hint
            binding.tilTarget.visibility = if (label.needsTarget) View.VISIBLE else View.GONE
        }
        binding.spinnerVerdict.onItemSelectedListener = onSelected { pos ->
            binding.tvVerdictHint.text = RuleLabels.verdicts.getValue(verdictKeys[pos]).hint
        }

        // Значения по умолчанию
        binding.spinnerStrategy.setSelection(strategies.indexOf(StrategySpec.NONE.name))
        binding.spinnerVerdict.setSelection(verdicts.indexOf(VerdictSpec.DISALLOW_REJECT.name))
    }

    private fun onSelected(block: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) =
            block(position)
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    private fun loadRule(id: Long) {
        lifecycleScope.launch {
            val rule = app.ruleStore.rules().firstOrNull { it.id == id } ?: return@launch
            existingRule = rule

            binding.etName.setText(rule.name)

            // Условия
            val conditions = rule.conditions.anyOf.flatten()
            val patternCond = conditions.firstOrNull { it.type == RuleEngine.TYPE_NUMBER_MATCH }
            binding.etPattern.setText(patternCond?.pattern ?: "")

            val contactsCond = conditions.firstOrNull { it.type == RuleEngine.TYPE_IN_CONTACTS }
            val anonTmp = conditions.firstOrNull { it.type == RuleEngine.TYPE_ANONYMOUS }
            binding.rgWho.check(
                when {
                    anonTmp?.value == true -> R.id.rbHidden
                    contactsCond?.value == true -> R.id.rbContacts
                    contactsCond?.value == false -> R.id.rbUnknown
                    else -> R.id.rbAll
                },
            )
            binding.spinnerOrder.setSelection(orderIndexFor(rule.priority))
            setupSimSpinner(rule.simSelector)
            scheduleEditor.load(rule.schedule, rule.validFrom, rule.validTo)


            // Действие
            binding.spinnerStrategy.setSelection(strategies.indexOf(rule.action.strategy.name).coerceAtLeast(0))
            binding.spinnerVerdict.setSelection(verdicts.indexOf(rule.action.verdict.name).coerceAtLeast(0))
            binding.etTarget.setText(rule.action.target ?: "")
            binding.cbDtmfTransfer.isChecked = rule.action.dtmfTransferOriginal
            binding.etSmsReply.setText(rule.action.autoReplySms ?: "")
            binding.spinnerReplyChannel.setSelection(fi.callshift.app.domain.ReplyChannel.labels.keys
                .indexOf(rule.action.replyChannel).coerceAtLeast(0))

            binding.btnDelete.visibility = View.VISIBLE
        }
    }

    private fun setupListeners() {
        binding.btnSave.setOnClickListener { saveRule() }

        binding.btnDelete.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Удалить правило?")
                .setPositiveButton("Удалить") { _, _ ->
                    lifecycleScope.launch {
                        app.ruleStore.delete(ruleId)
                        finish()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        binding.btnTest.setOnClickListener { showTestDialog() }

        // «Только контакты» и «Только скрытые» взаимоисключающие.
    }

    /** Собрать правило из формы. null — если есть ошибка (показана пользователю). */
    private fun buildRule(): Rule? {
        val name = binding.etName.text.toString().trim().ifBlank { "Правило" }
        val priority = ORDER_OPTIONS[binding.spinnerOrder.selectedItemPosition.coerceAtLeast(0)].second
        val pattern = binding.etPattern.text.toString().trim()
        val who = binding.rgWho.checkedRadioButtonId
        val isAnon = who == R.id.rbHidden

        if (pattern.isNotEmpty() && !NumberMatcher.isValidPattern(pattern)) {
            toast("Некорректная маска номера: только +, цифры, * и ? (например +7999* или *)")
            return null
        }

        val strategy = strategyKeys[binding.spinnerStrategy.selectedItemPosition.coerceAtLeast(0)]
        val verdict = verdictKeys[binding.spinnerVerdict.selectedItemPosition.coerceAtLeast(0)]
        val needsTarget = RuleLabels.strategies.getValue(strategy).needsTarget
        val target = if (needsTarget) binding.etTarget.text.toString().trim().ifBlank { null } else null
        if (needsTarget && target == null) {
            toast("Укажите номер, на который переадресовывать")
            return null
        }
        val smsReply = binding.etSmsReply.text.toString().trim().ifBlank { null }
        if (smsReply != null && verdict != VerdictSpec.DISALLOW_REJECT && verdict != VerdictSpec.DISALLOW_AS_MISSED) {
            toast("Ответ после отбоя работает только если звонок сбрасывается («Сбросить» или «Сбросить и записать в пропущенные»)")
            return null
        }
        if (smsReply != null && isAnon) {
            toast("Нельзя подготовить ответ звонящему со скрытым номером — выберите другой вариант в «Для каких звонков» или уберите текст ответа")
            return null
        }

        val condList = mutableListOf<ConditionSpec>()
        if (pattern.isNotEmpty()) condList.add(ConditionSpec(type = RuleEngine.TYPE_NUMBER_MATCH, pattern = pattern))
        if (who == R.id.rbContacts) condList.add(ConditionSpec(type = RuleEngine.TYPE_IN_CONTACTS, value = true))
        if (who == R.id.rbUnknown) condList.add(ConditionSpec(type = RuleEngine.TYPE_IN_CONTACTS, value = false))
        if (isAnon) condList.add(ConditionSpec(type = RuleEngine.TYPE_ANONYMOUS, value = true))
        val conditions = if (condList.isEmpty()) ConditionGroup() else ConditionGroup(listOf(condList))

        scheduleEditor.error()?.let { toast(it); return null }
        return Rule(
            id = ruleId,
            schedule = scheduleEditor.schedule(),
            validFrom = scheduleEditor.validFrom(),
            validTo = scheduleEditor.validTo(),
            createdAt = existingRule?.createdAt ?: 0,
            name = name,
            priority = priority,
            enabled = existingRule?.enabled ?: true,
            conditions = conditions,
            action = Action(
                verdict = verdict,
                strategy = strategy,
                target = target,
                dtmfTransferOriginal = binding.cbDtmfTransfer.isChecked,
                autoReplySms = smsReply,
                replyChannel = fi.callshift.app.domain.ReplyChannel.labels.keys.toList()
                    .getOrElse(binding.spinnerReplyChannel.selectedItemPosition) { "SMS" },
            ),
            simSelector = simOptions.getOrNull(binding.spinnerSim.selectedItemPosition)?.first
                ?: fi.callshift.app.domain.SimSelector.ANY,
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun saveRule() {
        val toSave = buildRule() ?: return
        lifecycleScope.launch {
            app.ruleStore.save(toSave)
            if (toSave.action.autoReplySms != null && toSave.action.replyChannel == "SMS" && !app.smsReplier.hasPermission()) {
                smsPermLauncher.launch(android.Manifest.permission.SEND_SMS)
                return@launch
            }
            if (toSave.action.autoReplySms != null && toSave.action.replyChannel != "SMS" &&
                android.os.Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                return@launch
            }
            Toast.makeText(this@RuleEditActivity, "Правило сохранено", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * Проверяет ПРАВИЛО С ЭКРАНА (даже несохранённое) на введённом номере и объясняет,
     * что помешает ему сработать при реальном звонке.
     */
    private fun showTestDialog() {
        val draft = buildRule() ?: return
        val input = EditText(this).apply {
            hint = "+79001234567 (пусто = скрытый номер)"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this)
            .setTitle("Проверка правила «${draft.name}»")
            .setMessage("Введите номер, с которого будто бы звонят:")
            .setView(input)
            .setPositiveButton("Проверить") { _, _ ->
                val raw = input.text.toString().trim().ifBlank { null }
                val norm = app.normalizer.normalize(raw)
                val ctx = CallContext(rawHandle = raw, e164 = norm.e164, national = norm.national, isEmergency = norm.isEmergency)
                lifecycleScope.launch {
                    val matches = app.ruleEngine.matchesConditions(draft.conditions, ctx)
                    val report = app.detector.detect()
                    val hasRole = report.isCallScreeningRole || report.isDefaultDialer
                    val others = app.ruleStore.rules()
                        .filter { it.enabled && it.id != draft.id && it.priority < draft.priority }
                        .filter { app.ruleEngine.matchesConditions(it.conditions, ctx) }

                    val msg = buildString {
                        append("Номер: ").append(ctx.displayNumber.takeIf { raw != null } ?: "скрытый").append("\n\n")
                        if (matches) {
                            append("✅ Условия правила подходят.\n")
                            append("Со звонком: ").append(RuleLabels.verdictTitle(draft.action.verdict)).append("\n")
                            append("Куда: ").append(RuleLabels.strategyTitle(draft.action.strategy.name)).append("\n")
                            draft.action.autoReplySms?.let {
                                append(fi.callshift.app.domain.ReplyChannel.labels[draft.action.replyChannel]).append(": ").append(if (ctx.e164 != null) "«$it»" else "не уйдёт — номер скрыт").append("\n")
                            }
                        } else {
                            append("❌ Условия правила НЕ подходят для этого номера.\n")
                            append(explainMismatch(draft, ctx)).append("\n")
                        }
                        val problems = mutableListOf<String>()
                        if (!hasRole) problems += "У приложения нет роли перехвата — звонки до него не доходят. Главный экран → «Выдать роль перехвата»."
                        if (!app.settings.masterEnabled) problems += "Выключен главный переключатель на главном экране."
                        if (!draft.enabled) problems += "Правило выключено (переключатель в карточке правила)."
                        if (others.isNotEmpty()) problems += "Раньше проверяется правило «${others.first().name}», и оно тоже подходит — сработает оно. Поменяйте «Порядок проверки»."
                        if (draft.conditions.anyOf.flatten().any { it.type == RuleEngine.TYPE_ANONYMOUS } && !report.isDefaultDialer) {
                            problems += "Android обычно НЕ передаёт скрытые номера приложению-фильтру. Правила для скрытых номеров надёжно работают, только если CallShift — основное приложение «Телефон»."
                        }
                        if (draft.simSelector != fi.callshift.app.domain.SimSelector.ANY) {
                            val simName = simOptions.firstOrNull { it.first == draft.simSelector }?.second ?: draft.simSelector
                            append("\nSIM: правило только для «$simName». После звонка в «Журнале» видно, на какую SIM он пришёл; если там «—», телефон не сообщает SIM: правило для конкретной карты не применяется, другая карта не подставляется.\n")
                        }
                        if (draft.action.autoReplySms != null && draft.action.replyChannel == "SMS" && !app.smsReplier.hasPermission()) problems += "Нет разрешения на отправку SMS."
                        val now = System.currentTimeMillis()
                        if (!draft.isActiveAt(now)) problems += "Правило выключено или вне срока действия."
                        if (!fi.callshift.app.domain.ScheduleMatcher.matches(draft.schedule, now)) problems += "Сейчас не по расписанию правила."
                        if (app.settings.autoReply.isActiveAt(now)) problems += "Включён отдельный автоответчик: если его условия подходят, он сработает раньше этого правила."
                        if (app.settings.repeatCallEnabled) problems += "Исключение повторного звонка включено: повтор может пройти без сброса."
                        if (draft.action.autoReplySms != null && draft.action.replyChannel != "SMS") problems += "Мессенджер не отправляет автоматически: требуется ручная отправка из уведомления."
                        if (draft.action.autoReplySms != null && draft.action.replyChannel == "SMS" && draft.simSelector.startsWith("HANDLE:")) {
                            if (!app.smsReplier.canUseAccount(draft.simSelector.removePrefix("HANDLE:"))) problems += "Выбранная SIM не определена для отправки SMS."
                        }
                        if (ruleId == 0L || existingRule == null) problems += "Правило ещё не сохранено — нажмите «Сохранить правило»."
                        if (problems.isNotEmpty()) {
                            append("\n⚠️ При реальном звонке помешает:\n")
                            problems.forEach { append("• ").append(it).append("\n") }
                        } else if (matches) {
                            append("\nЛокальные проверки пройдены. Это не проверка реальной SIM входящего звонка, сети или доставки SMS.")
                        }
                    }
                    AlertDialog.Builder(this@RuleEditActivity)
                        .setTitle("Результат проверки")
                        .setMessage(msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private suspend fun explainMismatch(rule: Rule, ctx: CallContext): String {
        val reasons = mutableListOf<String>()
        for (c in rule.conditions.anyOf.flatten()) {
            val ok = app.ruleEngine.matchesConditions(ConditionGroup(listOf(listOf(c))), ctx)
            if (ok) continue
            reasons += when (c.type) {
                RuleEngine.TYPE_NUMBER_MATCH -> "Номер не подходит под маску «${c.pattern}»."
                RuleEngine.TYPE_IN_CONTACTS -> if (c.value == false) {
                    "Номер есть в контактах, а выбрано «Только незнакомые»."
                } else {
                    "Номера нет в контактах (или нет доступа к контактам)."
                }
                RuleEngine.TYPE_ANONYMOUS -> "Номер не скрытый, а выбрано «Только скрытые номера»."
                else -> "Не выполнено условие ${c.type}."
            }
        }
        return reasons.joinToString("\n").ifBlank { "Причина не определена." }
    }

    companion object {
        const val EXTRA_RULE_ID = "extra_rule_id"

        /** «Порядок проверки» → числовой приоритет (меньше = проверяется раньше). */
        val ORDER_OPTIONS = listOf(
            "Проверять первым (исключения, например контакты)" to 10,
            "Обычный" to 100,
            "Проверять последним (общее правило «все звонки»)" to 900,
        )
    }
}
