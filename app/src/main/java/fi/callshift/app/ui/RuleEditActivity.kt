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

    private val strategies = StrategySpec.values().map { it.name }
    private val verdicts = VerdictSpec.values().map { it.name }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRuleEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ruleId = intent.getLongExtra(EXTRA_RULE_ID, 0L)
        setupSpinners()
        setupListeners()

        if (ruleId != 0L) {
            loadRule(ruleId)
        }
    }

    private fun setupSpinners() {
        val stratAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, strategies)
        binding.spinnerStrategy.adapter = stratAdapter

        val verdAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, verdicts)
        binding.spinnerVerdict.adapter = verdAdapter

        // Значения по умолчанию
        binding.spinnerStrategy.setSelection(strategies.indexOf(StrategySpec.CALLBACK_DIAL.name))
        binding.spinnerVerdict.setSelection(verdicts.indexOf(VerdictSpec.DISALLOW_REJECT.name))
    }

    private fun loadRule(id: Long) {
        lifecycleScope.launch {
            val rule = app.ruleStore.rules().firstOrNull { it.id == id } ?: return@launch
            existingRule = rule

            binding.etName.setText(rule.name)
            binding.etPriority.setText(rule.priority.toString())

            // Условия
            val conditions = rule.conditions.anyOf.flatten()
            val patternCond = conditions.firstOrNull { it.type == RuleEngine.TYPE_NUMBER_MATCH }
            binding.etPattern.setText(patternCond?.pattern ?: "")

            val contactsCond = conditions.firstOrNull { it.type == RuleEngine.TYPE_IN_CONTACTS }
            binding.cbInContacts.isChecked = contactsCond?.value ?: false

            val anonCond = conditions.firstOrNull { it.type == RuleEngine.TYPE_ANONYMOUS }
            binding.cbAnonymous.isChecked = anonCond?.value ?: false

            // Действие
            binding.spinnerStrategy.setSelection(strategies.indexOf(rule.action.strategy.name).coerceAtLeast(0))
            binding.spinnerVerdict.setSelection(verdicts.indexOf(rule.action.verdict.name).coerceAtLeast(0))
            binding.etTarget.setText(rule.action.target ?: "")
            binding.cbDtmfTransfer.isChecked = rule.action.dtmfTransferOriginal
            binding.etSmsReply.setText(rule.action.autoReplySms ?: "")

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
    }

    private fun saveRule() {
        val name = binding.etName.text.toString().trim().ifBlank { "Правило" }
        val priority = binding.etPriority.text.toString().toIntOrNull() ?: 100
        val pattern = binding.etPattern.text.toString().trim()
        val inContacts = binding.cbInContacts.isChecked
        val isAnon = binding.cbAnonymous.isChecked

        if (pattern.isNotEmpty() && !NumberMatcher.isValidPattern(pattern)) {
            Toast.makeText(this, "Некорректная маска номера", Toast.LENGTH_SHORT).show()
            return
        }

        val strategy = StrategySpec.valueOf(binding.spinnerStrategy.selectedItem as String)
        val verdict = VerdictSpec.valueOf(binding.spinnerVerdict.selectedItem as String)
        val target = binding.etTarget.text.toString().trim().ifBlank { null }
        val dtmfTransfer = binding.cbDtmfTransfer.isChecked
        val smsReply = binding.etSmsReply.text.toString().trim().ifBlank { null }
        if (smsReply != null && verdict != VerdictSpec.DISALLOW_REJECT && verdict != VerdictSpec.DISALLOW_AS_MISSED) {
            Toast.makeText(this, "SMS-автоответ работает только с вердиктом DISALLOW_REJECT / DISALLOW_AS_MISSED", Toast.LENGTH_LONG).show()
            return
        }

        // Сборка условий
        val condList = mutableListOf<ConditionSpec>()
        if (pattern.isNotEmpty()) {
            condList.add(ConditionSpec(type = RuleEngine.TYPE_NUMBER_MATCH, pattern = pattern))
        }
        if (inContacts) {
            condList.add(ConditionSpec(type = RuleEngine.TYPE_IN_CONTACTS, value = true))
        }
        if (isAnon) {
            condList.add(ConditionSpec(type = RuleEngine.TYPE_ANONYMOUS, value = true))
        }

        val conditions = if (condList.isEmpty()) ConditionGroup() else ConditionGroup(listOf(condList))

        val action = Action(
            verdict = verdict,
            strategy = strategy,
            target = target,
            dtmfTransferOriginal = dtmfTransfer,
            autoReplySms = smsReply,
        )

        val toSave = Rule(
            id = ruleId,
            name = name,
            priority = priority,
            enabled = existingRule?.enabled ?: true,
            conditions = conditions,
            action = action,
            simSelector = existingRule?.simSelector ?: "ANY",
        )

        lifecycleScope.launch {
            app.ruleStore.save(toSave)
            if (smsReply != null && !app.smsReplier.hasPermission()) {
                smsPermLauncher.launch(android.Manifest.permission.SEND_SMS)
                return@launch
            }
            Toast.makeText(this@RuleEditActivity, "Правило сохранено", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun showTestDialog() {
        val input = EditText(this).apply {
            hint = "+358401234567"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this)
            .setTitle("Тестирование номера")
            .setMessage("Введите номер телефона для проверки срабатывания движка правил:")
            .setView(input)
            .setPositiveButton("Проверить") { _, _ ->
                val raw = input.text.toString().trim()
                val norm = app.normalizer.normalize(raw)
                val ctx = CallContext(
                    rawHandle = raw,
                    e164 = norm.e164,
                    national = norm.national,
                    isEmergency = norm.isEmergency,
                )
                lifecycleScope.launch {
                    val decision = app.ruleEngine.evaluate(ctx)
                    AlertDialog.Builder(this@RuleEditActivity)
                        .setTitle("Результат проверки")
                        .setMessage("Вердикт: ${decision.verdict}\nСтратегия: ${decision.strategy}\nЦель: ${decision.target ?: "нет"}\nПричина: ${decision.reason}\nВремя: ${decision.engineMs} мс")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    companion object {
        const val EXTRA_RULE_ID = "extra_rule_id"
    }
}
