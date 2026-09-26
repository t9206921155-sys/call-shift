package fi.callshift.app.domain

/** Local preflight only: never promises operator delivery or OEM behavior. */
object SmsReadiness {
    data class Input(val api: Int, val telephony: Boolean, val smsCapable: Boolean,
        val dialer: Boolean, val screening: Boolean, val phonePermission: Boolean,
        val smsPermission: Boolean, val accounts: Int, val usableAccounts: Int)
    fun problems(i: Input): List<String> = buildList {
        if (i.api < 28) add("Нужен Android 9 или новее")
        if (!i.telephony || !i.smsCapable) add("Устройство не сообщает о поддержке телефонной связи и SMS")
        if (!(i.dialer || (i.api >= 29 && i.screening))) add(
            if (i.api == 28) "Android 9: назначьте CallShift приложением «Телефон» по умолчанию"
            else "Назначьте роль фильтрации звонков или приложения «Телефон»")
        if (!i.phonePermission) add("Выдайте разрешение «Телефон» для определения SIM")
        if (!i.smsPermission) add("Выдайте разрешение на отправку SMS")
        if (i.phonePermission && i.accounts == 0) add("SIM не обнаружены: проверьте SIM/eSIM и настройки оператора")
        if (i.accounts > i.usableAccounts) add("Не все SIM сопоставлены с SMS: отправка с неопределённой карты запрещена")
    }
}
