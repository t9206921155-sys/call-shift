package fi.callshift.app.domain

/** Стратегии перенаправления — ТЗ п. 3.3 (S1..S4) + служебные вердикты. */
enum class StrategyId {
    /** Ничего не делать, пропустить вызов штатно. */
    PASS,

    /** Отклонить вызов без перенаправления. */
    BLOCK,

    /** S1 — сетевая переадресация оператора через MMI-код (`*21*…#`). */
    MMI_FORWARD,

    /** S2 — screening-отбой + дозвон на целевой номер (callback-leg). */
    CALLBACK_DIAL,

    /** S3 — SIP/VoIP-мост (M3, в M1 отдаёт NOT_AVAILABLE). */
    SIP_BRIDGE,

    /** S4 — уведомление/эскалация (Telegram/webhook/локальное уведомление). */
    NOTIFY,
}

/** Исход для screening-ответа (ТЗ п. 3.5, профиль полномочий A/B/C). */
enum class Verdict {
    /** Пропустить вызов. */
    PASS,

    /** disallow + reject: вызов сбрасывается, как будто пользователь отклонил. */
    DISALLOW_REJECT,

    /** disallow + reject + rejectedAsMissed: вызов уходит в «пропущенные». */
    DISALLOW_AS_MISSED,

    /** silence: звонок без звука, но вызов показывается пользователю (API 29+). */
    SILENCE,
}

enum class Direction { INCOMING, OUTGOING }

/** Результат исполнения стратегии. */
sealed interface ForwardResult {
    data class Ok(val detail: String = "") : ForwardResult
    data class Failed(val code: String, val message: String) : ForwardResult
    data object NotAvailable : ForwardResult
    data object SkippedByGuard : ForwardResult
}

/**
 * Ссылка на SIM/PhoneAccount. Храним строковый id, а НЕ индекс «SIM1/SIM2»,
 * чтобы правила не ломались при замене SIM/eSIM (ТЗ E-15).
 */
data class PhoneAccountRef(
    val id: String,
    val label: String = "",
) {
    companion object {
        const val ANY = "ANY"
        val Any = PhoneAccountRef(ANY, "Любая SIM")
    }
}

/**
 * Профиль полномочий приложения — ТЗ п. 3.5.
 * Определяет, какие API реально доступны и какие функции должны деградировать.
 */
enum class PermissionProfile {
    /** Нет ни роли screening, ни роли dialer — перехват не работает. */
    NONE,

    /** Только ROLE_CALL_SCREENING. Доступны disallow/reject; silence — при ANSWER_PHONE_CALLS. */
    SCREENING,

    /** ROLE_DIALER — полный контроль Telecom, разрешения выдаются по роли. */
    DIALER,

    /** Root/system: доступны аудио-мост L2 и privileged-разрешения (ТЗ п. 10.7). */
    SYSTEM,
    ;

    val canScreen: Boolean get() = this != NONE
    val canSilence: Boolean get() = this == DIALER || this == SYSTEM
    val canSkipNotification: Boolean get() = this == DIALER || this == SYSTEM
    val canSkipCallLog: Boolean get() = this == SYSTEM // только carrier/system, ТЗ п. 3.5
    /** Профиль B+: доступно управление вызовом через InCallService (M2). */
    val canManageCall: Boolean get() = this == DIALER || this == SYSTEM
}
