package fi.callshift.app.domain

/** Поиск по цифрам клавиатуры (T9): латиница и кириллица, как на кнопочных телефонах. */
object T9 {
    private val map: Map<Char, Char> = buildMap {
        fun keys(d: Char, letters: String) = letters.forEach { this[it] = d }
        keys('2', "abcабвг"); keys('3', "defдеёжз"); keys('4', "ghiийкл"); keys('5', "jklмноп")
        keys('6', "mnoрсту"); keys('7', "pqrsфхцч"); keys('8', "tuvшщъы"); keys('9', "wxyzьэюя")
        keys('0', " ")
    }

    fun encode(text: String): String = buildString {
        text.lowercase().forEach { c -> if (c.isDigit()) append(c) else map[c]?.let { append(it) } }
    }

    /**
     * Насколько имя/номер подходит под набранные цифры. 0 — не подходит;
     * больше — лучше (начало имени > начало слова > часть номера).
     */
    fun score(query: String, name: String?, number: String): Int {
        val q = query.filter { it.isDigit() }
        if (q.isEmpty()) return 0
        if (!name.isNullOrBlank()) {
            val words = name.trim().split(Regex("\\s+")).map { encode(it) }
            if (words.firstOrNull()?.startsWith(q) == true) return 100
            if (words.any { it.startsWith(q) }) return 80
            // Инициалы: «ИИ» → Иван Иванов
            if (q.length >= 2 && words.joinToString("") { it.take(1) }.startsWith(q)) return 60
        }
        val digits = number.filter { it.isDigit() }
        if (digits.startsWith(q)) return 50
        if (q.length >= 3 && digits.contains(q)) return 40
        return 0
    }
}
