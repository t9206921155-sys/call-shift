package fi.callshift.app.telecom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import fi.callshift.app.domain.PermissionProfile
import java.io.File

/**
 * Определение профиля полномочий приложения (ТЗ п. 3.5, FR-P1..FR-P4).
 *
 * Профиль влияет на:
 *  - доступные флаги CallResponse (silence / skipNotification / skipCallLog);
 *  - доступность стратегий (ForwardStrategy.isAvailable);
 *  - доступность управления вызовом (InCallService — только профиль B/C);
 *  - то, что показывается на экране «Диагностика».
 */
class PermissionProfileDetector(private val context: Context) {

    data class Report(
        val profile: PermissionProfile,
        val isCallScreeningRole: Boolean,
        val isDefaultDialer: Boolean,
        val isRooted: Boolean,
        val grantedPermissions: Map<String, Boolean>,
        val isIgnoringBatteryOptimizations: Boolean,
        val manufacturer: String = Build.MANUFACTURER,
        val model: String = Build.MODEL,
        val apiLevel: Int = Build.VERSION.SDK_INT,
    )

    fun detect(): Report {
        val screening = isCallScreeningApp()
        val dialer = isDefaultDialer()
        val root = hasRoot()
        val profile = when {
            root && hasPrivilegedAudio() -> PermissionProfile.SYSTEM
            dialer -> PermissionProfile.DIALER
            screening -> PermissionProfile.SCREENING
            else -> PermissionProfile.NONE
        }
        val battery = runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            power.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

        return Report(
            profile = profile,
            isCallScreeningRole = screening,
            isDefaultDialer = dialer,
            isRooted = root,
            grantedPermissions = REQUIRED_PERMISSIONS.associateWith {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
            isIgnoringBatteryOptimizations = battery,
        )
    }

    /**
     * Роль CALL_SCREENING через RoleManager (API 29+).
     *
     * На Android 9 (API 28) публичного API для проверки нет
     * (TelecomManager.getDefaultCallScreeningApp() — @SystemApi), поэтому там
     * screening работает ТОЛЬКО если приложение является default dialer
     * (ТЗ D1, Приложение E P-8).
     */
    fun isCallScreeningApp(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as android.app.role.RoleManager
            roleManager.isRoleAvailable(ROLE_CALL_SCREENING) && roleManager.isRoleHeld(ROLE_CALL_SCREENING)
        } else {
            false
        }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun isDefaultDialer(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as android.app.role.RoleManager
            roleManager.isRoleAvailable(ROLE_DIALER) && roleManager.isRoleHeld(ROLE_DIALER)
        } else {
            val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.defaultDialerPackage == context.packageName
        }
    }.getOrDefault(false)

    fun hasRoot(): Boolean = ROOT_PATHS.any { File(it).exists() } ||
        runCatching { ProcessBuilder("which", "su").start().waitFor() == 0 }.getOrDefault(false)

    /** signature-разрешения, дающие уровень L3 аудио-моста (ТЗ п. 10.7). */
    private fun hasPrivilegedAudio(): Boolean =
        ContextCompat.checkSelfPermission(context, "android.permission.CAPTURE_AUDIO_OUTPUT") ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        /** Константы ролей: публичны с API 29, до этого берём строкой (safe). */
        const val ROLE_CALL_SCREENING = "android.app.role.CALL_SCREENING"
        const val ROLE_DIALER = "android.app.role.DIALER"

        val REQUIRED_PERMISSIONS = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.POST_NOTIFICATIONS
            else Manifest.permission.READ_PHONE_STATE,
        ).distinct()

        private val ROOT_PATHS = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/system/app/Superuser.apk", "/data/local/xbin/su", "/data/local/bin/su",
        )
    }
}
