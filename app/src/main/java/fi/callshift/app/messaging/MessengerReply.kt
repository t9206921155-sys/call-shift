package fi.callshift.app.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fi.callshift.app.domain.ReplyChannel

/** Only offers a draft; never claims delivery and never silently falls back to SMS. */
object MessengerReply {
    const val CHANNEL = "messenger_reply_drafts"

    fun offer(context: Context, number: String, text: String, channel: String) {
        require(ReplyChannel.isManual(channel))
        require(ReplyChannel.isPhoneAddress(number)) { "Номер звонящего не определён" }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "Ответ звонящему в мессенджере", NotificationManager.IMPORTANCE_DEFAULT,
        ))
        check(NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) {
            "Разрешите уведомления CallShift, чтобы открыть ответ в мессенджере"
        }
        val intent = Intent(context, MessengerReplyActivity::class.java).apply {
            data = Uri.parse("callshift-reply:" + java.util.UUID.randomUUID().toString())
            putExtra("number", number)
            putExtra("text", text)
            putExtra("channel", channel)
        }
        val pending = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Ответ звонящему: требуется отправка")
            .setContentText(ReplyChannel.labels[channel])
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending).setAutoCancel(true).build()
        manager.notify("reply:$channel:$number", 1, notification)
    }
}
