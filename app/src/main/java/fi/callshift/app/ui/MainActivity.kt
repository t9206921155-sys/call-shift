package fi.callshift.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import fi.callshift.app.CallShiftApp
import fi.callshift.app.databinding.ActivityMainBinding
import fi.callshift.app.databinding.ItemRuleBinding
import fi.callshift.app.domain.Rule
import fi.callshift.app.telecom.MmiCodes
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!app.settings.disclaimerAccepted) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        loadRules()
    }

    private fun setupListeners() {
        binding.switchMaster.isChecked = app.settings.masterEnabled
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            app.settings.setMasterEnabled(isChecked)
            updateStatus()
            lifecycleScope.launch {
                val rulesCount = app.ruleStore.rules().count { it.enabled }
                app.notifier.showStatus(isChecked, app.profile.name, rulesCount)
            }
        }

        binding.btnDialer.setOnClickListener {
            startActivity(Intent(this, DialerActivity::class.java))
        }

        binding.btnCarrier.setOnClickListener {
            startActivity(Intent(this, CarrierForwardActivity::class.java))
        }

        binding.btnLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        binding.btnDiag.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        binding.btnPanic.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Режим паники")
                .setMessage("Снять ВСЕ переадресации (MMI ##002# / ##21#) на всех SIM и отключить все правила?")
                .setPositiveButton("Снять всё") { _, _ ->
                    executePanic()
                }
                .setNegativeButton("Отмена", null)
                .show()
        }

        binding.fabAddRule.setOnClickListener {
            startActivity(Intent(this, RuleEditActivity::class.java))
        }
    }

    private fun updateStatus() {
        val report = app.detector.detect()
        binding.switchMaster.isChecked = app.settings.masterEnabled

        val profileStr = when (report.profile) {
            fi.callshift.app.domain.PermissionProfile.SYSTEM -> "Профиль C (System / Root)"
            fi.callshift.app.domain.PermissionProfile.DIALER -> "Профиль B (Default Dialer)"
            fi.callshift.app.domain.PermissionProfile.SCREENING -> "Профиль A (Call Screening)"
            fi.callshift.app.domain.PermissionProfile.NONE -> "Внимание: нет роли перехвата!"
        }

        val simCount = app.telecom.phoneAccounts().size
        binding.tvStatus.text = buildString {
            append("Статус: ")
            append(if (app.settings.masterEnabled) "АКТИВЕН" else "ОТКЛЮЧЁН")
            append(" · ").append(profileStr)
            if (simCount > 0) append(" · SIM-карт: ").append(simCount)
        }
    }

    private fun loadRules() {
        lifecycleScope.launch {
            val rules = app.ruleStore.rules()
            binding.rulesContainer.removeAllViews()

            if (rules.isEmpty()) {
                binding.tvEmptyRules.visibility = View.VISIBLE
            } else {
                binding.tvEmptyRules.visibility = View.GONE
                val inflater = LayoutInflater.from(this@MainActivity)
                rules.forEach { rule ->
                    val itemBinding = ItemRuleBinding.inflate(inflater, binding.rulesContainer, false)
                    bindRuleItem(itemBinding, rule)
                    binding.rulesContainer.addView(itemBinding.root)
                }
            }
        }
    }

    private fun bindRuleItem(item: ItemRuleBinding, rule: Rule) {
        item.tvPriority.text = "#${rule.priority}"
        item.tvRuleName.text = rule.name
        item.switchEnabled.isChecked = rule.enabled

        item.tvSummary.text = buildString {
            if (rule.conditions.isEmpty) {
                append("Условие: любые вызовы")
            } else {
                append("Условий: ").append(rule.conditions.anyOf.flatten().size)
            }
            if (rule.schedule != null) append(" · расписание")
            if (rule.simSelector != "ANY") append(" · ").append(rule.simSelector)
        }

        item.tvAction.text = buildString {
            append("Действие: ")
            append(rule.action.strategy.name)
            if (!rule.action.target.isNullOrBlank()) {
                val target = if (app.settings.maskNumbersInUi) app.normalizer.mask(rule.action.target) else rule.action.target
                append(" → ").append(target)
            }
            append(" (").append(rule.action.verdict.name).append(")")
        }

        item.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                app.ruleStore.setEnabled(rule.id, isChecked)
            }
        }

        item.root.setOnClickListener {
            val intent = Intent(this, RuleEditActivity::class.java).apply {
                putExtra(RuleEditActivity.EXTRA_RULE_ID, rule.id)
            }
            startActivity(intent)
        }

        item.root.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Удалить правило?")
                .setMessage("Правило «${rule.name}» будет удалено.")
                .setPositiveButton("Удалить") { _, _ ->
                    lifecycleScope.launch {
                        app.ruleStore.delete(rule.id)
                        loadRules()
                    }
                }
                .setNegativeButton("Отмена", null)
                .show()
            true
        }
    }

    private fun executePanic() {
        app.settings.setMasterEnabled(false)
        lifecycleScope.launch {
            val accounts = app.telecom.phoneAccounts().keys
            val sims = if (accounts.isEmpty()) listOf<String?>(null) else accounts.toList()
            sims.forEach { simId ->
                MmiCodes.panicSequence().forEach { code ->
                    app.telecom.placeMmi(simId, code)
                }
            }
            app.ruleStore.rules().forEach { rule ->
                if (rule.enabled) app.ruleStore.setEnabled(rule.id, false)
            }
            updateStatus()
            loadRules()
            Toast.makeText(this@MainActivity, "Все переадресации сброшены", Toast.LENGTH_SHORT).show()
        }
    }
}
