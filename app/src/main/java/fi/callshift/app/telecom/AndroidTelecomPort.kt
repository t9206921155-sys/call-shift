package fi.callshift.app.telecom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import fi.callshift.app.domain.ForwardResult
import fi.callshift.app.forward.TelecomPort
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Реализация [TelecomPort] поверх системных API (ТЗ п. 6.2, 10.4).
 *
 * Все вызовы к системе изолированы здесь — домен и стратегии не знают
 * про android.telecom, что даёт тестируемость и защиту от «расползания» API.
 */
class AndroidTelecomPort(
    private val context: Context,
    private val ussdTimeoutMs: Long = USSD_TIMEOUT_MS,
) : TelecomPort {

    private val telecom: TelecomManager
        get() = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    /** Последний ответ USSD — для экрана «Статус сетевой переадресации» (FR-3.4/FR-3.5). */
    @Volatile
    var lastUssdResponse: String? = null
        private set

    // ------------------------------------------------------------------ SIM

    /**
     * Список SIM-аккаунтов.
     *
     * ОГРАНИЧЕНИЕ ПЛАТФОРМЫ (Приложение E, P-3): поле `PhoneAccountHandle.id`
     * закрыто (@hide) в публичном SDK, поэтому читаем его reflection'ом, а при
     * неудаче — берём стабильное представление из toString() ("[pkg] <id>").
     */
    override fun phoneAccounts(): Map<String, String> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyMap()
        return runCatching {
            telecom.callCapablePhoneAccounts.associate { handle ->
                handleId(handle) to (runCatching { telecom.getPhoneAccount(handle)?.label?.toString() }.getOrNull()
                    ?: handleId(handle))
            }
        }.getOrDefault(emptyMap())
    }

    /**
     * Какая SIM сейчас звонит — запасной способ, когда система не передала SIM
     * в CallScreeningService (так бывает на части прошивок). Одна SIM — она и есть;
     * несколько — ищем подписку в состоянии RINGING (Android 12+).
     */
    fun ringingAccountId(): String? = runCatching {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        val accounts = telecom.callCapablePhoneAccounts
        if (accounts.size == 1) return handleId(accounts[0])
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        val tm = context.getSystemService(android.telephony.TelephonyManager::class.java) ?: return null
        val ringing = accounts.filter { h ->
            val subId = runCatching { tm.getSubscriptionId(h) }.getOrDefault(-1)
            subId >= 0 && runCatching {
                tm.createForSubscriptionId(subId).callStateForSubscription == android.telephony.TelephonyManager.CALL_STATE_RINGING
            }.getOrDefault(false)
        }
        if (ringing.size == 1) handleId(ringing[0]) else null
    }.getOrNull()

    override fun simIndex(accountId: String?): Int? {
        if (accountId == null) return null
        val accounts = runCatching { telecom.callCapablePhoneAccounts }.getOrDefault(emptyList())
        val idx = accounts.indexOfFirst { handleId(it) == accountId }
        return if (idx < 0) null else idx
    }

    private fun handleFor(accountId: String?): PhoneAccountHandle? {
        if (accountId == null) return null
        return runCatching {
            telecom.callCapablePhoneAccounts.firstOrNull { handleId(it) == accountId }
        }.getOrNull()
    }

    private fun handleId(handle: PhoneAccountHandle): String {
        runCatching {
            val id = handle.javaClass.getField("id").get(handle)?.toString()
            if (!id.isNullOrBlank()) return id
        }
        val str = handle.toString()
        val inBrackets = str.substringAfterLast('[', "").substringBefore(']', "")
        return inBrackets.ifBlank { str }
    }

    // ------------------------------------------------------------------ MMI

    override suspend fun placeMmi(accountId: String?, mmi: String): ForwardResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ForwardResult.Failed(
                "no_call_phone",
                "Нет разрешения CALL_PHONE: adb shell pm grant ${context.packageName} android.permission.CALL_PHONE",
            )
        }

        val uri = Uri.fromParts(SCHEME_TEL, mmi, null)
        val handle = handleFor(accountId)
        val receiver = UssdResultReceiver(Handler(Looper.getMainLooper()))

        // Основной путь: TelecomManager.placeCall (API 26+) — без показа UI.
        // Публичная сигнатура placeCall(Uri, Bundle): SIM выбирается через
        // EXTRA_PHONE_ACCOUNT_HANDLE (Приложение E, P-6).
        val placed = runCatching {
            val extras = Bundle().apply {
                if (handle != null) putParcelable(EXTRA_PHONE_ACCOUNT_HANDLE, handle)
                // Константа @hide в публичном SDK — используем литерал (Приложение E, P-7).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    putParcelable(EXTRA_RESULT_RECEIVER, receiver)
                }
            }
            telecom.placeCall(uri, extras)
        }

        if (placed.isSuccess) {
            // Ждём ответ USSD, но не дольше бюджета — иначе «статус неизвестен» (FR-3.4).
            val response = withTimeoutOrNull(ussdTimeoutMs) { receiver.await() }
            lastUssdResponse = response
            return if (response == null) {
                ForwardResult.Ok("MMI $mmi отправлен; ответ оператора не получен (статус неизвестен)")
            } else {
                ForwardResult.Ok("MMI $mmi → $response")
            }
        }

        // Fallback: ACTION_CALL (для устройств, где placeCall отклоняет MMI).
        return dialViaIntent(uri, "MMI $mmi")
    }

    // ------------------------------------------------------------------ DIAL

    override suspend fun dial(number: String, accountId: String?): ForwardResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ForwardResult.Failed(
                "no_call_phone",
                "Нет разрешения CALL_PHONE — дозвон невозможен (профиль полномочий, ТЗ п. 7.1)",
            )
        }
        val uri = Uri.fromParts(SCHEME_TEL, number, null)
        val handle = handleFor(accountId)
        val placed = runCatching {
            val extras = Bundle().apply {
                if (handle != null) putParcelable(EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            telecom.placeCall(uri, extras)
        }
        return if (placed.isSuccess) {
            ForwardResult.Ok("placeCall($number)")
        } else {
            dialViaIntent(uri, "дозвон $number")
        }
    }

    private fun dialViaIntent(uri: Uri, what: String): ForwardResult = runCatching {
        val intent = Intent(Intent.ACTION_CALL, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        ForwardResult.Ok("$what через ACTION_CALL")
    }.getOrElse {
        ForwardResult.Failed("dial_failed", "$what не выполнен: ${it.javaClass.simpleName}: ${it.message}")
    }

    /** Приём ответа USSD через EXTRA_RESULT_RECEIVER (API 28+, нужно ANSWER_PHONE_CALLS). */
    private class UssdResultReceiver(handler: Handler) : ResultReceiver(handler) {
        private var continuation: kotlin.coroutines.Continuation<String?>? = null
        private var delivered: String? = null

        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            // EXTRA_USSD_RESPONSE — @hide, используем литерал.
            val text = resultData?.getString(EXTRA_USSD_RESPONSE) ?: "resultCode=$resultCode"
            delivered = text
            continuation?.resume(text)
            continuation = null
        }

        suspend fun await(): String? = suspendCancellableCoroutine { cont ->
            val cached = delivered
            if (cached != null) {
                cont.resume(cached)
            } else {
                continuation = cont
                cont.invokeOnCancellation { continuation = null }
            }
        }
    }

    companion object {
        private const val SCHEME_TEL = "tel"

        /** Литералы скрытых констант TelecomManager (публичный SDK их не отдаёт). */
        private const val EXTRA_PHONE_ACCOUNT_HANDLE = "android.telecom.extra.PHONE_ACCOUNT_HANDLE"
        private const val EXTRA_RESULT_RECEIVER = "android.telecom.extra.RESULT_RECEIVER"
        private const val EXTRA_USSD_RESPONSE = "android.telecom.extra.USSD_RESPONSE"

        /** ТЗ FR-3.6: таймаут USSD-операции 15 с. */
        const val USSD_TIMEOUT_MS = 15_000L
    }
}
