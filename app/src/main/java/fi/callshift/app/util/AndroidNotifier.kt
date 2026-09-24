package fi.callshift.app.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.callshift.app.R
import fi.callshift.app.ui.InCallActivity

/**
 * Системные уведомления (ТЗ п. 6.7, FR-6.1).
 *
 * Каналы:
 *  - `forward`  — события перенаправления (низкий приоритет, без звука);
 *  - `error`    — ошибки исполнения (высокий приоритет);
 *  - `status`   — статус сервиса/ролей (низкий приоритет).
 *
 * POST_NOTIFICATIONS (API 33+) — runtime-разрешение: без него уведомления
 * не показываются, но перехват продолжает работать (fail-open).
 */
class AndroidNotifier(private val context: Context) {

    private val manager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannels()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channels = listOf(
            NotificationChannel(CH_FORWARD, "Перенаправления", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "События обработки входящих вызовов"; setShowBadge(false) },
            NotificationChannel(CH_ERROR, "Ошибки", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Сбои исполнения стратегий" },
            NotificationChannel(CH_STATUS, "Статус сервиса", NotificationManager.IMPORTANCE_MIN)
                .apply { description = "Состояние перехвата и ролей"; setShowBadge(false) },
        )
        runCatching { manager.createNotificationChannels(channels) }
    }

    fun showForwarded(text: String, number: String?, ruleName: String?) {
        if (!canPost()) return
        val notification = base(CH_FORWARD)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(ruleName ?: context.getString(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .build()
        post(ID_FORWARD, notification)
    }

    fun showError(text: String) {
        if (!canPost()) return
        val notification = base(CH_ERROR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_error_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .build()
        post(ID_ERROR, notification)
    }

    /** Постоянное уведомление «сервис активен» (FR-6.4: статус виден пользователю). */
    fun showStatus(active: Boolean, profile: String, rulesCount: Int) {
        if (!canPost()) return
        val text = if (active) {
            context.getString(R.string.notif_status_active, profile, rulesCount)
        } else {
            context.getString(R.string.notif_status_inactive)
        }
        val notification = base(CH_STATUS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(active)
            .build()
        post(ID_STATUS, notification)
    }

    /**
     * Heads-up уведомление с ответом/отбоем для экрана вызова.
     * Используется когда входящий прошёл screening, но требует действия пользователя.
     */
    fun showIncomingAction(number: String, ruleName: String?) {
        if (!canPost()) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, InCallActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(InCallActivity.EXTRA_NUMBER, number)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = base(CH_ERROR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(number)
            .setContentText(ruleName ?: context.getString(R.string.notif_incoming))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        post(ID_INCOMING, notification)
    }

    fun cancelStatus() = runCatching { manager.cancel(ID_STATUS) }

    private fun base(channelId: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channelId)
            .setPriority(
                if (channelId == CH_ERROR) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_LOW,
            )
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)

    private fun openAppPendingIntent(): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, Class.forName("fi.callshift.app.ui.MainActivity"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(id: Int, notification: Notification) =
        runCatching { manager.notify(id, notification) }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val CH_FORWARD = "forward"
        const val CH_ERROR = "error"
        const val CH_STATUS = "status"

        const val ID_FORWARD = 1001
        const val ID_ERROR = 1002
        const val ID_STATUS = 1003
        const val ID_INCOMING = 1004
    }
}
