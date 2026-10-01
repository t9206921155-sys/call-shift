package fi.callshift.app.domain

/** Отправка имитацией касаний в приложении Telegram — те же принципы, что в MAX:
 * отказ при любой неоднозначности, одна попытка, без повторов, без привязки к
 * получателю. Хранится только форма кнопки отправки; чат, контакт, текст
 * сообщения и экранные координаты никогда не сохраняются. */
object TelegramUiPolicy {
    const val PACKAGE = "org.telegram.messenger"
    const val TRAIN_TEXT = "Тест обучения CallShift"

    @kotlinx.serialization.Serializable
    data class SendRule(val version: Long, val targetId: String, val targetClass: String,
        val desc: String, val gesture: Boolean)

    /** «+7» и «8», пробелы и скобки никогда не меняют решение. */
    fun digits(v: String): String {
        val d = v.filter { it.isDigit() }
        return if (d.length == 11 && (d[0] == '7' || d[0] == '8')) "7" + d.takeLast(10) else d
    }

    fun sameNumber(a: String, b: String): Boolean {
        val da = digits(a); val db = digits(b)
        return da.isNotEmpty() && da == db
    }

    fun looksLikeSend(desc: String, id: String): Boolean {
        val hay = (desc + " " + id).lowercase()
        return listOf("send", "отправ").any { hay.contains(it) }
    }

    enum class TrainStatus(val explanation: String) {
        IDLE("Обучение кнопки Telegram ещё не запускалось"),
        WAIT_CHAT("Откройте любой безопасный чат Telegram (например «Избранное») — текст CallShift введёт сам"),
        WAIT_TAP("Черновик виден. Нажмите кнопку отправки Telegram ОДИН раз"),
        VERIFY("Проверяем, что нажатие отправило сообщение"),
        SAVED("Кнопка отправки Telegram распознана и сохранена. Вернитесь в CallShift и запустите тестовую отправку"),
        NO_SERVICE("Служба специальных возможностей Telegram не подключена"),
        BUSY("Telegram занят отправкой или другим обучением; сначала завершите его"),
        NO_EDITOR("Поле сообщения Telegram не распознано; обучение остановлено"),
        FOREIGN("Нажатие не соответствует кнопке возле поля сообщения. Ничего не сохранено"),
        SOURCE_MISSING("Android не передал источник нажатия, а кнопку возле поля нельзя определить однозначно. Ничего не сохранено"),
        NOT_SENT("После нажатия текст не исчез — сообщение не ушло; ничего не сохранено"),
        TIMEOUT("Время обучения истекло; действие не сохранено"),
        STOPPED("Обучение остановлено; новое действие не сохранено"),
        ERROR("Ошибка обучения; новое действие не сохранено"),
    }
}
