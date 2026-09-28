package fi.callshift.app.domain

/** One next step. Configuration, a dry check and permission to send are distinct states. */
object MaxSetupPolicy {
    enum class Step(val button: String) {
        PHONE_PERMISSION("Разрешить доступ к SIM"), SIM("Выберите SIM выше"),
        SERVICE("Подключить управление MAX"), ROUTE("Выбрать обычный MAX или копию"),
        LAYOUT("Настроить экран чата"), RUNNING("Идёт проверка…"),
        MASTER("Включить CallShift"), UNAVAILABLE("Автоответ MAX пока недоступен"), CARD("Настроить открытие карточки"), CHECK("Проверить без отправки"),
        ENABLE("Разрешить реальные ответы"), LIVE("Повторить проверку без отправки")
    }
    fun next(phonePermission: Boolean, sim: Boolean, connected: Boolean, route: Boolean,
        layout: Boolean, running: Boolean, cardFailure: Boolean, passed: Boolean, live: Boolean, unavailable: Boolean = false, master: Boolean = true): Step = when {
        running -> Step.RUNNING
        !phonePermission -> Step.PHONE_PERMISSION
        !sim -> Step.SIM
        !connected -> Step.SERVICE
        !route -> Step.ROUTE
        !layout -> Step.LAYOUT
        unavailable -> Step.UNAVAILABLE
        cardFailure -> Step.CARD
        !master -> Step.MASTER
        !passed -> Step.CHECK
        !live -> Step.ENABLE
        else -> Step.LIVE
    }
}
