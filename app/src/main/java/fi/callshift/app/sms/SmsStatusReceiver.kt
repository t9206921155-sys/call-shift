package fi.callshift.app.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsMessage
import fi.callshift.app.CallShiftApp
import kotlinx.coroutines.launch

/** Explicit, non-exported receiver; only our PendingIntents can reach it. */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val segments = intent.data?.pathSegments ?: return
        if (segments.size != 3) return
        val (id, kind, partString) = segments
        val part = partString.toIntOrNull() ?: return
        if (kind != "sent" && kind != "delivery") return
        val code = resultCode
        val deliveryStatus = if (kind == "delivery") runCatching {
            val pdu = intent.getByteArrayExtra("pdu") ?: return@runCatching null
            val format = intent.getStringExtra("format")
            @Suppress("DEPRECATION")
            val sms = if (format != null) SmsMessage.createFromPdu(pdu, format) else SmsMessage.createFromPdu(pdu)
            val status = sms?.status ?: return@runCatching null
            if (format == "3gpp2") {
                when {
                    status == 0 -> 0
                    (status shr 8) == 3 -> 64
                    (status shr 8) == 2 -> 32
                    else -> 1 // unsupported CDMA receipt: do not claim delivery
                }
            } else status
        }.getOrNull() else null
        val pending = goAsync()
        CallShiftApp.from(context).appScope.launch {
            try {
                SmsAttemptStore.update(context, id) {
                    if (kind == "sent") it.sentResult(part, code)
                    else if (deliveryStatus != null) it.deliveryResult(part, deliveryStatus)
                    else it // Missing PDU is not proof of delivery.
                }
            } catch (error: Exception) {
                android.util.Log.e("CallShift", "SMS receipt persistence failed", error)
            } finally { pending.finish() }
        }
    }
}
