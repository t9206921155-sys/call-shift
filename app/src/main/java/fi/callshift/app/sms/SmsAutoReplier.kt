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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private val replyLocks = List(32) { Mutex() }

    fun hasPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun maybeReply(ctx: CallContext, decision: Decision, template: String?) {
        val key = "${decision.matchedAction?.replyChannel ?: "SMS"}:${ctx.e164.orEmpty()}"
        replyLocks[(key.hashCode() and Int.MAX_VALUE) % replyLocks.size].withLock {
            replyLocked(ctx, decision, template)
        }
    }

    private suspend fun replyLocked(ctx: CallContext, decision: Decision, template: String?) {
        if (template.isNullOrBlank()) return
        val channel = decision.matchedAction?.replyChannel ?: "SMS"
        val key = if (channel == "SMS") ctx.e164.orEmpty() else "$channel:${ctx.e164.orEmpty()}"
        val now = System.currentTimeMillis()
        val last = if (key.isEmpty()) null else prefs.getLong(key, -1L).takeIf { it > 0 }

        when (val r = policy.decide(decision.verdict, template, ctx.e164, last, now)) {
            is SmsAutoReplyPolicy.Result.Skip -> {
                // Пустой текст/не отбой — молча; остальное фиксируем в журнале.
                if (r.reason != "no_text" && r.reason != "verdict_not_reject") {
                    record(ctx, decision, "BLOCKED", "reply_${r.reason}",
                        if (channel == "SMS") skipMessage(r.reason)
                        else "Ответ $channel не подготовлен: " + when (r.reason) {
                            "cooldown" -> "этому номеру уже предлагался ответ за последние 30 минут"
                            "unknown_number" -> "номер скрыт"
                            "short_number" -> "короткий/сервисный номер"
                            else -> r.reason
                        })
                }
            }
            is SmsAutoReplyPolicy.Result.Send -> {
                if (channel != "SMS") {
                    try {
                        fi.callshift.app.messaging.MessengerReply.offer(appContext, r.number, r.text, channel)
                        prefs.edit().putLong(key, now).apply()
                        record(ctx, decision, "PENDING_USER", null,
                            "$channel: подготовлен ответ, требуется выбор/проверка получателя и ручная отправка. Сообщение не отправлено.")
                    } catch (error: Exception) {
                        record(ctx, decision, "FAILED", "messenger_draft_failed",
                            "$channel: ${error.message ?: "Не удалось подготовить ответ"}. SMS не отправлялась.")
                    }
                    return
                }
                if (!hasPermission()) {
                    record(ctx, decision, "FAILED", "sms_no_permission",
                        "Нет разрешения SEND_SMS — выдайте его на главном экране")
                    return
                }
                val subId = resolveSubscriptionId(ctx.phoneAccount?.id)
                if (subId == null) {
                    record(ctx, decision, "FAILED", "sms_sim_unknown",
                        "SIM входящего вызова не определена для SMS. Отправка через другую SIM запрещена.")
                    return
                }
                // Reserve before asynchronous sending. Unknown/partial outcomes must
                // not cause an automatic duplicate or another paid multipart SMS.
                prefs.edit().putLong(key, now).apply()
                try {
                    TrackedSmsSender.send(appContext, smsManager(subId), r.number, r.text,
                        event(ctx, decision, "SUBMITTED", null, "Ожидаем подтверждение отправки"))
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "SMS send failed", error)
                    record(ctx, decision, "FAILED", "sms_error", "Ошибка отправки SMS: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * Быстрый SMS-ответ с экрана входящего вызова («Отклонить и отправить SMS»).
     * Без анти-спама (пользователь нажал сам). Возвращает текст ошибки или null при успехе.
     */
    fun sendQuickReply(number: String?, text: String, accountId: String?): String? {
        val normalized = normalizer.normalize(number).e164
        if (normalized == null || !fi.callshift.app.domain.ReplyChannel.isPhoneAddress(normalized)) return "Номер скрыт или некорректен"
        if (text.isBlank()) return "Пустой текст"
        if (!hasPermission()) return "Нет разрешения на отправку SMS"
        val subId = resolveSubscriptionId(accountId) ?: return "SIM звонка не определена — отправка через другую карту запрещена"
        val app = fi.callshift.app.CallShiftApp.from(appContext)
        app.appScope.launch {
            val ctx = CallContext(e164 = normalized, phoneAccount = accountId?.let { fi.callshift.app.domain.PhoneAccountRef(it, "") })
            val decision = Decision(fi.callshift.app.domain.Verdict.DISALLOW_REJECT, reason = "manual_sms_reply")
            try {
                TrackedSmsSender.send(appContext, smsManager(subId), normalized, text,
                    event(ctx, decision, "SUBMITTED", null, "Ручной SMS-ответ"))
            } catch (error: Exception) {
                record(ctx, decision, "FAILED", "sms_error", error.javaClass.simpleName)
            }
        }
        return null
    }

    /** Local preflight; does not send anything or verify operator delivery. */
    fun canUseAccount(accountId: String?): Boolean = resolveSubscriptionId(accountId) != null

    /**
     * PhoneAccountHandle.id → subscriptionId. На разных прошивках id аккаунта —
     * это subId, ICCID или «ICCID + F». При null отправку запрещаем.
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
                val resolved = fi.callshift.app.CallShiftApp.from(appContext).telecom.canonicalAccountId(accountId)
                val handle = telecom.callCapablePhoneAccounts.firstOrNull { it.id == resolved }
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

    private fun smsManager(subId: Int): SmsManager {
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            appContext.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        else SmsManager.getSmsManagerForSubscriptionId(subId)
    }

    private fun skipMessage(reason: String) = when (reason) {
        "cooldown" -> "SMS не отправлено: для этого номера уже была попытка за последние 30 мин"
        "unknown_number" -> "SMS не отправлено: номер скрыт"
        "short_number" -> "SMS не отправлено: короткий/сервисный номер"
        else -> "SMS не отправлено: $reason"
    }

    private fun event(ctx: CallContext, d: Decision, result: String, code: String?, msg: String) = CallEvent(
        ts = System.currentTimeMillis(), direction = ctx.direction.name,
        numberE164 = ctx.e164, numberMasked = normalizer.mask(ctx.e164 ?: ctx.rawHandle),
        sim = ctx.phoneAccount?.label?.takeIf { it.isNotBlank() } ?: ctx.phoneAccount?.id ?: "—",
        ruleId = d.ruleId, ruleName = d.ruleName,
        strategy = if ((d.matchedAction?.replyChannel ?: "SMS") == "SMS") "SMS_REPLY" else "MESSENGER_DRAFT",
        target = ctx.e164, result = result, errorCode = code, errorMessage = msg,
        reason = d.reason, screeningMs = d.engineMs, forwardMs = 0, totalMs = d.engineMs,
    )

    private suspend fun record(ctx: CallContext, d: Decision, result: String, code: String?, msg: String) {
        runCatching { recorder.record(event(ctx, d, result, code, msg)) }
            .onFailure { Log.e(TAG, "Reply journal write failed", it) }
    }

    companion object {
        private const val TAG = "CallShift"
        private const val PREFS = "sms_autoreply_last"
    }
}
