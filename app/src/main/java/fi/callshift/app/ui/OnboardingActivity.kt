package fi.callshift.app.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import fi.callshift.app.CallShiftApp
import fi.callshift.app.databinding.ActivityOnboardingBinding

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }

    private val screeningRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        updateStepButtons()
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        updateStepButtons()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateStepButtons()
    }

    private fun setupListeners() {
        binding.cbDisclaimer.setOnCheckedChangeListener { _, isChecked ->
            binding.btnRequestPerms.isEnabled = isChecked
            binding.btnRequestRole.isEnabled = isChecked
            binding.btnContinue.isEnabled = isChecked
        }

        binding.btnRequestPerms.setOnClickListener {
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

        binding.btnRequestRole.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = getSystemService(RoleManager::class.java)
                if (roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
                    screeningRoleLauncher.launch(intent)
                    return@setOnClickListener
                }
            }

            // Fallback: переход в системные настройки приложений по умолчанию
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

        binding.btnContinue.setOnClickListener {
            app.settings.acceptDisclaimer()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun updateStepButtons() {
        val report = app.detector.detect()
        if (report.isCallScreeningRole || report.isDefaultDialer) {
            binding.btnRequestRole.text = "✓ 2. Роль перехвата назначена"
            binding.btnRequestRole.isEnabled = false
        } else if (binding.cbDisclaimer.isChecked) {
            binding.btnRequestRole.text = "2. Назначить роль Call Screening"
            binding.btnRequestRole.isEnabled = true
        }

        val allPermsGranted = report.grantedPermissions.all { it.value }
        if (allPermsGranted) {
            binding.btnRequestPerms.text = "✓ 1. Разрешения получены"
            binding.btnRequestPerms.isEnabled = false
        } else if (binding.cbDisclaimer.isChecked) {
            binding.btnRequestPerms.text = "1. Запросить разрешения (Телефон/Контакты)"
            binding.btnRequestPerms.isEnabled = true
        }
    }
}
