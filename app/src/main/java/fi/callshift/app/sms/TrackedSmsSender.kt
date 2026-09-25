package fi.callshift.app.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import fi.callshift.app.domain.SmsAttempt
import fi.callshift.app.forward.CallEvent

/** Request both SENT and delivery receipts; no in-memory timeout receiver. */
object TrackedSmsSender {
    suspend fun send(context: Context, sms: SmsManager, number: String, text: String, event: CallEvent) {
        val parts = sms.divideMessage(text)
        val id = java.util.UUID.randomUUID().toString()
        val attempt = SmsAttempt(event.copy(eventId = id), List(parts.size) { null }, List(parts.size) { null })
        // Fail closed before sending if durable tracking cannot be created.
        SmsAttemptStore.create(context, attempt)
        fun callback(kind: String, part: Int): PendingIntent {
            val intent = Intent(context, SmsStatusReceiver::class.java).apply {
                data = Uri.Builder().scheme("callshift-sms").authority("receipt")
                    .appendPath(id).appendPath(kind).appendPath(part.toString()).build()
            }
            // Delivery fill-in contains the PDU. Mutability is restricted by an
            // explicit private component and unique immutable URI identity.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (kind == "delivery") {
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            } else PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(context, 0, intent, flags)
        }
        try {
            val sent = ArrayList(parts.indices.map { callback("sent", it) })
            val delivered = ArrayList(parts.indices.map { callback("delivery", it) })
            if (parts.size > 1) sms.sendMultipartTextMessage(number, null, parts, sent, delivered)
            else sms.sendTextMessage(number, null, text, sent.single(), delivered.single())
        } catch (error: Exception) {
            SmsAttemptStore.update(context, id) { it.copy(submissionError = error.javaClass.simpleName) }
        }
    }
}
