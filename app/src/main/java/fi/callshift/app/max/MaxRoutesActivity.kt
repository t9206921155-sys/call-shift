package fi.callshift.app.max

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.MaxRoutePolicy
import fi.callshift.app.domain.MaxUiPolicy
import fi.callshift.app.ui.FormUi

class MaxRoutesActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    override fun onResume() { super.onResume(); MaxUiService.endPickerProbe(); render() }
    private fun render() {
        val ui = FormUi(this)
        val app = CallShiftApp.from(this)
        val store = MaxRouteStore(this)
        ui.title("MAX: аккаунт отправителя по SIM")
        ui.hint("SIM берётся из звонка и вашего правила. Здесь один раз укажите, какой MAX соответствует каждой SIM. Контакты к чатам не привязываются. После изменения маршрута реальная отправка выключается; сначала нужен успешный проверочный звонок для этой SIM.")
        ui.hint("Универсальный режим изучает системное окно выбора, а не марку телефона. Поддерживаются две однозначные текстовые строки «MAX» и «MAX (…)». Нужны доступные Android элементы и кликабельные строки. Иконки без подписей, несколько одинаковых подписей, сторонние клоны и скрытые профили не поддерживаются. Автоматического обхода ограничений Android нет.")
        ui.add(MaterialButton(this).apply {
            text = "1. Изучить окно выбора двух MAX"
            setOnClickListener {
                AlertDialog.Builder(this@MaxRoutesActivity).setTitle("Чтение системного окна выбора")
                    .setMessage("CallShift на 60 секунд прочитает системное окно выбора MAX. Сохраняются локально пакет/версия окна и подписи двух вариантов. Ничего не нажимается и не отправляется. Автоматически записываются только коды причин и счётчики элементов — без текстов, имён и номеров. Когда окно появится, НЕ выбирайте MAX: подождите 2–3 секунды, нажмите «Отмена» или «Назад» и вернитесь сюда. Если сразу откроется чат, автоматический выбор клона не настроится: требуется окно выбора при каждом запуске, без приложения по умолчанию.")
                    .setPositiveButton("Начать") { _, _ ->
                        if (!MaxUiService.startPickerProbe()) { message("Подключите службу CallShift — MAX в специальных возможностях"); return@setPositiveButton }
                        val launch = packageManager.getLaunchIntentForPackage(MaxUiPolicy.PACKAGE)
                        if (launch == null) message("MAX не установлен") else runCatching { startActivity(launch) }.onFailure { message("Android не разрешил открыть MAX") }
                    }.setNegativeButton("Отмена", null).show()
            }
        })
        ui.add(MaterialButton(this).apply {
            text = "Показать / скопировать отчёт распознавания"
            setOnClickListener {
                val report = MaxUiService.report(this@MaxRoutesActivity) +
                    "\nService connected now=${MaxUiService.connected}; picker learned=${MaxUiService.pickerCandidate != null}\n"
                val textView = android.widget.TextView(this@MaxRoutesActivity).apply {
                    text = report; setPadding(24, 16, 24, 16); setTextIsSelectable(true)
                }
                val scroll = android.widget.ScrollView(this@MaxRoutesActivity).apply { addView(textView) }
                AlertDialog.Builder(this@MaxRoutesActivity).setTitle("Распознавание окна MAX").setView(scroll)
                    .setPositiveButton("Копировать") { _, _ ->
                        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(
                            android.content.ClipData.newPlainText("MAX picker diagnostics", report))
                    }.setNegativeButton("Закрыть", null).show()
            }
        })
        val learned = MaxUiService.pickerCandidate
        ui.hint(if (learned == null) MaxUiService.pickerStatus else "Окно распознано: ${learned.labels.joinToString(" / ")}. Теперь выберите вариант для каждой SIM ниже. Образец хранится в памяти до закрытия процесса.")
        val accounts = app.telecom.phoneAccounts()
        if (accounts.isEmpty()) ui.hint("SIM недоступны. Проверьте разрешение «Телефон» и наличие активной SIM. По названию оператора или порядку слотов не угадываем.")
        accounts.forEach { (id, label) ->
            val route = store.routes()[id]
            ui.header("SIM: $label")
            ui.hint(if (route == null) "Маршрут не настроен — отправка заблокирована" else
                "MAX: ${route.label ?: "единственный аккаунт, без клона"}. Проверочный звонок: ${if (store.tested(id, route)) "пройден" else "нужен"}")
            ui.add(MaterialButton(this).apply {
                text = "Выбрать аккаунт MAX для этой SIM"
                setOnClickListener {
                    val options = listOf("Отключить для этой SIM", "У меня только один MAX, без клона") + learned?.labels.orEmpty()
                    AlertDialog.Builder(this@MaxRoutesActivity).setTitle("SIM: $label").setItems(options.toTypedArray()) { _, index ->
                        val selected = when (index) { 0 -> null; 1 -> MaxRoutePolicy.Route(); else -> MaxRoutePolicy.Route(learned, learned!!.labels[index - 2]) }
                        fun save() {
                            MaxUiService.stopNow()
                            runCatching { store.put(id, selected) }.onFailure { message("Маршрут не сохранён") }
                            render()
                        }
                        AlertDialog.Builder(this@MaxRoutesActivity).setTitle("Подтвердите аккаунт отправителя")
                            .setMessage(if (index == 1) "Этот режим только для единственного аккаунта MAX. При наличии клона его использовать нельзя: Android может открыть другой аккаунт по умолчанию. Вы подтверждаете отсутствие второго MAX?" else "Для SIM $label использовать «${options[index]}»? Связь SIM с регистрационным номером аккаунта MAX Android не сообщает: проверьте соответствие самостоятельно. Изменение выключит реальную отправку.")
                            .setPositiveButton("Подтвердить") { _, _ -> save() }.setNegativeButton("Отмена", null).show()
                    }.show()
                }
            })
        }
        ui.hint("Далее вернитесь в «MAX: эксперимент»: сохраните профиль интерфейса, если его ещё нет → «Диагностика следующего звонка» → тестовый звонок на нужную SIM. В режиме с клоном выбор сделает CallShift; самостоятельно не нажимайте варианты. После успешной проверки убедитесь, что открыт нужный аккаунт. Только затем разрешайте отправку. Неподдерживаемое окно останется без нажатий.")
        setContentView(ui.scroll)
    }
    private fun message(text: String) { AlertDialog.Builder(this).setMessage(text).setPositiveButton("Понятно", null).show() }
}
