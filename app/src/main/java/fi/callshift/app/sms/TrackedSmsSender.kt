package fi.callshift.app.sms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import fi.callshift.app.domain.SmsSendTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** Wait for Android's SENT callbacks for every segment, not just the API return. */
object TrackedSmsSender {
    data class Result(val status: String, val message: String)

    suspend fun send(context: Context, sms: SmsManager, number: String, text: String): Result {
        val parts = sms.divideMessage(text)
        val tracker = SmsSendTracker(parts.size)
        val complete = CompletableDeferred<List<Int>>()
        val action = context.packageName + ".SMS_SENT." + java.util.UUID.randomUUID()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                tracker.accept(intent.getIntExtra("part", -1), resultCode)?.let { complete.complete(it) }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val callbacks = arrayListOf<PendingIntent>()
        try {
            parts.indices.forEach { index ->
                callbacks.add(PendingIntent.getBroadcast(context, index,
                    Intent(action).setPackage(context.packageName).putExtra("part", index),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE))
            }
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, callbacks, null)
            else sms.sendTextMessage(number, null, text, callbacks.single(), null)
            val results = withTimeoutOrNull(45_000) { complete.await() }
                ?: return Result("UNKNOWN", "Android не подтвердил отправку за 45 секунд. Результат неизвестен; автоматического повтора нет.")
            val sent = results.count { it == Activity.RESULT_OK }
            return if (sent == parts.size) Result("SENT",
                "Android подтвердил отправку всех частей SMS. Доставка получателю не подтверждена.")
            else Result("FAILED", "Отправлено частей: $sent/${parts.size}. Ошибки: " +
                results.filter { it != Activity.RESULT_OK }.distinct().joinToString { code ->
                    when (code) {
                        SmsManager.RESULT_ERROR_RADIO_OFF -> "радиомодуль выключен"
                        SmsManager.RESULT_ERROR_NO_SERVICE -> "нет сети"
                        SmsManager.RESULT_ERROR_NULL_PDU -> "ошибка SMS PDU"
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "сбой отправки/оператора"
                        else -> "код Android $code"
                    }
                } + ". Автоматического повтора нет, чтобы не дублировать части.")
        } finally {
            context.unregisterReceiver(receiver)
            callbacks.forEach { it.cancel() }
        }
    }
}
