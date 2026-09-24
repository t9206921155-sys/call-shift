package fi.callshift.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import fi.callshift.app.CallShiftApp
import fi.callshift.app.databinding.ActivityDiagnosticsBinding

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        renderReport()
    }

    private fun renderReport() {
        val report = app.detector.detect()
        val pkg = packageName

        val sb = StringBuilder()
        sb.append("Устройство: ${report.manufacturer} ${report.model} (API ${report.apiLevel})\n")
        sb.append("Профиль: ${report.profile.name}\n\n")

        sb.append("Роли:\n")
        sb.append(" • CALL_SCREENING: ${if (report.isCallScreeningRole) "ВЫДАНА" else "НЕТ"}\n")
        sb.append(" • ROLE_DIALER: ${if (report.isDefaultDialer) "ВЫДАНА" else "НЕТ"}\n")
        sb.append(" • Root: ${if (report.isRooted) "ДА" else "НЕТ"}\n\n")

        sb.append("Разрешения:\n")
        report.grantedPermissions.forEach { (perm, granted) ->
            val shortName = perm.substringAfterLast('.')
            sb.append(" • $shortName: ${if (granted) "OK" else "НЕТ"}\n")
        }

        sb.append("\nОптимизация батареи (Doze): ")
        sb.append(if (report.isIgnoringBatteryOptimizations) "Исключено (OK)" else "Включена (OEM может убить сервис)")

        binding.tvReport.text = sb.toString()

        val adb = buildString {
            append("# Разрешения и роли для CallShift\n")
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
