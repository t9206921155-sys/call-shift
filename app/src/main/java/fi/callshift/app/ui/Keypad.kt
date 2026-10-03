package fi.callshift.app.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.HapticFeedbackConstants
import android.widget.ImageButton
import android.widget.TextView

/** Общие элементы клавиатуры и круглых кнопок для экранов «Телефон» и «Звонок». */
object Keypad {

    private val LETTERS = mapOf(
        '1' to " ", '2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL",
        '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+", '*' to "", '#' to "",
    )

    private val TONES = mapOf(
        '1' to ToneGenerator.TONE_DTMF_1, '2' to ToneGenerator.TONE_DTMF_2, '3' to ToneGenerator.TONE_DTMF_3,
        '4' to ToneGenerator.TONE_DTMF_4, '5' to ToneGenerator.TONE_DTMF_5, '6' to ToneGenerator.TONE_DTMF_6,
        '7' to ToneGenerator.TONE_DTMF_7, '8' to ToneGenerator.TONE_DTMF_8, '9' to ToneGenerator.TONE_DTMF_9,
        '0' to ToneGenerator.TONE_DTMF_0, '*' to ToneGenerator.TONE_DTMF_S, '#' to ToneGenerator.TONE_DTMF_P,
    )

    /** Цифра крупно + буквы мелко под ней. */
    fun label(digit: Char): CharSequence {
        val letters = LETTERS[digit].orEmpty()
        val sb = SpannableStringBuilder(digit.toString())
        if (letters.isNotBlank()) {
            val start = sb.length
            sb.append("\n").append(letters)
            sb.setSpan(RelativeSizeSpan(0.38f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(Color.parseColor("#A9C4B6")), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** Настроить 12 клавиш: подписи, вибрация, обработчик. */
    fun bind(keys: List<Pair<TextView, Char>>, onKey: (Char) -> Unit) {
        keys.forEach { (view, digit) ->
            view.text = label(digit)
            view.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onKey(digit)
            }
        }
    }

    /** Локальный звук нажатия клавиши (в «Телефоне», пока нет разговора). */
    class TonePlayer {
        private val gen: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_DTMF, 70) }.getOrNull()
        fun play(digit: Char) { TONES[digit]?.let { gen?.startTone(it, 120) } }
        fun release() { runCatching { gen?.release() } }
    }

    /** Состояние круглой кнопки-переключателя: включено — белый круг с тёмной иконкой. */
    fun setToggle(button: ImageButton, on: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(if (on) Color.WHITE else Color.parseColor("#26FFFFFF"))
        button.imageTintList = ColorStateList.valueOf(if (on) Color.parseColor("#14281E") else Color.WHITE)
        button.isSelected = on
    }

    /** Инициалы для аватара: «Иван Петров» → «ИП». null — если имени нет. */
    fun initials(name: String?): String? {
        val words = name?.trim()?.split(Regex("\\s+"))?.filter { w -> w.firstOrNull()?.isLetter() == true }.orEmpty()
        if (words.isEmpty()) return null
        return words.take(2).joinToString("") { it.first().uppercase() }
    }
}
