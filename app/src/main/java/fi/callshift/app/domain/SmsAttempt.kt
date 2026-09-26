package fi.callshift.app.domain

import fi.callshift.app.forward.CallEvent
import kotlinx.serialization.Serializable

/** Durable, monotonic state for one multipart SMS. No automatic retries. */
@Serializable
data class SmsAttempt(
    val event: CallEvent,
    val sent: List<Int?>,
    val delivery: List<Int?>,
    val sendTimedOut: Boolean = false,
    val deliveryTimedOut: Boolean = false,
    val submissionError: String? = null,
) {
    init { require(sent.isNotEmpty() && sent.size == delivery.size) }

    fun sentResult(part: Int, code: Int): SmsAttempt =
        if (part !in sent.indices || sent[part] != null) this
        else copy(sent = sent.mapIndexed { i, old -> if (i == part) code else old })

    fun deliveryResult(part: Int, status: Int): SmsAttempt {
        if (part !in delivery.indices || status !in 0..255) return this
        val old = delivery[part]
        // Temporary report may be followed by final success/failure. Final reports
        // are immutable. Unknown reports do not replace better evidence.
        if (old == 0 || (old != null && old in 64..127)) return this
        return copy(delivery = delivery.mapIndexed { i, value -> if (i == part) status else value })
    }

    fun status(): String = when {
        delivery.all { it == 0 } -> "DELIVERED"
        delivery.any { it != null && it in 64..127 } -> "DELIVERY_FAILED"
        sent.any { it != null && it != -1 } || submissionError != null -> "FAILED"
        sent.all { it == -1 } || delivery.any { it == 0 } ->
            if (deliveryTimedOut) "DELIVERY_UNCONFIRMED" else "SENT"
        sendTimedOut -> "UNKNOWN"
        else -> "SUBMITTED"
    }

    fun message(): String {
        val sentCount = sent.count { it == -1 }
        val deliveredCount = delivery.count { it == 0 }
        val counts = "Частей: отправлено $sentCount/${sent.size}, доставлено $deliveredCount/${sent.size}."
        return when (status()) {
            "DELIVERED" -> "Получены отчёты о доставке всех частей SMS. Это не подтверждение прочтения. $counts"
            "DELIVERY_FAILED" -> "Оператор сообщил ошибку доставки (статусы ${delivery.filterNotNull().filter { it in 64..127 }.distinct()}). $counts"
            "FAILED" -> "Ошибка отправки: ${submissionError ?: "коды Android " + sent.filterNotNull().filter { it != -1 }.distinct()}. $counts Автоповтора нет."
            "SENT" -> "Есть подтверждение отправки Android или отчёт оператора. Доставка всех частей ещё не подтверждена. $counts"
            "DELIVERY_UNCONFIRMED" -> "За 24 часа доставка всех частей не подтверждена. Это НЕ означает недоставку: оператор может не присылать отчёты. $counts"
            "UNKNOWN" -> "Нет полного подтверждения отправки за 2 минуты. Результат неизвестен. $counts Автоповтора нет."
            else -> "SMS поставлена на отправку с SIM вызова. $counts Ожидаем подтверждения."
        }
    }
}
