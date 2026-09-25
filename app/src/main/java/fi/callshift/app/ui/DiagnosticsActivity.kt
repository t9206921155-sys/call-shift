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
        binding.btnTestSms.setOnClickListener { testSms() }
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
                Manifest.permission.SEND_SMS,
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

    /** Real SMS only after explicit number/SIM confirmation, never on opening diagnostics. */
    private fun testSms() {
        val accounts = app.telecom.phoneAccounts().entries.toList()
        if (accounts.isEmpty() || !app.smsReplier.hasPermission()) {
            Toast.makeText(this, "Сначала выдайте разрешения «Телефон» и «SMS» кнопкой ниже", Toast.LENGTH_LONG).show()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Выберите SIM для тестовой SMS")
            .setItems(accounts.mapIndexed { i, e -> "SIM ${i + 1}: ${e.value}" }.toTypedArray()) { _, position ->
                val account = accounts[position]
                if (!app.smsReplier.canUseAccount(account.key)) {
                    Toast.makeText(this, "Эта SIM не определена для SMS. Другая карта не будет использована.", Toast.LENGTH_LONG).show()
                    return@setItems
                }
                val input = android.widget.EditText(this).apply {
                    hint = "+7… — номер получателя"
                    inputType = android.text.InputType.TYPE_CLASS_PHONE
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Тестовая SMS с ${account.value}")
                    .setMessage("Введите номер своего второго телефона. Будет отправлена настоящая SMS «CallShift test» по тарифу оператора. Правила и пауза автоответов в ручном тесте не используются. Общий лимит расходов SMS действует.")
                    .setView(input)
                    .setPositiveButton("Далее") { _, _ ->
                        val number = app.normalizer.normalize(input.text.toString()).e164
                        if (number == null || !fi.callshift.app.domain.ReplyChannel.isPhoneAddress(number)) {
                            Toast.makeText(this, "Некорректный номер", Toast.LENGTH_LONG).show()
                        } else androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle("Отправить SMS?")
                            .setMessage("Получатель: $number\nSIM: ${account.value}\nТекст: CallShift test\nВозможна оплата по тарифу. Результат появится в Журнале → SMS.")
                            .setPositiveButton("Отправить") { _, _ ->
                                val error = app.smsReplier.sendQuickReply(number, "CallShift test", account.key)
                                Toast.makeText(this, error ?: "Запрос отправки принят. Результат — в журнале SMS.", Toast.LENGTH_LONG).show()
                            }.setNegativeButton("Отмена", null).show()
                    }
                    .setNegativeButton("Отмена", null).show()
            }.setNegativeButton("Отмена", null).show()
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

        sb.append("\n\nОтветы звонящим:\n")
        sb.append(" • SMS: ${if (app.smsReplier.hasPermission()) "разрешена" else "НЕТ разрешения SEND_SMS"}\n")
        val accounts = app.telecom.phoneAccounts()
        if (accounts.isEmpty()) sb.append(" • SIM не определены — проверьте разрешение «Телефон»\n")
        accounts.forEach { (id, label) ->
            sb.append(" • ${label.ifBlank { "SIM" }}: ${if (app.smsReplier.canUseAccount(id)) "SIM для SMS определена" else "SIM для SMS НЕ определена — отправка запрещена"}\n")
        }
        sb.append(" • Повторные звонки: ${if (app.settings.repeatCallEnabled) "пропускаются как срочные" else "проверяются по правилам"}\n")
        sb.append(" • Отдельный автоответчик: ${if (app.settings.autoReply.isActiveAt(System.currentTimeMillis())) "АКТИВЕН, проверяется раньше правил" else "не активен"}\n")
        sb.append(" • Уведомления: ${if (androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) "разрешены" else "запрещены"}\n")
        sb.append(" • Главный переключатель: ${if (app.settings.masterEnabled) "ВКЛЮЧЁН" else "ВЫКЛЮЧЕН"}\n")
        sb.append(" • Белый список: ${app.settings.whitelist.size} номеров — исключения пропускаются\n")
        sb.append(" • Лимит SMS: ${app.settings.smsDailyLimit} частей за последние 24 часа; счётчик — в «SMS: защита и копия»\n")
        sb.append(" • Telegram-аккаунт: отдельное подключение для автоотправки. MAX/WhatsApp — ручные.\n")
        sb.append(" • Проверка SIM не проверяет баланс, сеть и доставку оператором.\n")
        binding.tvReport.text = sb.toString()

        val adb = buildString {
            append("# Разрешения и роли для CallShift через ADB:\n")
            append("adb shell pm grant $pkg android.permission.SEND_SMS\n")
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
}
