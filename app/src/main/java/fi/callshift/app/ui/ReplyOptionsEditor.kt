package fi.callshift.app.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.google.android.material.checkbox.MaterialCheckBox
import fi.callshift.app.R
import fi.callshift.app.domain.ReplyChannel
import fi.callshift.app.domain.ReplyOptions

/** Same channel/interval control in rule editor and global auto-reply. */
class ReplyOptionsEditor(context: Context, parent: LinearLayout) {
    private val checks = linkedMapOf<String, MaterialCheckBox>()
    private val interval = Spinner(context)
    private var minutes = ReplyOptions.intervals.keys.toList()
    init {
        fun hint(text: String) = parent.addView(TextView(context).apply {
            this.text = text; setTextColor(context.getColor(R.color.text_secondary)); textSize = 14f
        })
        hint("Каналы ответа — можно выбрать несколько. Ответ готовится во ВСЕХ отмеченных каналах, не только в одном запасном.")
        ReplyChannel.labels.forEach { (key, label) ->
            val check = MaterialCheckBox(context).apply {
                text = label; setTextColor(context.getColor(R.color.text_primary))
            }
            checks[key] = check
            parent.addView(check, LinearLayout.LayoutParams(-1, -2))
        }
        hint("Повторный ответ тому же номеру — отдельно для каждого канала:")
        interval.adapter = darkSpinnerAdapter(context, ReplyOptions.intervals.values.toList())
        parent.addView(interval, LinearLayout.LayoutParams(-1, -2))
        hint("Это пауза между ответами, а не отложенная отправка. Без НОВОГО звонка ничего повторно не отправляется. Лимит общий для правил и SIM, но отдельный для каждого канала. Ошибки с неизвестным результатом тоже могут учитывать попытку.\n«На каждый звонок» может привести к нескольким платным SMS. Отправка в мессенджеры с пометкой РУЧНАЯ требует ваших действий. Настройка не отключает исключение «Повторный звонок — пропустить».")
        set("SMS", null, 30)
    }
    fun set(legacy: String, selected: List<String>?, cooldownMinutes: Int) {
        val chosen = ReplyOptions.channels(legacy, selected)
        checks.forEach { (key, view) -> view.isChecked = key in chosen }
        if (cooldownMinutes !in minutes) {
            minutes = minutes + cooldownMinutes.coerceIn(0, 1440)
            interval.adapter = darkSpinnerAdapter(interval.context, minutes.map(ReplyOptions::intervalLabel))
        }
        interval.setSelection(minutes.indexOf(cooldownMinutes.coerceIn(0, 1440)).coerceAtLeast(0))
    }
    fun save(state: android.os.Bundle) {
        state.putStringArrayList("reply_editor_channels", ArrayList(channels()))
        state.putInt("reply_editor_minutes", cooldownMinutes())
    }
    fun restore(state: android.os.Bundle?) {
        val channels = state?.getStringArrayList("reply_editor_channels") ?: return
        set("SMS", channels, state.getInt("reply_editor_minutes", 30))
    }
    fun channels(): List<String> = checks.filterValues { it.isChecked }.keys.toList()
    fun cooldownMinutes(): Int = minutes.getOrElse(interval.selectedItemPosition) { 30 }
}
