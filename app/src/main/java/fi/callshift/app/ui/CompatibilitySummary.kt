package fi.callshift.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyManager
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.SmsReadiness

object CompatibilitySummary {
    fun text(app: CallShiftApp): String {
        val report = app.detector.detect()
        val accounts = app.telecom.phoneAccounts()
        @Suppress("DEPRECATION")
        val sms = runCatching { app.getSystemService(TelephonyManager::class.java)?.isSmsCapable == true }.getOrDefault(false)
        val input = SmsReadiness.Input(Build.VERSION.SDK_INT,
            app.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY), sms,
            report.isDefaultDialer, report.isCallScreeningRole,
            app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
            app.smsReplier.hasPermission(), accounts.size, accounts.keys.count { app.smsReplier.canUseAccount(it) })
        val problems = SmsReadiness.problems(input)
        return (if (problems.isEmpty()) "Сброс + SMS: базовые условия выполнены. Нужен тест звонка и SMS."
            else "Сброс + SMS: нужна настройка / есть ограничения\n" + problems.joinToString("\n") { "• $it" }) +
            "\nПроверка не подтверждает баланс, сеть и работу прошивки. Для ответов контактам выдайте доступ к контактам. На двух SIM проверьте каждую отдельно."
    }
}
