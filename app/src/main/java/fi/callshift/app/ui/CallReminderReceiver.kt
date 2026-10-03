package fi.callshift.app.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.callshift.app.R

/** «Напомнить позже»: через заданное время показывает уведомление «Перезвонить». */
class CallReminderReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: return
        val name = intent.getStringExtra(EXTRA_NAME)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Напоминания перезвонить", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val id = number.hashCode()
        val call = PendingIntent.getActivity(ctx, id,
            Intent(ctx, DialerActivity::class.java).setData(Uri.fromParts("tel", number, null))
                .putExtra(DialerActivity.EXTRA_AUTODIAL, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val sms = PendingIntent.getActivity(ctx, id + 1,
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Перезвонить: ${name ?: number}")
            .setContentText("Вы просили напомнить об этом звонке")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(call)
            .setAutoCancel(true)
            .addAction(R.drawable.ic_call, "Перезвонить", call)
            .addAction(R.drawable.ic_message, "SMS", sms)
            .build()
        runCatching { nm.notify(id, n) }
    }

    companion object {
        private const val CHANNEL = "reminders"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_NAME = "name"

        fun schedule(ctx: Context, number: String, name: String?, delayMs: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val pi = PendingIntent.getBroadcast(ctx, number.hashCode(),
                Intent(ctx, CallReminderReceiver::class.java).putExtra(EXTRA_NUMBER, number).putExtra(EXTRA_NAME, name),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi)
        }
    }
}
