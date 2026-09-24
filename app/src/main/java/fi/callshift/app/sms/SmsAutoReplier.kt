package fi.callshift.app.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import fi.callshift.app.domain.CallContext
import fi.callshift.app.domain.Decision
import fi.callshift.app.domain.PhoneNumberNormalizer
import fi.callshift.app.domain.SmsAutoReplyPolicy
import fi.callshift.app.forward.CallEvent
import fi.callshift.app.forward.EventRecorder

/**
 * Отправка SMS-автоответа звонящему после отбоя вызова.
 * Вызывается ПОСЛЕ respondToCall — не влияет на бюджет screening.
 * Результат (включая пропуски по анти-спаму) пишется в журнал событий.
 */
class SmsAutoReplier(
    context: Context,
    private val recorder: EventRecorder,
    private val normalizer: PhoneNumberNormalizer,
    private val policy: SmsAutoReplyPolicy = SmsAutoReplyPolicy(),
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun maybeReply(ctx: CallContext, decision: Decision, template: String?) {
        if (template.isNullOrBlank()) return
        val key = ctx.e164.orEmpty()
        val now = System.currentTimeMillis()
        val last = if (key.isEmpty()) null else prefs.getLong(key, -1L).takeIf { it > 0 }

        when (val r = policy.decide(decision.verdict, template, ctx.e164, last, now)) {
            is SmsAutoReplyPolicy.Result.Skip -> {
                // Пустой текст/не отбой — молча; остальное фиксируем в журнале.
                if (r.reason != "no_text" && r.reason != "verdict_not_reject") {
                    record(ctx, decision, "BLOCKED", "sms_${r.reason}", skipMessage(r.reason))
                }
            }
            is SmsAutoReplyPolicy.Result.Send -> {
                if (!hasPermission()) {
                    record(ctx, decision, "FAILED", "sms_no_permission",
                        "Нет разрешения SEND_SMS — выдайте его на главном экране")
                    return
                }
                val result = runCatching { send(r.number, r.text) }
                result.onSuccess {
                    prefs.edit().putLong(key, now).apply()
                    record(ctx, decision, "OK", null, "SMS-автоответ отправлен: «${r.text}»")
                }.onFailure { t ->
                    Log.e(TAG, "SMS send failed", t)
                    record(ctx, decision, "FAILED", "sms_error", t.toString())
                }
            }
        }
    }

    private fun send(number: String, text: String) {
        @Suppress("DEPRECATION")
        val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
        } else {
            SmsManager.getDefault()
        }
        val parts = sms.divideMessage(text)
        if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, null, null)
        else sms.sendTextMessage(number, null, text, null, null)
    }

    private fun skipMessage(reason: String) = when (reason) {
        "cooldown" -> "SMS не отправлено: этому номеру уже отвечали за последние 30 мин"
        "unknown_number" -> "SMS не отправлено: номер скрыт"
        "short_number" -> "SMS не отправлено: короткий/сервисный номер"
        else -> "SMS не отправлено: $reason"
    }

    private suspend fun record(ctx: CallContext, d: Decision, result: String, code: String?, msg: String) {
        runCatching {
            recorder.record(
                CallEvent(
                    ts = System.currentTimeMillis(),
                    direction = ctx.direction.name,
                    numberE164 = ctx.e164,
                    numberMasked = normalizer.mask(ctx.e164 ?: ctx.rawHandle),
                    sim = ctx.phoneAccount?.label ?: ctx.phoneAccount?.id ?: "—",
                    ruleId = d.ruleId,
                    ruleName = d.ruleName,
                    strategy = "SMS_REPLY",
                    target = ctx.e164,
                    result = result,
                    errorCode = code,
                    errorMessage = msg,
                    reason = d.reason,
                    screeningMs = d.engineMs,
                    forwardMs = 0,
                    totalMs = d.engineMs,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "CallShift"
        private const val PREFS = "sms_autoreply_last"
    }
}
