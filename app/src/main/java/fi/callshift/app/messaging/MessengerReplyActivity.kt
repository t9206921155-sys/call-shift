package fi.callshift.app.messaging

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import fi.callshift.app.domain.ReplyChannel

/** Explicit user action opens the destination. No background launch or UI automation. */
class MessengerReplyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent.getStringExtra("number").orEmpty()
        val text = intent.getStringExtra("text").orEmpty()
        val channel = intent.getStringExtra("channel").orEmpty()
        if (!ReplyChannel.isPhoneAddress(number) || text.isBlank() ||
            !ReplyChannel.isManual(channel)) {
            finish(); return
        }
        val instruction = if (channel == ReplyChannel.WHATSAPP)
            "Откроется чат по номеру. Проверьте получателя и нажмите отправку в WhatsApp. Наличие аккаунта не проверено."
        else "Выберите нужный мессенджер и чат именно этого звонящего. По номеру автоматически найти получателя нельзя. Проверьте его перед отправкой."
        AlertDialog.Builder(this)
            .setTitle("Ответ звонящему: $number")
            .setMessage("$text\n\n$instruction\n\nСообщение ещё не отправлено.")
            .setPositiveButton("Открыть") { _, _ ->
                try {
                    val target = if (channel == ReplyChannel.WHATSAPP) {
                        val uri = Uri.Builder().scheme("https").authority("wa.me")
                            .appendPath(number.removePrefix("+"))
                            .appendQueryParameter("text", text).build()
                        Intent(Intent.ACTION_VIEW, uri)
                    } else Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }, "${ReplyChannel.labels[channel]} · получатель $number")
                    startActivity(target)
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(this, "Нет приложения для отправки. Сообщение не отправлено.", Toast.LENGTH_LONG).show()
                } catch (_: SecurityException) {
                    Toast.makeText(this, "Android запретил открытие приложения. Сообщение не отправлено.", Toast.LENGTH_LONG).show()
                }
                finish()
            }
            .setNegativeButton("Отмена") { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }
}
