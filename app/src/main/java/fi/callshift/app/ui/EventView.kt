package fi.callshift.app.ui

import fi.callshift.app.forward.CallEvent

/** Человеческое представление записи журнала. */
object EventView {
    enum class Kind(val title: String, val icon: String, val color: Int) {
        MAX_CHECKED("MAX: проверка без отправки", "✓", 0xFF90A4AE.toInt()),
        MAX_UNKNOWN("MAX: результат не подтверждён", "?", 0xFFFFB74D.toInt()),
        MAX_BLOCKED("MAX: сценарий остановлен", "!", 0xFFFFB74D.toInt()),
        REJECT_REQUESTED("Запрошен сброс", "⊘", 0xFFFFB74D.toInt()),
        REJECTED("Сброшен", "⊘", 0xFFE57373.toInt()),
        SILENCED("Без звука", "🔕", 0xFFFFB74D.toInt()),
        PASSED("Прошёл", "✓", 0xFF81C784.toInt()),
        REPLY_DRAFT("Нужна ручная отправка", "✎", 0xFFFFB74D.toInt()),
        REPLY_SKIPPED("Ответ не подготовлен", "✎", 0xFF90A4AE.toInt()),
        TELEGRAM_PENDING("Telegram: ожидание", "✉", 0xFFFFB74D.toInt()),
        TELEGRAM_SENT("Telegram: отправлено", "✉", 0xFF64B5F6.toInt()),
        TELEGRAM_SENT_LOCAL("Telegram: предположительно отправлено", "✉", 0xFF64B5F6.toInt()),
        TELEGRAM_UI_CHECKED("Telegram: проверка без отправки", "✓", 0xFF90A4AE.toInt()),
        TELEGRAM_UNKNOWN("Telegram: результат неизвестен", "?", 0xFFFFB74D.toInt()),
        SMS_PENDING("SMS: ждём подтверждения", "✉", 0xFFFFB74D.toInt()),
        SMS_UNKNOWN("SMS: результат неизвестен", "?", 0xFFFFB74D.toInt()),
        SMS_DELIVERED("SMS доставлена", "✓", 0xFF81C784.toInt()),
        SMS_UNCONFIRMED("Доставка не подтверждена", "?", 0xFFFFB74D.toInt()),
        SMS_SENT("SMS отправлено", "✉", 0xFF64B5F6.toInt()),
        SMS_SKIPPED("SMS не отправлено", "✉", 0xFF90A4AE.toInt()),
        FORWARDED("Перенаправлен", "↪", 0xFF4DB6AC.toInt()),
        ERROR("Ошибка", "⚠", 0xFFFF5252.toInt()),
    }

    fun kind(e: CallEvent): Kind = when (e.strategy) {
        "MAX_UI_REPLY" -> when (e.result) {
            "UI_CHECKED" -> Kind.MAX_CHECKED
            "UI_UNKNOWN", "UI_PENDING" -> Kind.MAX_UNKNOWN
            else -> Kind.MAX_BLOCKED
        }
        "DIALER_RULES" -> when (e.result) {
            "PASS" -> Kind.PASSED
            "REQUESTED" -> Kind.REJECT_REQUESTED
            else -> Kind.ERROR
        }
        "SCREENED" -> when {
            e.result == "PASS" -> Kind.PASSED
            e.errorMessage?.startsWith("звонок без звука") == true -> Kind.SILENCED
            else -> Kind.REJECTED
        }
        "TELEGRAM_REPLY" -> when (e.result) {
            "TG_SENT" -> Kind.TELEGRAM_SENT
            "TG_UI_SENT_LOCAL" -> Kind.TELEGRAM_SENT_LOCAL
            "TG_UI_CHECKED" -> Kind.TELEGRAM_UI_CHECKED
            "TG_LOOKUP", "TG_PENDING", "TG_UI_PENDING" -> Kind.TELEGRAM_PENDING
            "TG_UNKNOWN", "TG_UI_UNKNOWN" -> Kind.TELEGRAM_UNKNOWN
            "FAILED", "ERROR" -> Kind.ERROR
            else -> Kind.REPLY_SKIPPED
        }
        "MESSENGER_DRAFT" -> when (e.result) {
            "PENDING_USER" -> Kind.REPLY_DRAFT
            "FAILED", "ERROR" -> Kind.ERROR
            else -> Kind.REPLY_SKIPPED
        }
        "SMS_REPLY" -> when (e.result) {
            "DELIVERED" -> Kind.SMS_DELIVERED
            "DELIVERY_UNCONFIRMED" -> Kind.SMS_UNCONFIRMED
            "DELIVERY_FAILED" -> Kind.ERROR
            "SENT" -> Kind.SMS_SENT
            "SUBMITTED" -> Kind.SMS_PENDING
            "OK", "UNKNOWN" -> Kind.SMS_UNKNOWN
            "FAILED", "ERROR" -> Kind.ERROR
            else -> Kind.SMS_SKIPPED
        }
        else -> when (e.result) {
            "OK" -> Kind.FORWARDED
            "PASS" -> Kind.PASSED
            else -> Kind.ERROR
        }
    }

    /** Текст «почему» без технических префиксов. */
    fun sentence(e: CallEvent): String {
        val msg = e.errorMessage?.takeIf { it.isNotBlank() }
        val base = when (kind(e)) {
            Kind.FORWARDED -> "Стратегия «" + RuleLabels.strategyTitle(e.strategy) + "»" + (msg?.let { ": $it" } ?: "")
            Kind.ERROR -> if (e.strategy != "SMS_REPLY") "«" + RuleLabels.strategyTitle(e.strategy) + "»: " + (msg ?: e.result) else msg ?: e.result
            else -> msg ?: e.reason
        }
        return base.replaceFirstChar { it.uppercase() }
    }

    data class Stats(val rejected: Int, val passed: Int, val sms: Int, val forwarded: Int, val errors: Int) {
        fun text(): String = buildList {
            add("сброшено $rejected")
            add("прошло $passed")
            add("SMS $sms")
            if (forwarded > 0) add("перенаправлено $forwarded")
            if (errors > 0) add("ошибок $errors")
        }.joinToString(" · ")
    }

    fun stats(events: List<CallEvent>): Stats {
        val k = events.map { kind(it) }
        return Stats(
            rejected = k.count { it == Kind.REJECTED || it == Kind.SILENCED },
            passed = k.count { it == Kind.PASSED },
            sms = k.count { it == Kind.SMS_SENT || it == Kind.SMS_DELIVERED || it == Kind.SMS_UNCONFIRMED },
            forwarded = k.count { it == Kind.FORWARDED },
            errors = k.count { it == Kind.ERROR },
        )
    }

    fun startOfToday(): Long = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
}
