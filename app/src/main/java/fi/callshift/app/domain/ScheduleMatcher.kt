package fi.callshift.app.domain

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Проверка расписания правила (ТЗ п. 9.4, US-1, E-17).
 *
 * Расписание всегда считается в локальной часовой зоне устройства, ZoneId
 * фиксируется на момент проверки — при смене TZ расписание пересчитывается
 * автоматически (требование E-17).
 */
object ScheduleMatcher {

    fun matches(schedule: ScheduleSpec?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (schedule == null) return true
        val ldt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
        if (!dayMatches(schedule.days, ldt.dayOfWeek.value)) return false
        return timeMatches(schedule.from, schedule.to, ldt.toLocalTime())
    }

    /** [days] пустой = каждый день. 1 = понедельник … 7 = воскресенье (ISO-8601). */
    fun dayMatches(days: List<Int>, isoDayOfWeek: Int): Boolean =
        days.isEmpty() || days.contains(isoDayOfWeek)

    /** Поддерживает окна через полночь: from=22:00, to=07:00. */
    fun timeMatches(from: String?, to: String?, now: LocalTime): Boolean {
        val f = parse(from) ?: return true
        val t = parse(to) ?: return true
        return if (t.isAfter(f)) {
            // обычное окно внутри суток: 09:00–18:00
            !now.isBefore(f) && !now.isAfter(t)
        } else {
            // окно через полночь: 22:00–07:00
            !now.isBefore(f) || !now.isAfter(t)
        }
    }

    fun parse(hhmm: String?): LocalTime? {
        if (hhmm.isNullOrBlank()) return null
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return LocalTime.of(h, m)
    }
}
