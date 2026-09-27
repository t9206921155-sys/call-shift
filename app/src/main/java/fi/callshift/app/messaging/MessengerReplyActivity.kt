package fi.callshift.app.messaging

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import fi.callshift.app.domain.ManualReply

/** Only opens a draft after confirmation; no delivery inference or paid fallback. */
class MessengerReplyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra("number").orEmpty()
        val text = intent.getStringExtra("text").orEmpty()
        val channel = intent.getStringExtra("channel").orEmpty()
        if (!ManualReply.valid(channel, number, text)) { finish(); return }
        val instruction = when (channel) {
            "WHATSAPP" -> "Откроется чат по номеру с текстом. Аккаунт должен существовать. Проверьте получателя и нажмите «Отправить»."
            "TELEGRAM" -> "Попробуем открыть чат по номеру с текстом. Telegram может не найти человека из-за приватности или отсутствия аккаунта. Проверьте получателя; CallShift не получает результат поиска и отправки."
            "MAX" -> "Откроется MAX с текстом. Получателя нужно выбрать вручную: надёжного выбора по номеру здесь нет."
            else -> "Выберите приложение и получателя вручную."
        }
        val dialog = AlertDialog.Builder(this).setTitle("Ручной ответ: $number")
            .setMessage("$text\n\n$instruction\n\nСообщение ещё не отправлено. Повторное открытие может создать дубль — проверьте переписку.")
            .setPositiveButton("Открыть", null)
            .setNeutralButton("Копировать текст", null)
            .setNegativeButton("Закрыть") { _, _ -> finish() }
            .setOnCancelListener { finish() }.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Ответ", text))
                Toast.makeText(this, "Текст скопирован. Номер получателя: $number", Toast.LENGTH_LONG).show()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val link = ManualReply.link(channel, number, text)
                val target = if (link != null) Intent(Intent.ACTION_VIEW, Uri.parse(link)) else Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                }
                val packages = when (channel) {
                    "WHATSAPP" -> listOf("com.whatsapp")
                    "TELEGRAM" -> listOf("org.telegram.messenger", "org.telegram.messenger.web")
                    "MAX" -> listOf("ru.oneme.app")
                    else -> emptyList()
                }
                val installed = packages.firstOrNull { pkg ->
                    Intent(target).setPackage(pkg).resolveActivity(packageManager) != null
                }
                if (installed != null) target.setPackage(installed)
                else if (channel == "MAX") {
                    Toast.makeText(this, "MAX не установлен или не принимает текст. Скопируйте ответ и откройте приложение вручную.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                try {
                    startActivity(if (channel == "OTHER") Intent.createChooser(target, "Выберите приложение и получателя $number") else target)
                    // No SENT state: app launch is not a message receipt.
                    finish()
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, "Подходящее приложение не найдено. Можно скопировать текст.", Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    Toast.makeText(this, "Android запретил открытие приложения. Сообщение не отправлено.", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }
}
