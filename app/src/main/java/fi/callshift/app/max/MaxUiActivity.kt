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
        ui.hint("Это управление интерфейсом, НЕ API MAX. Работает только в уже открытом чате, если в шапке явно виден телефон звонящего. По имени, списку контактов или координатам приложение не выбирает человека. Заблокированный экран, другой чат или изменённый интерфейс — остановка.")
        ui.hint("Служба получает доступ к содержимому экрана MAX и может вводить текст и нажимать кнопку отправки. Содержимое других приложений не обрабатывается, снимки интерфейса не загружаются на сервер. Краткий образец шапки используется локально в памяти при настройке. Разрешение выдаёте только вы в настройках Android.")
        status = ui.hint("")
        fun button(label: String, action: () -> Unit) = ui.add(MaterialButton(this).apply { text = label; setOnClickListener { action() } })
        button("1. Открыть специальные возможности") {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.onFailure { toast("Откройте настройки Android вручную") }
        }
        ui.hint("Найдите CallShift — MAX (эксперимент). Если Android блокирует разрешение для APK, прочитайте предупреждение системы; приложение не обходит его и не включает службу само.")
        button("2. Проверить интерфейс MAX (60 секунд)") {
            if (!MaxUiService.connected) { toast("Сначала подключите службу"); return@button }
            MaxUiService.candidate = null
            MaxUiService.probeUntil = SystemClock.elapsedRealtime() + 60_000
            val launch = packageManager.getLaunchIntentForPackage(MaxUiPolicy.PACKAGE)
            if (launch == null) toast("MAX не найден") else runCatching { startActivity(launch) }.onFailure { toast("Не удалось открыть MAX") }
        }
        ui.hint("Откройте личный чат, в шапке которого показан именно номер телефона, а не имя. Затем вернитесь сюда. Во время проверки ничего не вводится и не отправляется. Если номер или элементы недоступны службе, автоматизация для этого экрана не поддерживается.")
        button("3. Сохранить найденный профиль интерфейса") {
            val c = MaxUiService.candidate
            if (c == null || SystemClock.elapsedRealtime() - c.at > 60_000) { toast("Подходящий экран не найден. Не будем угадывать элементы — нужна проверка интерфейса этой версии MAX."); return@button }
            AlertDialog.Builder(this).setTitle("Проверка профиля")
                .setMessage("На образце распознан номер ${c.phone}. Это номер в шапке нужного личного чата? При каждой попытке он будет заново сравниваться с номером звонящего. Сохранение выключит реальную отправку.")
                .setPositiveButton("Сохранить") { _, _ ->
                    runCatching { store.profile(c.header, c.input, c.version); store.modes(true, false) }
                        .onFailure { toast("Не удалось сохранить") }
                    refresh()
                }.setNegativeButton("Отмена", null).show()
        }
        button("Включить только проверку — БЕЗ отправки") {
            runCatching { store.modes(true, false) }.onFailure { toast("Ошибка сохранения") }; refresh()
        }
        button("Разрешить экспериментальную отправку") {
            if (!MaxUiService.connected || store.header.isEmpty()) { toast("Сначала подключите службу и сохраните профиль"); return@button }
            AlertDialog.Builder(this).setTitle("Реальная отправка через интерфейс MAX")
                .setMessage("Сначала выполните проверочный звонок в режиме без отправки и проверьте журнал. При включении CallShift сможет вводить текст и нажимать «Отправить» в проверенном чате. Возможны ошибки интерфейса. Лимит — 5 попыток за последние 24 часа, ошибки не возвращают резерв. Доставка не подтверждается. После переподключения службы отправка снова выключится. Разрешить?")
                .setPositiveButton("Разрешить") { _, _ -> runCatching { store.modes(true, true) }.onFailure { toast("Ошибка сохранения") }; refresh() }
                .setNegativeButton("Отмена", null).show()
        }
        button("СТОП: выключить автоматизацию MAX") {
            runCatching { store.modes(false, false); MaxUiService.stopNow() }.onFailure { toast("Ошибка сохранения; отключите службу в Android") }; refresh()
        }
        ui.hint("В полном редакторе правила выберите отдельный канал «MAX — эксперимент UI». Старый «MAX — ручная отправка» не меняется. Не включайте одновременно ручной и экспериментальный MAX для одного ответа. Приложение открывает MAX, но НЕ ищет и НЕ переключает чаты. Если чат неверный, попытка блокируется. Оставленный после ошибки черновик проверяйте вручную; автоматического повтора и запасной SMS нет.")
        setContentView(ui.scroll)
    }
    override fun onResume() { super.onResume(); if (::status.isInitialized) refresh() }
    private fun refresh() {
        val c = MaxUiService.candidate?.takeIf { SystemClock.elapsedRealtime() - it.at <= 60_000 }
        status.text = "Служба: ${if (MaxUiService.connected) "подключена" else "не подключена"}\nРежим: ${if (!store.enabled) "выключен" else if (store.live) "реальная отправка" else "проверка без действий"}\nПрофиль MAX: ${store.version}\nПоследний образец: ${c?.phone ?: "подходящий экран не найден"}"
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
