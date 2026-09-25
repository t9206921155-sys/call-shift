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

    override fun onScreenCall(callDetails: Call.Details) {
        val started = System.nanoTime()
        try {
            val ctx = CallContextFactory(app).buildContext(callDetails)

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
            if (decision.shouldDisallow) ctx.e164?.let { runCatching { app.settings.markRejected(it) } }

            // Пост-обработка вне screening-пути (дозвон/MMI/уведомление).
            val action = decision.matchedAction ?: Action(
                verdict = VerdictSpec.PASS,
                strategy = StrategySpec.NONE,
                target = decision.target,
            )
            // Telecom may unbind/destroy this service immediately after the response.
            // Replies must not be cancelled together with the screening service.
            app.appScope.launch {
                // Reply must not wait for a forwarding/network strategy.
                runCatching { app.smsReplier.maybeReply(ctx, decision, action.autoReplySms) }
                    .onFailure { Log.e(TAG, "auto-reply failed", it) }
            }
            app.appScope.launch {
                runCatching { app.dispatcher.submit(ctx, decision, action) }
                    .onFailure { Log.e(TAG, "dispatch failed", it) }
                // Без переадресации диспетчер ничего не пишет — фиксируем сам факт перехвата,
                // чтобы в «Журнале» было видно: звонок дошёл до приложения и что с ним сделано.
                if (decision.strategy == fi.callshift.app.domain.StrategyId.PASS) {
                    runCatching { recordScreened(ctx, decision, started) }
                        .onFailure { Log.e(TAG, "journal write failed", it) }
                }
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
            Verdict.PASS -> "звонок прошёл как обычно"
            Verdict.DISALLOW_REJECT -> "запрошен сброс звонка"
            Verdict.DISALLOW_AS_MISSED -> "запрошен сброс звонка (в пропущенные)"
            Verdict.SILENCE -> "звонок без звука"
        }
        val why = decision.ruleName?.let { "правило «$it»" } ?: when (decision.reason) {
            "default_policy" -> "ни одно правило не подошло"
            "master_switch_off" -> "главный переключатель выключен"
            "no_screening_role" -> "нет роли перехвата"
            fi.callshift.app.domain.RuleEngine.REASON_WHITELIST -> "номер в белом списке"
            fi.callshift.app.domain.RuleEngine.REASON_REPEAT_CALL -> "повторный звонок — пропущен как срочный"
            else -> decision.reason
        } + if (decision.skipped.isNotEmpty()) {
            "\nПочему не сработали правила:\n" + decision.skipped.joinToString("\n") { "• $it" }
        } else ""
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

        // Do not report success or send an SMS when submitting the response throws.
        respondToCall(details, builder.build())
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

    companion object {
        private const val TAG = "CallShift"

        /**
         * Бюджет 2800 мс: системный таймаут screening ≈ 5000 мс, оставляем запас
         * на построение ответа и IPC (ТЗ п. 10.2, NFR-1).
         */
        const val SCREENING_BUDGET_MS = 2800L
    }
}
