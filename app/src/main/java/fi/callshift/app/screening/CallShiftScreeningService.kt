package fi.callshift.app.screening

import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.Action
import fi.callshift.app.domain.CallContext
import fi.callshift.app.domain.Decision
import fi.callshift.app.domain.Direction
import fi.callshift.app.domain.PhoneAccountRef
import fi.callshift.app.domain.Signal
import fi.callshift.app.domain.StrategySpec
import fi.callshift.app.domain.Verdict
import fi.callshift.app.domain.VerdictSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Точка перехвата входящих вызовов (ТЗ п. 4.2).
 *
 * Контракт реализации:
 *  1. [respondToCall] ОБЯЗАН быть вызван для КАЖДОГО полученного [Call.Details] —
 *     иначе Telecom держит вызов подвешенным до системного таймаута (~5 с).
 *     Сигнатура платформы: `respondToCall(Call.Details, CallResponse)`.
 *  2. Бюджет на решение — [SCREENING_BUDGET_MS] = 2800 мс (ТЗ п. 10.2, FR-2.1).
 *     При превышении — отвечаем «пропустить» (fail-open, NFR-2).
 *  3. Исполнение перенаправления (дозвон, MMI, webhook) — ПОСЛЕ ответа системе,
 *     в отдельной корутине (ТЗ п. 6.6, шаги 4–5).
 *  4. Любое исключение → PASS (FR-2.8).
 *
 * ОГРАНИЧЕНИЕ ПЛАТФОРМЫ (ТЗ Приложение E, P-2): для приложения, не являющегося
 * default dialer, `Call.Details.getPhoneAccountHandle()` закрыт. Поэтому SIM
 * определяется best-effort: reflection → extras → «неизвестно».
 */
class CallShiftScreeningService : CallScreeningService() {

    private val app: CallShiftApp by lazy { CallShiftApp.from(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onScreenCall(callDetails: Call.Details) {
        val started = System.nanoTime()
        try {
            val ctx = buildContext(callDetails)

            // Экстренные номера — абсолютный приоритет (FR-2.6, E-04).
            if (ctx.isEmergency) {
                respondPass(callDetails, "emergency")
                return
            }

            val decision = runBlocking {
                withTimeoutOrNull(SCREENING_BUDGET_MS) { app.ruleEngine.evaluate(ctx) }
                    ?: Decision.pass("screening_budget_exceeded")
            }

            respond(callDetails, decision)

            // Пост-обработка вне screening-пути (дозвон/MMI/уведомление).
            val action = decision.matchedAction ?: Action(
                verdict = VerdictSpec.PASS,
                strategy = StrategySpec.NONE,
                target = decision.target,
            )
            serviceScope.launch {
                runCatching { app.dispatcher.submit(ctx, decision, action) }
                    .onFailure { Log.e(TAG, "dispatch failed", it) }
                // Без переадресации диспетчер ничего не пишет — фиксируем сам факт перехвата,
                // чтобы в «Журнале» было видно: звонок дошёл до приложения и что с ним сделано.
                if (decision.strategy == fi.callshift.app.domain.StrategyId.PASS) {
                    runCatching { recordScreened(ctx, decision, started) }
                        .onFailure { Log.e(TAG, "journal write failed", it) }
                }
                // SMS-автоответ после отбоя (если задан в правиле).
                runCatching { app.smsReplier.maybeReply(ctx, decision, action.autoReplySms) }
                    .onFailure { Log.e(TAG, "sms auto-reply failed", it) }
            }

            Log.i(
                TAG,
                "screened ${ctx.displayNumber} → ${decision.verdict}/${decision.strategy} " +
                    "reason=${decision.reason} in ${(System.nanoTime() - started) / 1_000_000}ms",
            )
        } catch (t: Throwable) {
            // FR-2.8: при любом сбое вызов должен пройти.
            runCatching { respondPass(callDetails, "screening_error:${t.javaClass.simpleName}") }
            Log.e(TAG, "onScreenCall error — fail-open", t)
        }
    }

    private suspend fun recordScreened(ctx: CallContext, decision: Decision, startedNs: Long) {
        val verdictText = when (decision.verdict) {
            Verdict.PASS -> "звонок пропущен"
            Verdict.DISALLOW_REJECT -> "звонок сброшен"
            Verdict.DISALLOW_AS_MISSED -> "звонок сброшен (в пропущенные)"
            Verdict.SILENCE -> "звонок без звука"
        }
        val why = decision.ruleName?.let { "правило «$it»" } ?: when (decision.reason) {
            "default_policy" -> "ни одно правило не подошло"
            "master_switch_off" -> "главный переключатель выключен"
            "no_screening_role" -> "нет роли перехвата"
            else -> decision.reason
        }
        val totalMs = (System.nanoTime() - startedNs) / 1_000_000L
        app.eventStore.record(
            fi.callshift.app.forward.CallEvent(
                ts = System.currentTimeMillis(),
                direction = ctx.direction.name,
                numberE164 = ctx.e164,
                numberMasked = app.normalizer.mask(ctx.e164 ?: ctx.rawHandle),
                sim = ctx.phoneAccount?.label ?: ctx.phoneAccount?.id ?: "—",
                ruleId = decision.ruleId,
                ruleName = decision.ruleName,
                strategy = "SCREENED",
                target = null,
                result = if (decision.verdict == Verdict.PASS) "PASS" else "OK",
                errorCode = null,
                errorMessage = "$verdictText: $why",
                reason = decision.reason,
                screeningMs = decision.engineMs,
                forwardMs = 0,
                totalMs = totalMs,
            ),
        )
    }

    /** Формируем доменный контекст из системного Call.Details. */
    private fun buildContext(details: Call.Details): CallContext {
        val raw = runCatching { details.handle?.schemeSpecificPart }.getOrNull()
        val normalized = app.normalizer.normalize(raw)
        val isEmergency = normalized.isEmergency ||
            app.normalizer.looksLikeLocalEmergency(raw.orEmpty()) ||
            hasHiddenProperty(details, "PROPERTY_EMERGENCY_CALLBACK")

        return CallContext(
            rawHandle = raw,
            e164 = normalized.e164,
            national = normalized.national,
            // CallScreeningService вызывается только для входящих вызовов.
            direction = Direction.INCOMING,
            phoneAccount = resolvePhoneAccount(details),
            isEmergency = isEmergency,
            isSelfManaged = runCatching {
                details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED)
            }.getOrDefault(false),
            signals = currentSignals(),
        )
    }

    /**
     * Best-effort определение SIM (Приложение E, P-2/P-3):
     *  1) reflection к скрытому getPhoneAccountHandle();
     *  2) ключи phoneAccount в extras;
     *  3) null → «неизвестно» → правило трактуется как «любая SIM».
     */
    private fun resolvePhoneAccount(details: Call.Details): PhoneAccountRef? {
        // 0) Публичный API: Call.Details.getAccountHandle().
        runCatching {
            val id = details.accountHandle?.id
            if (!id.isNullOrBlank()) {
                return PhoneAccountRef(id = id, label = app.telecom.phoneAccounts()[id] ?: id)
            }
        }
        runCatching {
            val method = details.javaClass.getMethod("getPhoneAccountHandle")
            val handle = method.invoke(details)
            if (handle != null) {
                val id = (handle as? android.telecom.PhoneAccountHandle)?.id
                if (!id.isNullOrBlank()) {
                    return PhoneAccountRef(id = id, label = app.telecom.phoneAccounts()[id] ?: id)
                }
            }
        }
        runCatching {
            val extras = details.extras ?: return@runCatching
            for (key in extras.keySet()) {
                if (key.contains("phone_account", ignoreCase = true)) {
                    val value = extras.get(key)?.toString() ?: continue
                    val id = value.substringAfterLast('[').substringBefore(']').ifBlank { value }
                    return PhoneAccountRef(id = id, label = id)
                }
            }
        }
        return null
    }

    private fun hasHiddenProperty(details: Call.Details, constantName: String): Boolean {
        val value = runCatching {
            Call.Details::class.java.getField(constantName).getInt(null)
        }.getOrNull() ?: return false
        return runCatching { details.hasProperty(value) }.getOrDefault(false)
    }

    /** Дешёвые сигналы окружения для условий правил (ТЗ п. 9.4). */
    private fun currentSignals(): Map<Signal, String> {
        val signals = mutableMapOf<Signal, String>()
        runCatching {
            val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level > 0) signals[Signal.BATTERY] = level.toString()
        }
        runCatching {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            signals[Signal.NETWORK] = when {
                caps == null -> "NONE"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                else -> "OTHER"
            }
        }
        return signals
    }

    /** Маппинг Decision → CallResponse с учётом профиля полномочий (ТЗ п. 3.5). */
    private fun respond(details: Call.Details, decision: Decision) {
        val profile = app.profile
        val builder = CallResponse.Builder()

        when (decision.verdict) {
            Verdict.PASS -> builder.setDisallowCall(false)

            Verdict.DISALLOW_REJECT -> {
                builder.setDisallowCall(true)
                // setRejectCall допустим только вместе с disallow (ТЗ п. 3.5)
                builder.setRejectCall(true)
            }

            Verdict.DISALLOW_AS_MISSED -> {
                builder.setDisallowCall(true)
                builder.setRejectCall(true)
                // setRejectedAsMissed отсутствует в публичном SDK — вызываем
                // reflection'ом и деградируем мягко (Приложение E, P-4).
                val applied = invokeIfAvailable(builder, "setRejectedAsMissed", true)
                if (!applied) Log.w(TAG, "setRejectedAsMissed недоступен — помечаем как обычный отбой")
            }

            Verdict.SILENCE -> {
                builder.setDisallowCall(false)
                val canSilence = profile.canSilence || hasAnswerPermission()
                val applied = if (canSilence) invokeIfAvailable(builder, "setSilenceCall", true) else false
                if (!applied) {
                    // Деградация: не можем заглушить — пропускаем вызов как есть.
                    Log.w(TAG, "silence недоступен в профиле $profile — вызов пропущен штатно")
                }
            }
        }

        // setSkipNotification — только для default dialer / carrier app (ТЗ п. 3.5).
        if (decision.shouldDisallow && profile.canSkipNotification) {
            invokeIfAvailable(builder, "setSkipNotification", true)
        }
        // setSkipCallLog игнорируется системой для сторонних приложений — не используем.

        runCatching { respondToCall(details, builder.build()) }
            .onFailure { Log.e(TAG, "respondToCall failed", it) }
    }

    private fun respondPass(details: Call.Details, reason: String) {
        runCatching {
            respondToCall(details, CallResponse.Builder().setDisallowCall(false).build())
        }.onFailure { Log.e(TAG, "respondPass failed ($reason)", it) }
    }

    /** Вызов метода Builder'а, которого может не быть в данной версии платформы. */
    private fun invokeIfAvailable(builder: CallResponse.Builder, method: String, value: Boolean): Boolean =
        runCatching {
            builder.javaClass.getMethod(method, Boolean::class.javaPrimitiveType).invoke(builder, value)
            true
        }.getOrDefault(false)

    private fun hasAnswerPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        runCatching { serviceScope.cancel() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallShift"

        /**
         * Бюджет 2800 мс: системный таймаут screening ≈ 5000 мс, оставляем запас
         * на построение ответа и IPC (ТЗ п. 10.2, NFR-1).
         */
        const val SCREENING_BUDGET_MS = 2800L
    }
}
