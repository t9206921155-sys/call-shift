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
}
