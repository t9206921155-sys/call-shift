package fi.callshift.app.domain

/** Closed, text-free diagnostics for the system chooser, not a tree export. */
object MaxPickerInspection {
    enum class Issue(val explanation: String) {
        STARTED("Изучение запущено. Оставьте окно с двумя MAX открытым на 3 секунды"),
        SERVICE_UNAVAILABLE("Служба CallShift — MAX не подключена"),
        LOCKED("Экран заблокирован или выключен"),
        NO_ROOT("Android не передал активное окно, в том числе через список окон"),
        OWN_APP("Окно выбора ещё не получено: активен CallShift"),
        MAX_DIRECT("Открыт сам MAX, а не окно выбора. Проверьте выбор приложения по умолчанию"),
        NO_PACKAGE("Android не передал владельца окна"),
        PACKAGE_UNAVAILABLE("Android не позволил проверить системный пакет окна. Нажатия запрещены"),
        NOT_SYSTEM("Активное окно не принадлежит системному приложению. Оно не изучается"),
        TRUNCATED("Дерево окна неполное или слишком большое. Однозначность не подтверждена"),
        TITLE_MISSING("В доступных элементах не найден заголовок системного окна выбора"),
        LABELS_MISSING("Не найдены обе доступные подписи MAX и MAX (…). Текст на экране может быть недоступен службе"),
        LABELS_AMBIGUOUS("Подписи вариантов повторяются или вариантов больше двух"),
        ROW_UNAVAILABLE("Подписи найдены, но не найден отдельный элемент нажатия для каждой копии"),
        ROW_AMBIGUOUS("У варианта несколько элементов нажатия либо общий элемент для обеих копий"),
        READY("Окно выбора распознано. Выберите вариант для каждой SIM"),
        ERROR("Ошибка чтения окна. Нажатий не было; подробности содержимого не записываются"),
    }
    data class Report(val issue: Issue, val visible: Int = 0, val titles: Int = 0,
        val labels: Int = 0, val described: Int = 0, val clickable: Int = 0)
    /** Do not replace a concrete refusal with the transition back to our activity. */
    fun meaningful(issue: Issue): Boolean = issue !in setOf(Issue.STARTED, Issue.OWN_APP, Issue.NO_ROOT, Issue.LOCKED)
}
