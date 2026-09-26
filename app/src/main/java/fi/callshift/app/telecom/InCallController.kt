package fi.callshift.app.telecom

import android.content.Context
import android.media.AudioManager
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import android.view.Surface
import fi.callshift.app.domain.DtmfTransmitter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Контроллер вызовов профиля B (ТЗ FR-P3, п. 3.5).
 *
 * [InCallService] живёт и умирает вместе с вызовами, а UI (InCallActivity)
 * может пересоздаваться, поэтому состояние держим здесь — в синглтоне процесса.
 *
 * Требования, которые закрывает этот класс:
 *  1. «Никогда не возвращать null-binding» (иначе Telecom откатывается на
 *     системный dialer и показывает пользователю уведомление) — сервис всегда
 *     регистрирует вызовы здесь;
 *  2. Экран входящего И экран активного вызова (обязательное требование роли
 *     ROLE_DIALER) — [CallShiftInCallService] поднимает InCallActivity;
 *  3. DTMF-передача исходного номера цели (ТЗ п. 10.6, FR-4.6) — [pendingDtmf].
 */
class InCallController private constructor() {

    /** Снимок состояния вызова для UI — не отдаём наружу системный [Call]. */
    data class CallInfo(
        val id: String,
        val number: String?,
        val name: String?,
        val stateLabel: String,
        val stateCode: Int,
        val isRinging: Boolean,
        val isActive: Boolean,
        val isOnHold: Boolean,
        val isOutgoing: Boolean,
        val connectTimeMs: Long,
        val simLabel: String?,
        val isConference: Boolean,
        /** id PhoneAccountHandle (SIM), на которую/с которой идёт вызов. */
        val simId: String? = null,
        /** Оператор и телефон поддерживают видеозвонок (ViLTE). */
        val canVideo: Boolean = false,
    )

    fun interface Listener {
        fun onCallsChanged(calls: List<CallInfo>)
    }

    private val calls = CopyOnWriteArrayList<Call>()
    private val listeners = CopyOnWriteArrayList<Listener>()

    /** Очередь DTMF: целевой номер → исходный номер звонящего. */
    @Volatile
    var pendingDtmf: PendingDtmf? = null

    data class PendingDtmf(
        val targetNumber: String,
        val originalNumber: String,
        val ruleName: String?,
        val enqueuedAt: Long = System.currentTimeMillis(),
    )

    private var service: InCallService? = null
    private var context: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ----------------------------------------------------------------- lifecycle

    fun attach(service: InCallService, ctx: Context) {
        this.service = service
        this.context = ctx.applicationContext
    }

    fun detach(service: InCallService) {
        if (this.service === service) {
            this.service = null
        }
    }

    fun onCallAdded(call: Call) {
        if (!calls.contains(call)) calls.add(call)
        registerCallback(call)
        // Исходящий дозвон на цель: если для него заказана DTMF-передача
        // исходного номера — отправим её, когда вызов станет ACTIVE.
        maybeStartDtmf(call)
        notifyListeners()
    }

    fun onCallRemoved(call: Call) {
        calls.remove(call)
        notifyListeners()
    }

    private fun registerCallback(call: Call) {
        runCatching {
            call.registerCallback(object : Call.Callback() {
                override fun onStateChanged(c: Call, state: Int) {
                    maybeStartDtmf(c)
                    notifyListeners()
                }

                override fun onConferenceableCallsChanged(c: Call, candidates: MutableList<Call>) = notifyListeners()
                override fun onDetailsChanged(c: Call, details: Call.Details?) = notifyListeners()
            })
        }.onFailure { Log.w(TAG, "registerCallback failed", it) }
    }

    // ----------------------------------------------------------------- действия

    fun answer(callId: String, videoState: Int = VideoProfile.STATE_AUDIO_ONLY) {
        call(callId)?.let { runCatching { it.answer(videoState) } }
    }

    fun reject(callId: String, rejectWithMessage: Boolean = false) {
        call(callId)?.let { runCatching { it.reject(rejectWithMessage, null) } }
    }

    fun disconnect(callId: String) {
        call(callId)?.let { runCatching { it.disconnect() } }
    }

    fun hold(callId: String) {
        call(callId)?.let { runCatching { it.hold() } }
    }

    fun unhold(callId: String) {
        call(callId)?.let { runCatching { it.unhold() } }
    }

    fun playDtmf(callId: String, digit: Char) {
        call(callId)?.let { runCatching { it.playDtmfTone(digit) } }
    }

    fun conference(callId: String, otherId: String) {
        val a = call(callId) ?: return
        val b = call(otherId) ?: return
        runCatching { a.conference(b) }
    }

    /** Маршрут аудио: динамик / наушник (доступно в профиле B). */
    fun setSpeakerphone(callId: String, enabled: Boolean) {
        runCatching {
            val route = if (enabled) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE
            service?.setAudioRoute(route)
        }.onFailure {
            // Fallback для устройств, где setAudioRoute недоступен.
            runCatching {
                val am = context?.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
                @Suppress("DEPRECATION")
                am?.isSpeakerphoneOn = enabled
            }
        }
    }

    /** Доступные маршруты звука (битовая маска CallAudioState.ROUTE_*) и текущий. */
    @Suppress("DEPRECATION")
    fun audioRoutes(): Pair<Int, Int> {
        val st = runCatching { service?.callAudioState }.getOrNull()
        return (st?.supportedRouteMask ?: 0) to (st?.route ?: 0)
    }

    fun setAudioRoute(route: Int) {
        runCatching { service?.setAudioRoute(route) }
    }

    fun setMute(muted: Boolean) {
        runCatching {
            service?.setMuted(muted)
        }.onFailure {
            runCatching {
                val am = context?.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
                am?.isMicrophoneMute = muted
            }
        }
    }

    // ----------------------------------------------------------------- состояние

    fun snapshot(): List<CallInfo> = calls.map { it.toInfo() }

    fun hasCalls(): Boolean = calls.isNotEmpty()

    fun primary(): CallInfo? = snapshot().firstOrNull { it.isRinging } ?: snapshot().firstOrNull()

    fun addListener(l: Listener) {
        listeners.addIfAbsent(l)
        l.onCallsChanged(snapshot())
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        val snap = snapshot()
        listeners.forEach { runCatching { it.onCallsChanged(snap) } }
    }

    private fun call(id: String): Call? = calls.firstOrNull { id(it) == id }

    private fun Call.toInfo(): CallInfo {
        val details = runCatching { this.details }.getOrNull()
        val state = runCatching { this.state }.getOrNull()
        val number = runCatching { details?.handle?.schemeSpecificPart }.getOrNull()
        val name = runCatching { details?.callerDisplayName?.toString() }.getOrNull()
        val sim = runCatching { details?.extras?.getString("android.telecom.extra.PHONE_ACCOUNT_LABEL") }.getOrNull()
        val hasConf = runCatching { details?.hasProperty(Call.Details.PROPERTY_CONFERENCE) }.getOrDefault(false)
        return CallInfo(
            id = id(this),
            number = number,
            name = name,
            stateCode = state ?: 0,
            stateLabel = stateLabel(state),
            isRinging = state == Call.STATE_RINGING,
            isActive = state == Call.STATE_ACTIVE,
            isOnHold = state == Call.STATE_HOLDING,
            isOutgoing = state == Call.STATE_DIALING || state == Call.STATE_CONNECTING || state == Call.STATE_SELECT_PHONE_ACCOUNT,
            connectTimeMs = runCatching { details?.connectTimeMillis }.getOrNull() ?: 0L,
            simLabel = sim,
            isConference = hasConf ?: false,
            simId = runCatching { details?.accountHandle?.id }.getOrNull(),
            canVideo = runCatching {
                details?.can(Call.Details.CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL) == true &&
                    details.can(Call.Details.CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL)
            }.getOrDefault(false),
        )
    }

    private fun id(call: Call): String = Integer.toHexString(System.identityHashCode(call))

    private fun stateLabel(state: Int?): String = when (state) {
        Call.STATE_NEW -> "новый"
        Call.STATE_DIALING -> "набор"
        Call.STATE_RINGING -> "входящий"
        Call.STATE_HOLDING -> "удержание"
        Call.STATE_ACTIVE -> "разговор"
        Call.STATE_DISCONNECTED -> "завершён"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "выбор SIM"
        Call.STATE_CONNECTING -> "соединение"
        Call.STATE_DISCONNECTING -> "завершается"
        Call.STATE_PULLING_CALL -> "pull"
        Call.STATE_AUDIO_PROCESSING -> "audio processing"
        Call.STATE_SIMULATED_RINGING -> "имитация звонка"
        else -> "неизвестно"
    }

    // ----------------------------------------------------------------- DTMF (ТЗ п. 10.6)

    /**
     * Если для исходящего вызова на цель заказана передача исходного номера —
     * дождаться состояния ACTIVE и отправить тоны с паузой [DtmfTransmitter.TONE_GAP_MS].
     */
    private fun maybeStartDtmf(call: Call) {
        val pending = pendingDtmf ?: return
        val state = runCatching { call.state }.getOrNull()
        if (state != Call.STATE_ACTIVE) return
        val number = runCatching { call.details?.handle?.schemeSpecificPart }.getOrNull() ?: return
        if (!sameNumber(number, pending.targetNumber)) return

        pendingDtmf = null
        val sequence = DtmfTransmitter.buildSequence(pending.originalNumber)
            ?.take(DtmfTransmitter.MAX_SEQUENCE_LENGTH) ?: return
        Log.i(TAG, "DTMF transfer to $number: $sequence (rule=${pending.ruleName})")
        scope.launch {
            DtmfTransmitter.toChars(sequence).forEach { digit ->
                runCatching { call.playDtmfTone(digit) }
                delay(DtmfTransmitter.TONE_GAP_MS)
            }
        }
    }

    private fun sameNumber(a: String, b: String): Boolean {
        val da = a.filter { it.isDigit() }
        val db = b.filter { it.isDigit() }
        if (da.isEmpty() || db.isEmpty()) return false
        if (da == db) return true
        val tailA = da.takeLast(9)
        val tailB = db.takeLast(9)
        return tailA.length >= 7 && tailA == tailB
    }

    companion object {
        private const val TAG = "CallShift"

        @Volatile
        private var instance: InCallController? = null

        fun get(): InCallController = instance ?: synchronized(this) {
            instance ?: InCallController().also { instance = it }
        }

        /** Для тестов: сброс синглтона. */
        fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }
}
