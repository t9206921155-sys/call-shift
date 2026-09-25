package fi.callshift.app.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import fi.callshift.app.CallShiftApp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import fi.callshift.app.domain.SimSelector
import fi.callshift.app.domain.VerdictSpec
import fi.callshift.app.databinding.ActivityDiagnosticsBinding

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }

    private val screeningRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        renderReport()
    }

    private val dialerRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        renderReport()
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        renderReport()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
    }

    override fun onResume() {
        super.onResume()
        renderReport()
    }

    private fun setupButtons() {
        binding.btnActionScreening.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                    screeningRoleLauncher.launch(intent)
                    return@setOnClickListener
                }
            }
            openDefaultAppsSettings()
        }

        binding.btnActionDialer.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    dialerRoleLauncher.launch(intent)
                    return@setOnClickListener
                }
            }
            @Suppress("DEPRECATION")
            val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            runCatching { startActivity(intent) }.onFailure { openDefaultAppsSettings() }
        }

        binding.btnActionPerms.setOnClickListener {
            val perms = mutableListOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_PHONE_NUMBERS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ANSWER_PHONE_CALLS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionsLauncher.launch(perms.toTypedArray())
        }

        binding.btnActionBattery.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }.onFailure {
                    Toast.makeText(this, "Откройте Настройки → Батарея → CallShift вручную", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnActionDefaultApps.setOnClickListener {
            openDefaultAppsSettings()
        }
    }

    private fun openDefaultAppsSettings() {
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

    private fun renderReport() {
        val report = app.detector.detect()
        val pkg = packageName

        val sb = StringBuilder()
        sb.append("Устройство: ${report.manufacturer} ${report.model} (Android API ${report.apiLevel})\n")
        sb.append("Профиль: ${report.profile.name}\n\n")

        sb.append("Роли:\n")
        sb.append(" • CALL_SCREENING: ${if (report.isCallScreeningRole) "ВЫДАНА (OK)" else "НЕ ВЫДАНА (нажмите кнопку ниже)"}\n")
        sb.append(" • ROLE_DIALER: ${if (report.isDefaultDialer) "ВЫДАНА (OK)" else "НЕ ВЫДАНА"}\n")
        sb.append(" • Root: ${if (report.isRooted) "ДА" else "НЕТ"}\n\n")

        sb.append("Разрешения:\n")
        report.grantedPermissions.forEach { (perm, granted) ->
            val shortName = perm.substringAfterLast('.')
            sb.append(" • $shortName: ${if (granted) "OK" else "НЕТ"}\n")
        }

        sb.append("\nОптимизация батареи (Doze): ")
        sb.append(if (report.isIgnoringBatteryOptimizations) "Исключено (OK)" else "Включена (OEM может выгружать сервис)")

        binding.tvReport.text = sb.toString()
        lifecycleScope.launch {
            val ready = runCatching { readinessText() }.getOrElse { "Проверка готовности: ошибка ${it.message}" }
            binding.tvReport.text = ready + "\n\n" + sb.toString()
        }

        val adb = buildString {
            append("# Разрешения и роли для CallShift через ADB:\n")
            append("adb shell pm grant $pkg android.permission.READ_PHONE_STATE\n")
            append("adb shell pm grant $pkg android.permission.CALL_PHONE\n")
            append("adb shell pm grant $pkg android.permission.ANSWER_PHONE_CALLS\n")
            append("adb shell pm grant $pkg android.permission.READ_CONTACTS\n")
            append("adb shell pm grant $pkg android.permission.READ_CALL_LOG\n")
            append("adb shell cmd role add-role-holder android.app.role.CALL_SCREENING $pkg\n")
            append("adb shell cmd role add-role-holder android.app.role.DIALER $pkg\n")
            append("adb shell dumpsys deviceidle whitelist +$pkg\n")
        }

        binding.tvAdbCommands.text = adb

        binding.btnCopyAdb.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CallShift ADB", adb))
            Toast.makeText(this, "Команды скопированы в буфер", Toast.LENGTH_SHORT).show()
        }
    }

    /** «Проверка готовности»: почему правило может не сработать — простым языком. */
    private suspend fun readinessText(): String {
        val r = app.detector.detect()
        val out = StringBuilder("ПРОВЕРКА ГОТОВНОСТИ\n")
        fun line(ok: Boolean, text: String) { out.append(if (ok) "✅ " else "❌ ").append(text).append('\n') }
        line(app.settings.masterEnabled, "Главный переключатель CallShift " + if (app.settings.masterEnabled) "включён" else "ВЫКЛЮЧЕН — звонки не проверяются")
        line(r.isCallScreeningRole || r.isDefaultDialer, if (r.isDefaultDialer) "CallShift — звонилка по умолчанию" else if (r.isCallScreeningRole) "Роль фильтра звонков выдана" else "Нет роли — CallShift не видит звонки")
        val perm = { p: String -> checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        line(perm(Manifest.permission.READ_PHONE_STATE), "Разрешение «Телефон» (нужно для определения SIM)")
        line(perm(Manifest.permission.READ_CONTACTS), "Разрешение «Контакты» (для условий «контакты/незнакомые»)")
        line(r.isIgnoringBatteryOptimizations, "Батарея: " + if (r.isIgnoringBatteryOptimizations) "без ограничений" else "ограничена — система может усыплять CallShift")
        val sims = runCatching { app.telecom.phoneAccounts() }.getOrDefault(emptyMap())
        out.append("\nSIM, которые видит CallShift: ")
        out.append(if (sims.isEmpty()) "не найдены (нет разрешения «Телефон»?)" else sims.values.joinToString(", "))
        out.append("\n\nПравила (проверяются сверху вниз):\n")
        val rules = app.ruleStore.rules()
        if (rules.isEmpty()) out.append("❌ Правил нет — все звонки проходят как обычно\n")
        val now = System.currentTimeMillis()
        rules.forEach { rule ->
            val warn = mutableListOf<String>()
            if (!rule.enabled) warn += "выключено"
            else if (!rule.isActiveAt(now)) warn += "срок действия не наступил или истёк"
            if (rule.schedule != null) warn += "работает только по расписанию"
            if (rule.simSelector != SimSelector.ANY) warn += "только для одной SIM"
            if (rule.action.verdict == VerdictSpec.PASS) warn += "действие «Пропустить» — звонок не сбрасывается"
            val ok = warn.isEmpty() || (rule.enabled && rule.isActiveAt(now) && warn.all { it.startsWith("работает") || it.startsWith("только") })
            out.append(if (warn.isEmpty()) "✅ " else if (ok) "⚠ " else "❌ ")
            out.append("«${rule.name}» → ${RuleLabels.verdictTitle(rule.action.verdict)}")
            if (warn.isNotEmpty()) out.append(" (").append(warn.joinToString("; ")).append(")")
            out.append('\n')
        }
        out.append("\nНе сбрасываются никогда: белый список, повторный звонок в течение 3 минут, экстренные номера.")
        return out.toString()
    }
}
