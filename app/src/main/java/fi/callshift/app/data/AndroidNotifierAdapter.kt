package fi.callshift.app.data

import android.content.Context
import fi.callshift.app.domain.ForwardResult
import fi.callshift.app.forward.NotifierPort
import fi.callshift.app.util.AndroidNotifier

/**
 * Адаптер [NotifierPort] на системные уведомления (S4, ТЗ п. 4.6).
 *
 * Внешние каналы (Telegram/webhook) в M1/M2 возвращают NotAvailable и
 * ставятся в очередь повторной доставки — это честная деградация,
 * требующаяся ТЗ п. 3.5 (FR-D2): «показать, что доступно на данном устройстве».
 */
class AndroidNotifierAdapter(
    private val context: Context,
    private val notifier: AndroidNotifier,
) : NotifierPort {

    override fun notifyForwarded(text: String, number: String?, ruleName: String?) {
        notifier.showForwarded(text, number, ruleName)
    }

    override fun notifyError(text: String) {
        notifier.showError(text)
    }

    override suspend fun sendExternal(channel: String, endpoint: String?, payload: String): ForwardResult {
        // M2: внешние каналы не подключены (нужны токен бота/URL — ТЗ FR-6.2, M4).
        return ForwardResult.Failed(
            "external_not_configured",
            "Канал $channel не настроен (M4). Событие сохранено в журнале и поставлено в очередь повторной доставки",
        )
    }
}
