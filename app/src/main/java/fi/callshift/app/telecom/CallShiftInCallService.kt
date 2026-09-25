package fi.callshift.app.telecom

import android.content.Intent
import android.os.Handler
import android.os.Looper
import fi.callshift.app.domain.Verdict
import fi.callshift.app.screening.CallContextFactory
import kotlinx.coroutines.*
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val callbacks = mutableMapOf<Call, Call.Callback>()
    private val evaluated = mutableSetOf<Call>()
    private val jobs = mutableMapOf<Call, Job>()
    private val rejected = mutableMapOf<Call, Pair<fi.callshift.app.domain.CallContext, fi.callshift.app.domain.Decision>>()

    override fun onCreate() {
        super.onCreate()
        controller.attach(this, applicationContext)
        Log.i(TAG, "InCallService created")
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        controller.onCallAdded(call)
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                if (state == Call.STATE_DISCONNECTED) finishRejection(call)
                else evaluateIncoming(call)
            }
        }
        callbacks[call] = callback
        call.registerCallback(callback, Handler(Looper.getMainLooper()))
        evaluateIncoming(call)
        showInCallUi()
        Log.i(TAG, "call added: ${describe(call)}")
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        finishRejection(call)
        callbacks.remove(call)?.let { call.unregisterCallback(it) }
        jobs.remove(call)?.cancel()
        evaluated.remove(call)
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

    /** ROLE_DIALER alone does not imply ROLE_CALL_SCREENING on Android 10+.
     * Use the same engine when there is no separate screening role. Never touch
     * outgoing/answered calls, and never dispatch the same call on both paths.
     */
    private fun evaluateIncoming(call: Call) {
        if (call.state != Call.STATE_RINGING || call in evaluated) return
        val app = CallShiftApp.from(this)
        if (app.detector.isCallScreeningApp()) return
        evaluated.add(call)
        jobs[call] = scope.launch {
            try {
                val ctx = CallContextFactory(app).buildContext(call.details)
                val decision = app.ruleEngine.evaluate(ctx)
                if (call !in callbacks || call.state != Call.STATE_RINGING) return@launch
                when {
                    decision.shouldDisallow -> {
                        rejected[call] = ctx to decision
                        try {
                            call.reject(false, null)
                        } catch (error: Exception) {
                            rejected.remove(call)
                            throw error
                        }
                    }
                    decision.verdict == Verdict.SILENCE ->
                        (getSystemService(TELECOM_SERVICE) as android.telecom.TelecomManager).silenceRinger()
                }
                if (decision.shouldDisallow) ctx.e164?.let { app.settings.markRejected(it) }
                Log.i(TAG, "dialer decision=${decision.verdict} reason=${decision.reason}")
                withContext(Dispatchers.IO) {
                    app.eventStore.record(fi.callshift.app.forward.CallEvent(
                        ts = System.currentTimeMillis(), direction = ctx.direction.name,
                        numberE164 = ctx.e164, numberMasked = app.normalizer.mask(ctx.e164),
                        sim = ctx.phoneAccount?.id ?: "—", ruleId = decision.ruleId,
                        ruleName = decision.ruleName, strategy = "DIALER_RULES", target = decision.target,
                        result = if (decision.shouldDisallow) "REQUESTED" else "PASS",
                        errorCode = null, errorMessage = "${decision.verdict}: ${decision.reason}",
                        reason = decision.reason, screeningMs = decision.engineMs,
                        forwardMs = 0, totalMs = decision.engineMs,
                    ))
                }
                // Forwarding and SMS wait for the disconnected/removed callback.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(TAG, "dialer rule evaluation/action failed", error)
            }
        }
    }

    private fun finishRejection(call: Call) {
        val (ctx, decision) = rejected.remove(call) ?: return
        val action = decision.matchedAction ?: return
        val app = CallShiftApp.from(this)
        app.appScope.launch {
            runCatching { app.dispatcher.submit(ctx, decision, action) }
                .onFailure { Log.e(TAG, "dialer dispatch failed", it) }
            runCatching { app.smsReplier.maybeReply(ctx, decision, action.autoReplySms) }
                .onFailure { Log.e(TAG, "dialer SMS failed", it) }
        }
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
        callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }
        callbacks.clear()
        rejected.clear()
        jobs.clear()
        evaluated.clear()
        scope.cancel()
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
