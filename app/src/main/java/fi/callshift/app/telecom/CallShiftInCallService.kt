package fi.callshift.app.telecom

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import fi.callshift.app.CallShiftApp
import fi.callshift.app.ui.InCallActivity

/**
 * InCallService профиля B (ТЗ FR-P3, п. 3.5, п. 7.2).
 *
 * Обязательные требования роли ROLE_DIALER, которые здесь закрыты:
 *  1. сервис НЕ помечен `android:exported="false"` (иначе Telecom не сможет
 *     привязаться к нему во время вызова);
 *  2. binding никогда не бывает «пустым»: все вызовы регистрируются в
 *     [InCallController], иначе система откатывается на предустановленный
 *     dialer и показывает пользователю уведомление об этом;
 *  3. есть и экран входящего вызова, и экран активного вызова
 *     ([InCallActivity]);
 *  4. исходящие вызовы ставятся через `TelecomManager.placeCall(Uri, Bundle)`
 *     (AndroidTelecomPort), а не через `ACTION_CALL` — так платформа
 *     корректно обрабатывает в том числе экстренные номера.
 *
 * Манифест: `android:permission="android.permission.BIND_INCALL_SERVICE"` +
 * meta-data `android.telecom.INCLUDE_SELF_MANAGED_CALLS = false`
 * (по умолчанию self-managed вызовы нам не отдаются — ТЗ FR-2.7).
 */
class CallShiftInCallService : InCallService() {

    private val controller = InCallController.get()

    override fun onCreate() {
        super.onCreate()
        controller.attach(this, applicationContext)
        Log.i(TAG, "InCallService created")
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        controller.onCallAdded(call)
        showInCallUi()
        Log.i(TAG, "call added: ${describe(call)}")
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        controller.onCallRemoved(call)
        if (!controller.hasCalls()) {
            fi.callshift.app.ui.CallRecorder.stop()
            // Вызовов не осталось — закрываем экран звонка.
            runCatching {
                startActivity(
                    Intent(this, InCallActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }
        }
        Log.i(TAG, "call removed: ${describe(call)}")
    }

    /**
     * Экран звонка. Поднимаем только когда есть хотя бы один вызов —
     * иначе finish() в InCallActivity создавал бы лишние циклы запуска.
     */
    private fun showInCallUi() {
        if (!controller.hasCalls()) return
        runCatching {
            val intent = Intent(this, InCallActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            }
            startActivity(intent)
        }.onFailure { Log.e(TAG, "не удалось показать InCallActivity", it) }
    }

    private fun describe(call: Call): String = runCatching {
        "${call.details?.handle?.schemeSpecificPart ?: "unknown"} state=${call.state}"
    }.getOrDefault("unknown")

    override fun onDestroy() {
        controller.detach(this)
        // Фиксируем в журнале факт жизни сервиса — помогает в полевой отладке (LOG-2).
        runCatching {
            val app = CallShiftApp.from(this)
            Log.i(TAG, "InCallService destroyed, profile=${app.profile}")
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallShift"
    }
}
