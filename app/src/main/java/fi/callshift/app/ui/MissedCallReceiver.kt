package fi.callshift.app.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Когда CallShift — звонилка по умолчанию, система не показывает своё уведомление
 * о пропущенных, а присылает этот broadcast. Показываем уведомление с кнопками
 * «Перезвонить» и «SMS».
 */
class MissedCallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
        val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try { show(context.applicationContext, count, number) } finally { pending.finish() }
        }
    }

    companion object {
        private const val CHANNEL = "missed"
        private const val ID = 4201

        suspend fun show(ctx: Context, count: Int, number: String?) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (count <= 0) { nm.cancel(ID); return }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Пропущенные звонки", NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = "Уведомление о пропущенных с кнопками «Перезвонить» и «SMS»" },
                )
            }
            val app = CallShiftApp.from(ctx)
            val name = number?.let { n ->
                runCatching { app.contacts.contactName(app.normalizer.normalize(n).e164 ?: n) }.getOrNull()
            }
            val who = name ?: number?.takeIf { it.isNotBlank() } ?: "Скрытый номер"
            val title = if (count == 1) "Пропущенный звонок" else "Пропущенных звонков: $count"
            val text = if (count == 1 || number != null) who else "Откройте «Недавние»"
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

            val open = PendingIntent.getActivity(ctx, 1,
                Intent(ctx, DialerActivity::class.java)
                    .putExtra(DialerActivity.EXTRA_SHOW_RECENTS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)

            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_missed_call)
                .setContentTitle(title)
                .setContentText(text)
                .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setNumber(count)
                .setColor(ContextCompat.getColor(ctx, R.color.brand_accent))

            if (!number.isNullOrBlank()) {
                val call = PendingIntent.getActivity(ctx, 2,
                    Intent(ctx, DialerActivity::class.java)
                        .setData(Uri.fromParts("tel", number, null))
                        .putExtra(DialerActivity.EXTRA_AUTODIAL, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
                val sms = PendingIntent.getActivity(ctx, 3,
                    Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
                b.addAction(R.drawable.ic_call, "Перезвонить", call)
                b.addAction(R.drawable.ic_notification, "SMS", sms)
            }
            runCatching { nm.notify(ID, b.build()) }
        }

        fun cancel(ctx: Context) {
            runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) }
            // Сообщаем системе, что пропущенные просмотрены.
            runCatching { ctx.getSystemService(TelecomManager::class.java)?.cancelMissedCallsNotification() }
        }
    }
}
