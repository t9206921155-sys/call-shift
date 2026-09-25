package fi.callshift.app.data

import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SettingsBackupManager(private val app: CallShiftApp) {
    suspend fun snapshot(): SettingsBackup {
        val s = app.settings
        return SettingsBackup(createdAt = System.currentTimeMillis(), rules = app.ruleStore.rules(),
            autoReply = s.autoReply, whitelist = s.whitelist, repeatEnabled = s.repeatCallEnabled,
            repeatMinutes = s.repeatCallMinutes, smsLimit = s.smsDailyLimit,
            perSimCooldown = s.smsCooldownPerSim, maskUi = s.maskNumbersInUi)
    }
    suspend fun restore(backup: SettingsBackup) = withContext(Dispatchers.IO) {
        lock.withLock {
            // Validate before touching any live data.
            BackupCodec.decode(BackupCodec.encode(backup))
            val previous = snapshot()
            val safe = BackupCodec.safeRestore(backup)
            app.settings.pauseForRestore()
            try {
                app.ruleStore.replaceAll(safe.rules)
                applySettings(safe)
                // Neutral policy; imported data cannot cause dialing/network actions
                // on calls not matching the imported (disabled) rules.
                app.settings.setDefaultPolicy(DefaultPolicy())
            } catch (error: Exception) {
                runCatching { app.ruleStore.replaceAll(previous.rules); applySettings(previous.copy(autoReply = previous.autoReply.copy(enabled = false))) }
                throw error
            }
            // Master remains OFF on success/failure. User reviews SIMs/roles first.
        }
    }
    private fun applySettings(b: SettingsBackup) {
        app.settings.setAutoReply(b.autoReply)
        app.settings.setWhitelist(b.whitelist)
        app.settings.setRepeatCall(b.repeatEnabled, b.repeatMinutes)
        app.settings.setSmsSafety(b.smsLimit, b.perSimCooldown)
        app.settings.setMaskNumbersInUi(b.maskUi)
    }
    companion object { private val lock = Mutex() }
}
