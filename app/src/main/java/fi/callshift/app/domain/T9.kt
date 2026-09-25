package fi.callshift.app.domain

/** Поиск по цифрам клавиатуры (T9): латиница и кириллица, как на кнопочных телефонах. */
object T9 {
    private val map: Map<Char, Char> = buildMap {
        fun put(d: Char, letters: String) = letters.forEach { put(it, d) }
        put('2', "abcабвг"); put('3', "defдеёжз"); put('4', "ghiийкл"); put('5', "jklмноп")
        put('6', "mnoрсту"); put('7', "pqrsфхцч"); put('8', "tuvшщъы"); put('9', "wxyzьэюя")
        put('0', " ")
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
