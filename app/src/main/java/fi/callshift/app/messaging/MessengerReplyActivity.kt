package fi.callshift.app.messaging

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import fi.callshift.app.domain.ManualReply
import fi.callshift.app.domain.ReplyChannel
import fi.callshift.app.ui.FormUi

/** Persistent review screen. Launching another app never means the message was sent. */
class MessengerReplyActivity : AppCompatActivity() {
    private lateinit var number: String
    private lateinit var message: String
    private lateinit var channel: String
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        number = intent.getStringExtra("number").orEmpty()
        message = intent.getStringExtra("text").orEmpty()
        channel = intent.getStringExtra("channel").orEmpty()
        if (!ManualReply.valid(channel, number, message)) { finish(); return }
        val ui = FormUi(this)
        ui.title("Ручной ответ")
        ui.header(ReplyChannel.labels[channel] ?: channel)
        ui.header("Получатель")
        ui.hint(number).setTextIsSelectable(true)
        ui.header("Сохранённый текст этой попытки")
        ui.hint(message).setTextIsSelectable(true)
        ui.hint(when (channel) {
            "WHATSAPP" -> "Открыть по номеру: попробуем перейти в чат с текстом. Если аккаунт не найден, скопируйте номер или выберите чат вручную."
            "TELEGRAM" -> "Открыть по номеру: Telegram может не найти человека из-за приватности или версии клиента. Тогда вернитесь сюда и выберите чат вручную. Результат поиска CallShift не получает."
            "MAX" -> "Надёжного выбора получателя по номеру здесь нет. MAX получит текст, но чат выбираете вы."
            else -> "Выберите приложение и получателя вручную."
        })
        ui.hint("Перед отправкой проверьте человека и переписку. Повторное открытие может привести к дублированию сообщения. Автоматической отправки и запасной SMS нет.")
        status = ui.hint(savedInstanceState?.getString("reply_status") ?: "Отправка не подтверждена. Этот экран лишь открывает мессенджер.")
        fun button(label: String, action: () -> Unit) = ui.add(MaterialButton(this).apply {
            text = label
            setOnClickListener { action() }
        })
        if (ManualReply.link(channel, number, message) != null) {
            button("Открыть чат по номеру") { open(manual = false) }
            button("Не нашёл человека — выбрать чат вручную") { open(manual = true) }
        } else button("Выбрать чат вручную") { open(manual = true) }
        button("Копировать номер") { copy("Получатель", number) }
        button("Копировать текст") { copy("Ответ", message) }
        button("Закрыть") { finish() }
        setContentView(ui.scroll)
    }

    private fun copy(label: String, value: String) {
        val clip = ClipData.newPlainText(label, value)
        // On supported Android versions suppress sensitive clipboard previews.
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, "$label скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun open(manual: Boolean) {
        try {
            val link = if (manual) null else ManualReply.link(channel, number, message)
            val target = if (link != null) Intent(Intent.ACTION_VIEW, Uri.parse(link)) else Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
            }
            val installed = ManualReply.packages(channel).firstOrNull { pkg ->
                Intent(target).setPackage(pkg).resolveActivity(packageManager) != null
            }
            if (installed != null) target.setPackage(installed)
            else if (manual && channel != "OTHER") {
                status.text = "Клиент $channel не найден или не принимает текст. Скопируйте номер и текст и откройте свой клиент вручную. Ничего не отправлено."
                return
            }
            startActivity(if (channel == "OTHER") Intent.createChooser(target, "Выберите приложение и получателя $number") else target)
            status.text = if (manual)
                "Открыт выбор чата. Получатель НЕ выбран автоматически. Отправка не подтверждена."
            else "Открыта ссылка на номер. Выбор получателя и отправка не подтверждены. Если не получилось, используйте ручной выбор чата."
            // Keep this screen on the back stack for return, copy and explicit fallback.
        } catch (_: android.content.ActivityNotFoundException) {
            status.text = "Нет приложения для этого действия. Можно скопировать номер и текст. Ничего не отправлено."
        } catch (_: SecurityException) {
            status.text = "Android запретил открытие приложения. Можно скопировать номер и текст. Ничего не отправлено."
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::status.isInitialized) outState.putString("reply_status", status.text.toString())
        super.onSaveInstanceState(outState)
    }
}
