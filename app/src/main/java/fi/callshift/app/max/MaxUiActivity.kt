package fi.callshift.app.max

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import fi.callshift.app.domain.MaxUiPolicy
import fi.callshift.app.ui.FormUi

class MaxUiActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val store by lazy { MaxUiStore(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = FormUi(this)
        ui.title("MAX: экспериментальная автоматизация")
        ui.hint("Это управление интерфейсом, НЕ API MAX. Может искать по полному номеру и открыть единственный результат, где явно виден этот номер. +7 и 8 сравниваются как один номер. Для чата с именем открывается карточка и проверяется поле «Номер телефона». Имя из адресной книги можно использовать для поиска кандидатов, но отправка разрешается только после проверки номера и возврата в исходный чат. По координатам не нажимаем. Заблокированный экран, другой чат или изменённый интерфейс — остановка.")
        ui.hint("Служба получает доступ к содержимому экрана MAX и может вводить текст и нажимать кнопку отправки. Содержимое других приложений не обрабатывается, снимки интерфейса не загружаются на сервер. Краткий образец шапки используется локально в памяти при настройке. Разрешение выдаёте только вы в настройках Android.")
        status = ui.hint("")
        fun button(label: String, action: () -> Unit) = ui.add(MaterialButton(this).apply { text = label; setOnClickListener { action() } })
        button("1. Открыть специальные возможности") {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure { toast("Откройте настройки Android вручную") }
        }
        ui.hint("Найдите CallShift — MAX (эксперимент). Если Android блокирует разрешение для APK, прочитайте предупреждение системы; приложение не обходит его и не включает службу само.")
        button("2. Проверить интерфейс MAX (60 секунд)") {
            if (!MaxUiService.startProfileProbe()) { toast(MaxUiService.captureIssue.explanation); refresh(); return@button }
            val launch = packageManager.getLaunchIntentForPackage(MaxUiPolicy.PACKAGE)
            if (launch == null) toast("MAX не найден") else runCatching { startActivity(launch) }.onFailure { toast("Не удалось открыть MAX") }
        }
        ui.hint("Откройте саму переписку с полем сообщения — НЕ карточку контакта, как на экране с номером телефона. В шапке может быть номер или имя. Подождите 2–3 секунды и вернитесь сюда. Настройка сама записывает диагностический отчёт; отдельно запускать диагностику не нужно. Найденный образец доступен для сохранения 10 минут. При настройке профиля ничего не вводится и не отправляется. В проверочном сценарии после звонка служба может открыть поиск, ввести номер и перейти в результат — но не вводит текст сообщения и не нажимает отправку. Если номер или элементы недоступны службе, автоматизация для этого экрана не поддерживается.")
        button("3. Сохранить найденный профиль интерфейса") {
            MaxUiService.endProfileProbe()
            val c = MaxUiService.usableCandidate(this)
            if (c == null) {
                val reason = if (MaxUiService.candidate != null) fi.callshift.app.domain.MaxProfileCapture.Issue.EXPIRED.explanation else MaxUiService.captureIssue.explanation
                AlertDialog.Builder(this).setTitle("Почему профиль не сохранён").setMessage(reason + "\n\nОтчёт настройки уже записан. Нажмите «Посмотреть / скопировать отчёт» ниже. Для образца нужен экран переписки с полем сообщения, не карточка контакта.")
                    .setPositiveButton("Понятно", null).show()
                return@button
            }
            AlertDialog.Builder(this).setTitle("Проверка профиля")
                .setMessage("Шапка образца: ${c.phone}. Сохраняются только элементы интерфейса, не привязка человека. Если в шапке имя, при каждой попытке номер будет проверяться в карточке контакта. Сохранение выключит реальную отправку.")
                .setPositiveButton("Сохранить") { _, _ ->
                    runCatching {
                        check(MaxUiService.usableCandidate(this) == c) { "Образец изменился или устарел" }
                        store.profile(c.header, c.input, c.version); store.modes(true, false)
                    }.onSuccess { toast("Профиль сохранён. Включена только проверка без отправки.") }
                        .onFailure { toast("Не удалось сохранить") }
                    refresh()
                }.setNegativeButton("Отмена", null).show()
        }
        ui.hint("Проверка именованного контакта: открыть карточку → найти подпись «Номер телефона» и номер рядом → сверить +7/8 → вернуться в тот же чат. Если MAX пересоздал экран или номер не виден, сценарий остановится. До первого успешного проверочного звонка не включайте реальную отправку.")
        button("Включить поиск и проверку — БЕЗ отправки") {
            runCatching { store.modes(true, false) }.onFailure { toast("Ошибка сохранения") }; refresh()
        }
        button("Разрешить экспериментальную отправку") {
            if (MaxUiService.diagnostics.active()) { toast("Сначала завершите диагностику: во время записи отправка запрещена"); return@button }
            if (!MaxUiService.connected || store.header.isEmpty()) { toast("Сначала подключите службу и сохраните профиль"); return@button }
            AlertDialog.Builder(this).setTitle("Реальная отправка через интерфейс MAX")
                .setMessage("Сначала выполните проверочный звонок в режиме без отправки и проверьте журнал. При включении CallShift сможет вводить текст и нажимать «Отправить» в проверенном чате. Возможны ошибки интерфейса. Лимит — 5 попыток за последние 24 часа, ошибки не возвращают резерв. Доставка не подтверждается. После переподключения службы отправка снова выключится. Разрешить?")
                .setPositiveButton("Разрешить") { _, _ -> runCatching { store.modes(true, true) }.onFailure { toast("Ошибка сохранения") }; refresh() }
                .setNegativeButton("Отмена", null).show()
        }
        button("СТОП: выключить автоматизацию MAX") {
            runCatching { store.modes(false, false); MaxUiService.stopNow() }.onFailure { toast("Ошибка сохранения; отключите службу в Android") }; refresh()
        }
        ui.hint("В полном редакторе правила выберите отдельный канал «MAX — эксперимент UI». Старый «MAX — ручная отправка» не меняется. Не включайте одновременно ручной и экспериментальный MAX для одного ответа. Приложение ищет номер, если текущий чат не подходит. Нужны распознаваемые элементы поиска и карточки. После поиска +7/8 можно использовать имя из контактов Android (нужно разрешение «Контакты»). Однофамильцы проверяются по карточкам, максимум три кандидата за попытку; имя само по себе не подтверждает получателя. +7 и 8 не требуют разных настроек. Профиль хранит только элементы интерфейса, а не привязку номера к чату. Оставленный после ошибки черновик проверяйте вручную; автоматического повтора и запасной SMS нет.")
        ui.header("Диагностика MAX без переписки")
        ui.hint("Только по вашему запросу: этапы сценария, версия MAX и счётчики элементов. Без имён, номеров, текстов, ID элементов и скриншотов. Отчёт хранится только в памяти до закрытия процесса или очистки, автоматически никуда не отправляется. При запуске реальная отправка выключается и не включится обратно сама.")
        button("Записать интерфейс MAX — 60 секунд") {
            if (!MaxUiService.connected) { toast("Сначала подключите службу"); return@button }
            MaxUiService.collectDiagnostics(false)
            refresh()
            val launch = packageManager.getLaunchIntentForPackage(MaxUiPolicy.PACKAGE)
            if (launch == null) toast("MAX не найден") else runCatching { startActivity(launch) }.onFailure { toast("Откройте MAX вручную") }
        }
        ui.hint("Откройте MAX, затем поиск и нужный чат, вернитесь сюда. Это пассивное наблюдение — служба ничего не нажимает. Для него не нужен сохранённый профиль интерфейса.")
        button("Диагностика следующего звонка — 3 минуты") {
            if (!MaxUiService.connected) { toast("Сначала подключите службу"); return@button }
            MaxUiService.collectDiagnostics(true)
            refresh()
            toast("Реальная отправка выключена. Сделайте тестовый звонок с правилом MAX UI; номер может вводиться только в поиск.")
        }
        button("Посмотреть / скопировать отчёт") {
            val report = MaxUiService.diagnostics.report(android.os.Build.VERSION.SDK_INT, fi.callshift.app.BuildConfig.VERSION_NAME) +
                "\nService connected now=${MaxUiService.connected}; staged sample available=${MaxUiService.usableCandidate(this) != null}\n"
            val view = android.widget.TextView(this).apply { text = report; setPadding(24, 16, 24, 16); setTextIsSelectable(true) }
            val scroll = android.widget.ScrollView(this).apply { addView(view) }
            AlertDialog.Builder(this).setTitle("Диагностика MAX").setView(scroll)
                .setPositiveButton("Копировать") { _, _ ->
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("MAX diagnostics", report))
                    toast("Отчёт скопирован. Отправляйте только если согласны поделиться технической диагностикой.")
                }.setNegativeButton("Закрыть", null).show()
        }
        button("Очистить и закончить диагностику") { MaxUiService.diagnostics.clear(); toast("Отчёт очищен. Реальная отправка остаётся выключенной."); refresh() }
        setContentView(ui.scroll)
    }
    override fun onResume() {
        super.onResume()
        MaxUiService.endProfileProbe()
        if (::status.isInitialized) refresh()
    }
    private fun refresh() {
        val c = MaxUiService.usableCandidate(this)
        status.text = "Служба: ${if (MaxUiService.connected) "подключена" else "не подключена"}\nРежим: ${if (!store.enabled) "выключен" else if (store.live) "реальная отправка" else "проверка без отправки"}\nПрофиль MAX: ${store.version}\nПоследний образец: ${c?.phone ?: "нет"}\n${if (c != null) "Образец найден — нажмите «Сохранить»" else MaxUiService.captureIssue.explanation}"
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
