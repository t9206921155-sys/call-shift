package fi.callshift.app.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import fi.callshift.app.CallShiftApp
import fi.callshift.app.telecom.MmiCodes
import kotlinx.coroutines.launch

/**
 * Восстановление состояния после перезагрузки устройства или обновления пакета
 * (ТЗ FR-8.6, E-01, E-07).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "onReceive: $action")
        val app = CallShiftApp.from(context)

        app.appScope.launch {
            // 1. Проверяем роль перехвата
            val report = app.detector.detect()
            if (!report.isCallScreeningRole && !report.isDefaultDialer) {
                app.notifier.showError("После перезагрузки потеряна роль перехвата вызовов! Откройте приложение.")
            }

            // 2. Восстанавливаем желаемое состояние MMI (таблица cf_state, ТЗ п. 9.3)
            val desired = app.settings.allDesiredCf()
            desired.forEach { (simAndCode, target) ->
                val (simId, code) = simAndCode
                val mmi = "*$code*${target.removePrefix("+")}#"
                Log.i(TAG, "restoring MMI on SIM $simId: $mmi")
                app.telecom.placeMmi(simId, mmi)
            }
        }
    }

    companion object {
        private const val TAG = "CallShiftBoot"
    }
}

/**
 * Внешнее управление через adb broadcast / автоматизацию (ТЗ FR-8.5).
 * Защищено собственной signature-permission fi.callshift.app.permission.CONTROL.
 */
class CommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val app = CallShiftApp.from(context)

        when (action) {
            ACTION_MASTER_TOGGLE -> {
                val explicit = intent.hasExtra("enabled")
                val targetState = if (explicit) intent.getBooleanExtra("enabled", true) else !app.settings.masterEnabled
                app.settings.setMasterEnabled(targetState)
                Log.i(TAG, "master toggle: $targetState")
            }

            ACTION_PANIC -> {
                // Режим паники (FR-9.5, A-10): снимаем все переадресации и выключаем мастер
                app.settings.setMasterEnabled(false)
                app.appScope.launch {
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
                }
                Log.w(TAG, "PANIC command executed!")
            }

            ACTION_SET_PRESET -> {
                val preset = intent.getStringExtra("preset") ?: return
                Log.i(TAG, "preset requested: $preset")
            }
        }
    }

    companion object {
        private const val TAG = "CallShiftCmd"
        const val ACTION_MASTER_TOGGLE = "fi.callshift.app.action.MASTER_TOGGLE"
        const val ACTION_SET_PRESET = "fi.callshift.app.action.SET_PRESET"
        const val ACTION_PANIC = "fi.callshift.app.action.PANIC"
    }
}
