package fi.callshift.app.domain

/** Explains refusal without exposing UI text or weakening recipient checks. */
object MaxProfileCapture {
    enum class Issue(val explanation: String) {
        NOT_STARTED("Нажмите «Проверить интерфейс MAX», затем откройте переписку с полем сообщения"),
        WAITING("Проверка запущена. Откройте в MAX сам чат с полем сообщения"),
        SERVICE_UNAVAILABLE("Служба CallShift — MAX не подключена. Проверьте специальные возможности Android"),
        NO_ROOT("Android пока не передал активное окно"),
        OTHER_APP("На экране не MAX. Откройте MAX и нужный чат"),
        LOCKED("Экран выключен или заблокирован"),
        NO_HEADER("Заголовок чата в поддерживаемой панели не найден. Возможна другая структура интерфейса MAX"),
        HEADER_ID_MISSING("Заголовок виден, но MAX не передал его resource ID"),
        AMBIGUOUS_HEADER("Найдено несколько возможных заголовков — автоматический выбор запрещён"),
        NO_INPUT("Поле сообщения не найдено. Нужен экран переписки, не карточка контакта и не список чатов"),
        INPUT_ID_MISSING("Поле сообщения видно, но MAX не передал его resource ID"),
        AMBIGUOUS_INPUT("На экране несколько полей ввода. Закройте поиск и откройте сам чат"),
        READY("Профиль интерфейса найден. Вернитесь и нажмите «Сохранить»"),
        EXPIRED("Образец устарел или MAX обновлён. Повторите проверку интерфейса"),
        ERROR("Не удалось прочитать интерфейс. Отправка не выполнялась"),
    }
    data class Header(val index: Int, val hasId: Boolean, val phone: Boolean, val titleId: Boolean)
    data class Input(val index: Int, val hasId: Boolean)
    data class Selection(val issue: Issue, val headerIndex: Int? = null, val inputIndex: Int? = null)
    fun select(headers: List<Header>, inputs: List<Input>): Selection {
        if (inputs.isEmpty()) return Selection(Issue.NO_INPUT)
        val input = inputs.singleOrNull() ?: return Selection(Issue.AMBIGUOUS_INPUT)
        if (!input.hasId) return Selection(Issue.INPUT_ID_MISSING)
        if (headers.isEmpty()) return Selection(Issue.NO_HEADER)
        val ids = headers.filter { it.hasId }
        if (ids.isEmpty()) return Selection(Issue.HEADER_ID_MISSING)
        val phones = ids.filter { it.phone }
        val titles = ids.filter { it.titleId }
        val preferred = when { phones.isNotEmpty() -> phones; titles.isNotEmpty() -> titles; else -> ids }
        val h = preferred.singleOrNull() ?: return Selection(Issue.AMBIGUOUS_HEADER)
        return Selection(Issue.READY, h.index, input.index)
    }
    fun fresh(now: Long, captured: Long, version: Long, installedVersion: Long): Boolean =
        version >= 0 && version == installedVersion && now >= captured && now - captured <= 10 * 60_000L
}
