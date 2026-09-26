package fi.callshift.app.ui

import android.telephony.SmsMessage
import android.widget.EditText
import android.widget.TextView
import androidx.core.widget.addTextChangedListener

object SmsParts {
    fun count(text: String): Int = if (text.isEmpty()) 0 else SmsMessage.calculateLength(text, false)[0]
    fun attach(input: EditText, output: TextView) {
        fun update() {
            val parts = runCatching { count(input.text.toString()) }.getOrNull()
            output.text = parts?.let { "SMS-частей: $it. Оператор может тарифицировать каждую часть отдельно. Суточный лимит считает части, не сообщения." }
                ?: "Количество частей пока не определено; при отправке оно будет рассчитано для выбранной SIM."
        }
        input.addTextChangedListener { update() }; update()
    }
}
