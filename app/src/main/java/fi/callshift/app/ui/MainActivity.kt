package fi.callshift.app.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R
import fi.callshift.app.databinding.ActivityMainBinding
import fi.callshift.app.databinding.ItemRuleBinding
import fi.callshift.app.domain.PermissionProfile
import fi.callshift.app.domain.Rule
import fi.callshift.app.telecom.MmiCodes
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }

    private val screeningRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        updateStatus()
    }

    private val dialerRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        updateStatus()
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        updateStatus()
    }

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
        showLastCrashIfAny()
    }

    /** Если приложение падало — показать текст ошибки с кнопкой «Копировать». */
    private fun showLastCrashIfAny() {
        val report = fi.callshift.app.util.CrashReporter.takeLastCrash(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("Приложение было закрыто из-за ошибки")
            .setMessage(report.take(4000))
            .setPositiveButton("Копировать") { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("CallShift crash", report))
                Toast.makeText(this, "Текст ошибки скопирован — отправьте его разработчику", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        loadRules()
        updateTodayStats()
    }

    private fun setupListeners() {
        binding.switchAutoReply.setOnCheckedChangeListener { btn, on ->
            if (!btn.isPressed) return@setOnCheckedChangeListener
            val cur = app.settings.autoReply
            app.settings.setAutoReply(if (on) cur.copy(enabled = true, untilMs = cur.untilMs?.takeIf { it > System.currentTimeMillis() }) else cur.copy(enabled = false))
            AutoReplyTileService.requestUpdate(this)
            updateAutoReply()
        }
        binding.btnAutoReplySetup.setOnClickListener { startActivity(Intent(this, AutoReplyActivity::class.java)) }
        binding.btnWhitelist.setOnClickListener { startActivity(Intent(this, WhitelistActivity::class.java)) }
        binding.switchMaster.isChecked = app.settings.masterEnabled
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            app.settings.setMasterEnabled(isChecked)
            updateStatus()
            lifecycleScope.launch {
                val rulesCount = app.ruleStore.rules().count { it.enabled }
                app.notifier.showStatus(isChecked, app.profile.name, rulesCount)
            }
        }

        // Кнопка назначения роли Call Screening прямо из приложения
        binding.btnGrantRole.setOnClickListener {
            requestScreeningRole()
        }

        // Клик по статусной карточке также вызывает запрос роли, если она не выдана
        binding.cardStatus.setOnClickListener {
            val report = app.detector.detect()
            if (!report.isCallScreeningRole && !report.isDefaultDialer) {
                requestScreeningRole()
            }
        }

        // Кнопка выдачи разрешений
        binding.btnGrantPerms.setOnClickListener {
            requestRuntimePermissions()
        }

        binding.btnDialer.setOnClickListener {
            startActivity(Intent(this, DialerActivity::class.java))
        }

        binding.btnCarrier.setOnClickListener {
            startActivity(Intent(this, CarrierForwardActivity::class.java))
        }

        binding.tvTodayStats.setOnClickListener { startActivity(Intent(this, LogActivity::class.java)) }

        binding.btnLogs.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        binding.btnDiag.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        binding.btnTheme.setOnClickListener {
            val modes = listOf(
                fi.callshift.app.data.SettingsStore.THEME_DARK to "Тёмная",
                fi.callshift.app.data.SettingsStore.THEME_LIGHT to "Светлая",
                fi.callshift.app.data.SettingsStore.THEME_SYSTEM to "Как в системе",
            )
            val cur = modes.indexOfFirst { it.first == app.settings.themeMode }.coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("Тема оформления")
                .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), cur) { d, i ->
                    d.dismiss()
                    app.settings.setThemeMode(modes[i].first)
                    fi.callshift.app.CallShiftApp.applyTheme(modes[i].first)
                }
                .show()
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

    fun requestScreeningRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                    Toast.makeText(this, "Роль перехвата уже назначена!", Toast.LENGTH_SHORT).show()
                } else {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                    screeningRoleLauncher.launch(intent)
                }
                return
            }
        }

        // Fallback для устройств, где RoleManager недоступен или на кастомных прошивках (MIUI/ColorOS)
        showDefaultAppsDialog()
    }

    fun requestDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                dialerRoleLauncher.launch(intent)
                return
            }
        }

        @Suppress("DEPRECATION")
        val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
            putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
        }
        runCatching { startActivity(intent) }.onFailure { showDefaultAppsDialog() }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.SEND_SMS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(perms.toTypedArray())
    }

    private fun showDefaultAppsDialog() {
        AlertDialog.Builder(this)
            .setTitle("Настройка роли перехвата")
            .setMessage("В настройках вашего устройства откройте:\nПриложения → Приложения по умолчанию → «Определение номера и спам-фильтр» (Caller ID & spam) и выберите CallShift.")
            .setPositiveButton("Открыть настройки") { _, _ ->
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                }.onFailure {
                    runCatching {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                }
            }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun updateAutoReply() {
        val ar = app.settings.autoReply
        val active = ar.isActiveAt(System.currentTimeMillis())
        binding.switchAutoReply.isChecked = active
        binding.tvAutoReply.text = AutoReplyActivity.summary(ar)
        binding.cardAutoReply.setStrokeColor(if (active) getColor(R.color.brand_accent) else getColor(R.color.card_stroke))
        binding.btnWhitelist.text = "Белый список (${app.settings.whitelist.size})"
    }

    private fun updateStatus() {
        updateAutoReply()
        val report = app.detector.detect()
        binding.switchMaster.isChecked = app.settings.masterEnabled

        val hasRole = report.isCallScreeningRole || report.isDefaultDialer
        val missingPerms = report.grantedPermissions.filter { !it.value }.keys

        if (!hasRole) {
            binding.tvStatus.text = "Статус: Внимание! Нет роли перехвата вызовов.\nНажмите кнопку ниже, чтобы включить защиту."
            binding.tvStatus.setTextColor(getColor(R.color.status_error))
            binding.btnGrantRole.visibility = View.VISIBLE
            binding.btnGrantRole.text = "Выдать роль перехвата (Call Screening)"
            binding.cardStatus.strokeColor = getColor(R.color.status_error)
        } else {
            val profileStr = when (report.profile) {
                PermissionProfile.SYSTEM -> "Профиль C (System / Root)"
                PermissionProfile.DIALER -> "Профиль B (Основной телефон)"
                PermissionProfile.SCREENING -> "Профиль A (Call Screening)"
                PermissionProfile.NONE -> "Внимание: нет роли!"
            }
            val simCount = app.telecom.phoneAccounts().size
            binding.tvStatus.text = buildString {
                append("Статус: ")
                append(if (app.settings.masterEnabled) "АКТИВЕН" else "ОТКЛЮЧЁН")
                append(" · ").append(profileStr)
                if (simCount > 0) append("\n").append(getString(R.string.status_sim_count, simCount))
                if (app.settings.repeatCallEnabled) {
                    append("\nПовторные звонки за ").append(app.settings.repeatCallMinutes)
                        .append(" мин проходят — исключение включено")
                }
            }
            binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
            binding.btnGrantRole.visibility = View.GONE
            binding.cardStatus.strokeColor = getColor(R.color.brand_accent)
        }

        // Если не хватает базовых разрешений — показываем кнопку
        if (missingPerms.isNotEmpty()) {
            binding.btnGrantPerms.visibility = View.VISIBLE
        } else {
            binding.btnGrantPerms.visibility = View.GONE
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

    /** Цвет правила по действию: сброс — красный, без звука — оранжевый, пропуск — зелёный, перенаправление — бирюзовый. */
    private fun ruleColor(rule: Rule): Int = when {
        !rule.enabled -> getColor(R.color.rule_disabled)
        rule.action.strategy.name != "NONE" && rule.action.strategy.name != "NOTIFY" -> EventView.Kind.FORWARDED.color
        rule.action.verdict == fi.callshift.app.domain.VerdictSpec.PASS -> EventView.Kind.PASSED.color
        rule.action.verdict == fi.callshift.app.domain.VerdictSpec.SILENCE -> EventView.Kind.SILENCED.color
        else -> EventView.Kind.REJECTED.color
    }

    private fun updateTodayStats() {
        lifecycleScope.launch {
            val today = EventView.startOfToday()
            val events = app.eventStore.events(limit = 1000).filter { it.ts >= today }
            binding.tvTodayStats.text = if (events.isEmpty()) "📊 Сегодня событий не было"
            else "📊 Сегодня: " + EventView.stats(events).text() + "  ›"
        }
    }

    private fun bindRuleItem(item: ItemRuleBinding, rule: Rule) {
        item.tvPriority.text = when {
            rule.priority <= 50 -> "1-е"
            rule.priority >= 500 -> "посл."
            else -> "обыч."
        }
        item.tvRuleName.text = rule.name
        item.switchEnabled.isChecked = rule.enabled

        item.tvSummary.text = buildString {
            val conds = rule.conditions.anyOf.flatten()
            val who = when {
                conds.any { it.type == fi.callshift.app.domain.RuleEngine.TYPE_ANONYMOUS && it.value == true } -> "скрытые номера"
                conds.any { it.type == fi.callshift.app.domain.RuleEngine.TYPE_IN_CONTACTS && it.value == true } -> "только контакты"
                conds.any { it.type == fi.callshift.app.domain.RuleEngine.TYPE_IN_CONTACTS && it.value == false } -> "только незнакомые"
                else -> "все звонки"
            }
            append("Для: ").append(who)
            conds.firstOrNull { it.type == fi.callshift.app.domain.RuleEngine.TYPE_NUMBER_MATCH }?.pattern
                ?.takeIf { it != "*" }?.let { append(" · номер ").append(it) }
            if (rule.action.autoReplySms != null) {
                append(" · ").append(fi.callshift.app.domain.ReplyOptions.labels(rule.action.replyChannel, rule.action.replyChannels))
                append(" · ").append(fi.callshift.app.domain.ReplyOptions.intervalLabel(rule.action.replyCooldownMinutes))
            }
            ScheduleEditor.shortText(rule)?.let { append(" · ").append(it) }
            if (rule.simSelector != "ANY") {
                val id = rule.simSelector.removePrefix(fi.callshift.app.domain.SimSelector.HANDLE_PREFIX)
                val accounts = app.telecom.phoneAccounts()
                val idx = accounts.keys.indexOf(id)
                val simName = when {
                    idx >= 0 -> "SIM ${idx + 1} (${accounts[id]})"
                    rule.simSelector == "SIM1" -> "SIM 1"
                    rule.simSelector == "SIM2" -> "SIM 2"
                    else -> "SIM ?"
                }
                append(" · ").append(simName)
            }
        }

        item.tvAction.text = buildString {
            append("Действие: ")
            append(RuleLabels.strategyTitle(rule.action.strategy.name))
            if (!rule.action.target.isNullOrBlank()) {
                val target = if (app.settings.maskNumbersInUi) app.normalizer.mask(rule.action.target) else rule.action.target
                append(" → ").append(target)
            }
            append(" · ").append(RuleLabels.verdictTitle(rule.action.verdict))
            item.vRuleStripe.setBackgroundColor(ruleColor(rule))
            item.tvAction.setTextColor(ruleColor(rule))
        }

        item.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                app.ruleStore.setEnabled(rule.id, isChecked)
            }
            val c = ruleColor(rule.copy(enabled = isChecked))
            item.vRuleStripe.setBackgroundColor(c)
            item.tvAction.setTextColor(c)
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
