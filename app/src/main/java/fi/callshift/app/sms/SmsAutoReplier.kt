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
                val subId = resolveSubscriptionId(ctx.phoneAccount?.id)
                val result = runCatching { send(r.number, r.text, subId) }
                result.onSuccess {
                    prefs.edit().putLong(key, now).apply()
                    val via = if (subId != null) {
                        "с SIM ${ctx.phoneAccount?.label?.ifBlank { null } ?: "#$subId"}"
                    } else {
                        "с SIM по умолчанию для SMS (SIM вызова не определена)"
                    }
                    record(ctx, decision, "OK", null, "SMS-автоответ отправлен $via: «${r.text}»")
                }.onFailure { t ->
                    Log.e(TAG, "SMS send failed", t)
                    record(ctx, decision, "FAILED", "sms_error", t.toString())
                }
            }
        }
    }

    /**
     * Быстрый SMS-ответ с экрана входящего вызова («Отклонить и отправить SMS»).
     * Без анти-спама (пользователь нажал сам). Возвращает текст ошибки или null при успехе.
     */
    fun sendQuickReply(number: String?, text: String, accountId: String?): String? {
        if (number.isNullOrBlank()) return "Номер скрыт — SMS отправить некуда"
        if (!hasPermission()) return "Нет разрешения на отправку SMS"
        return runCatching { send(number, text, resolveSubscriptionId(accountId)) }
            .exceptionOrNull()?.let { "Не удалось отправить SMS: ${it.message ?: it.javaClass.simpleName}" }
    }

    /**
     * PhoneAccountHandle.id → subscriptionId. На разных прошивках id аккаунта —
     * это subId, ICCID или «ICCID + F». Пробуем по порядку; null → SIM по умолчанию.
     */
    private fun resolveSubscriptionId(accountId: String?): Int? {
        if (accountId.isNullOrBlank()) return null
        if (appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return null

        // 1) API 30+: официальный маппинг handle → subId.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val telecom = appContext.getSystemService(android.telecom.TelecomManager::class.java)
                val tm = appContext.getSystemService(android.telephony.TelephonyManager::class.java)
                val handle = telecom.callCapablePhoneAccounts.firstOrNull { handleId(it) == accountId }
                if (handle != null) {
                    val sub = tm.getSubscriptionId(handle)
                    if (sub != android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID) return sub
                }
            }
        }
        // 2) Сопоставление по списку активных подписок.
        return runCatching {
            val sm = appContext.getSystemService(android.telephony.SubscriptionManager::class.java)
            val subs = sm.activeSubscriptionInfoList.orEmpty()
            val bare = accountId.trimEnd('F', 'f')
            subs.firstOrNull { it.subscriptionId.toString() == accountId }?.subscriptionId
                ?: subs.firstOrNull {
                    @Suppress("DEPRECATION")
                    val icc = runCatching { it.iccId }.getOrNull().orEmpty()
                    icc.isNotEmpty() && (icc == accountId || icc.trimEnd('F', 'f') == bare)
                }?.subscriptionId
        }.getOrNull()
    }

    private fun handleId(handle: android.telecom.PhoneAccountHandle): String =
        runCatching { handle.id }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: handle.toString().substringAfterLast('[', "").substringBefore(']', "")

    private fun send(number: String, text: String, subId: Int?) {
        @Suppress("DEPRECATION")
        val sms: SmsManager = when {
            subId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                appContext.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            subId != null -> SmsManager.getSmsManagerForSubscriptionId(subId)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                appContext.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            else -> SmsManager.getDefault()
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
