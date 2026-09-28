package fi.callshift.app.domain

object MaxCardLearningPolicy {
    @kotlinx.serialization.Serializable
    data class Rule(val version: Long, val header: String, val input: String,
        val targetId: String, val targetClass: String, val shape: String)
    enum class Status(val explanation: String) {
        IDLE("Обучение открытия карточки ещё не запускалось"),
        WAIT_CHAT("Откройте личный чат с пустым полем сообщения, подождите 3 секунды"),
        WAIT_TAP("Шапка распознана. Нажмите имя или аватар ОДИН раз"),
        WAIT_CARD("Проверяем, что ручное нажатие открыло карточку с телефоном"),
        RETURNING("Возвращаемся в исходный чат для проверки действия"),
        REPLAY("Проверяем одно автоматическое открытие карточки — без сообщения"),
        FINAL_RETURN("Возвращаемся после проверочного открытия"),
        SAVED("Действие открытия карточки проверено и сохранено. Теперь запустите тест без сообщения"),
        NO_SERVICE("Служба MAX не подключена"),
        NO_LAYOUT("Сначала нужен сохранённый профиль чата для текущей версии MAX"),
        NO_TARGETS("У элементов шапки нет уникальных resource ID или действия нажатия. Обучение для этого экрана недоступно"),
        SOURCE_MISSING("Android не передал источник ручного нажатия. По координатам не нажимаем"),
        SOURCE_REJECTED("Нажатие не соответствует одному из сохранённых элементов шапки. Повторите обучение без других действий"),
        EXTRA_CLICK("Дополнительное нажатие во время проверки: обучение остановлено"),
        CARD_UNVERIFIED("После нажатия не подтверждена карточка с единственным полем телефона"),
        RETURN_UNVERIFIED("После карточки исходные элементы чата или пустое поле не подтверждены"),
        REPLAY_FAILED("MAX не принял проверочное нажатие. Действие не сохранено"),
        TIMEOUT("Время обучения истекло; действие не сохранено"),
        STOPPED("Обучение остановлено; новое действие не сохранено"),
        ERROR("Ошибка обучения; новое действие не сохранено"),
    }
    fun matches(saved: Rule, actual: Rule): Boolean = saved.version >= 0 &&
        saved.targetId.isNotBlank() && saved.targetClass.isNotBlank() && saved.shape.isNotBlank() && saved == actual
    fun fresh(now: Long, snapshotAt: Long): Boolean = now >= snapshotAt && now - snapshotAt <= 1500
}
