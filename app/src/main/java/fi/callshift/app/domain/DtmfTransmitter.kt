package fi.callshift.app.domain

/**
 * Чистая логика DTMF-передачи исходного номера звонящего целевому абоненту.
 *
 * Это компенсационный механизм из ТЗ п. 10.6: стороннее приложение НЕ может
 * подменить CLI в сети GSM, но если цель — IVR/АТС, она способна принять
 * номер звонящего тоновым набором. Профиль B (InCallService.playDtmfTone)
 * делает это технически возможным.
 *
 * Класс не зависит от Android — покрыт unit-тестами (ТЗ п. 14.1).
 */
object DtmfTransmitter {

    /** Допустимые символы DTMF (3GPP TS 24.008 / RFC 4733). */
    private val VALID = "0123456789*#ABCD"

    /**
     * Собирает DTMF-последовательность для передачи номера.
     *
     * @param originalNumber исходный номер звонящего (E.164 или как есть)
     * @param prefix префикс, который ожидает АТС (например "" или "*9")
     * @param terminator завершающий символ (обычно "#"); null — не добавлять
     * @param dropPlus убирать ведущий '+' (многие IVR не понимают его в DTMF)
     * @return последовательность символов либо null, если передавать нечего
     */
    fun buildSequence(
        originalNumber: String?,
        prefix: String = "",
        terminator: String? = "#",
        dropPlus: Boolean = true,
    ): String? {
        if (originalNumber.isNullOrBlank()) return null
        var digits = originalNumber.trim()
        if (dropPlus) digits = digits.removePrefix("+")
        // В DTMF имеют смысл только допустимые символы; прочее (пробелы, '-', '(') отбрасываем.
        digits = digits.filter { it.uppercaseChar() in VALID }
        if (digits.isEmpty()) return null
        val p = prefix.filter { it.uppercaseChar() in VALID }
        val t = terminator?.filter { it.uppercaseChar() in VALID }.orEmpty()
        return "$p$digits$t"
    }

    /** Разбивает последовательность на отдельные символы для playDtmfTone(). */
    fun toChars(sequence: String): List<Char> = sequence.toList()

    /**
     * Пауза между тонами, мс. Слишком быстро — IVR не успевает распознать,
     * слишком медленно — занимает эфир. 120 мс — типовое значение.
     */
    const val TONE_GAP_MS = 120L

    /** Максимальная длина последовательности: защита от «простыни» в эфире. */
    const val MAX_SEQUENCE_LENGTH = 32
}
