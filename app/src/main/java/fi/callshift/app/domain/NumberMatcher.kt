package fi.callshift.app.domain

/**
 * Сопоставление номера с маской (ТЗ п. 10.1).
 *
 * Поддерживаемые форматы:
 *  - точное совпадение:      "+358401234567"
 *  - префикс:                "+35840*"
 *  - суффикс:                "*4567"
 *  - маска с '?' (одна цифра): "+35840???????"
 *  - «любой»:                "*"
 *
 * Сравнение идёт по цифрам: все нецифровые символы (пробелы, '-', '(', ')')
 * отбрасываются с обеих сторон, поэтому "+358 40 123" совпадёт с "+35840123".
 */
object NumberMatcher {

    fun matches(pattern: String?, candidate: String?): Boolean {
        if (pattern.isNullOrBlank()) return false
        if (candidate.isNullOrBlank()) return false
        val p = digits(pattern)
        val c = digits(candidate)
        if (p.isEmpty()) return false
        return globMatch(p, c)
    }

    /** Совпадает ли кандидат с любым номером из списка. */
    fun matchesAny(patterns: List<String>, candidate: String?): Boolean =
        patterns.any { matches(it, candidate) }

    /** Валидна ли маска для сохранения правила (UI-4, FR-1.7). */
    fun isValidPattern(pattern: String?): Boolean {
        if (pattern.isNullOrBlank()) return false
        val allowed = pattern.all { it.isDigit() || it == '*' || it == '?' || it == '+' }
        if (!allowed) return false
        // не более двух '*' — защищаем от вырожденных масок
        val stars = pattern.count { it == '*' }
        return stars <= 2
    }

    private fun digits(s: String): String = buildString(s.length) {
        for (ch in s) when {
            ch.isDigit() -> append(ch)
            ch == '*' || ch == '?' -> append(ch)
            else -> Unit // '+', ' ', '-', '(' , ')' — игнорируем
        }
    }

    /** Итеративный glob без рекурсии — не боимся StackOverflow на длинных масках. */
    private fun globMatch(pattern: String, text: String): Boolean {
        var p = 0
        var t = 0
        var star = -1
        var mark = 0
        while (t < text.length) {
            when {
                p < pattern.length && (pattern[p] == '?' || pattern[p] == text[t]) -> {
                    p++; t++
                }
                p < pattern.length && pattern[p] == '*' -> {
                    star = p; mark = t; p++
                }
                star != -1 -> {
                    p = star + 1; mark++; t = mark
                }
                else -> return false
            }
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }
}
