package fi.callshift.app.domain

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Нормализация номера в E.164 (ТЗ п. 10.1, FR-3.3).
 *
 * defaultRegion — страна устройства (у заказчика FI). Если регион определить
 * не удалось, берём "FI" как fallback, но всегда пытаемся разобрать номер
 * с '+' — тогда регион не важен.
 */
class PhoneNumberNormalizer(private val defaultRegion: String = "FI") {

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    data class Normalized(
        val e164: String? = null,
        val national: String? = null,
        val isValid: Boolean = false,
        val isEmergency: Boolean = false,
    )

    fun normalize(raw: String?): Normalized {
        val source = raw?.let { stripTelScheme(it) }?.takeIf { it.isNotBlank() } ?: return Normalized()
        return try {
            val number = util.parse(source, defaultRegion)
            val e164 = util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
            val national = util.format(number, PhoneNumberUtil.PhoneNumberFormat.NATIONAL)
            Normalized(
                e164 = e164,
                national = national,
                isValid = util.isValidNumber(number),
                // isEmergencyNumber() отсутствует в публичном API libphonenumber —
                // проверяем локальным списком коротких номеров (FR-2.6, Приложение E P-9).
                isEmergency = looksLikeLocalEmergency(source),
            )
        } catch (_: NumberParseException) {
            // Не телефонный номер (например, SIP URI) — отдаём как есть.
            if (source.startsWith("sip:", ignoreCase = true) || source.contains('@')) {
                Normalized(e164 = source, isValid = true)
            } else {
                Normalized(isEmergency = looksLikeLocalEmergency(source))
            }
        } catch (_: Throwable) {
            Normalized(isEmergency = looksLikeLocalEmergency(source))
        }
    }

    /** Проверка «похоже на экстренный номер» без metadata libphonenumber (FR-2.6). */
    fun looksLikeLocalEmergency(raw: String): Boolean {
        val d = raw.filter { it.isDigit() }
        return d in EMERGENCY_SHORT_CODES || d.startsWith("112") || d.startsWith("911")
    }

    /** Приводим tel:-URI / MMI-строку к «сырому» номеру. */
    fun stripTelScheme(value: String): String {
        var v = value.trim()
        if (v.startsWith("tel:", ignoreCase = true)) v = v.substring(4)
        // обрезаем post-dial (паузы ',', ';' и DTMF после '#')
        val cut = v.indexOfFirst { it == ',' || it == ';' || it == '#' }
        if (cut > 0 && !v.startsWith("*") && !v.startsWith("#")) v = v.substring(0, cut)
        return v.trim()
    }

    /** Маскируем номер для логов уровня INFO (LOG-3) и опционально для UI (FR-7.4). */
    fun mask(number: String?): String {
        if (number.isNullOrBlank()) return "unknown"
        val digits = number.filter { it.isDigit() }
        return when {
            digits.length <= 4 -> "*".repeat(digits.length)
            digits.length <= 7 -> number.take(3) + "*".repeat(digits.length - 3)
            else -> number.take(number.length - 7) + "*".repeat(4) + digits.takeLast(3)
        }
    }

    companion object {
        /** Короткие экстренные номера (FI/ЕС + типовые). Полный список — из CarrierConfig. */
        val EMERGENCY_SHORT_CODES = setOf(
            "112", "911", "999", "000", "08", "110", "100", "101", "102", "103", "104", "116000", "116111",
        )
    }
}
