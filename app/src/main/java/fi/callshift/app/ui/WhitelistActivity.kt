package fi.callshift.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import fi.callshift.app.CallShiftApp
import fi.callshift.app.R

/** Белый список: номера, которые никогда не сбрасываются (ни правилами, ни автоответчиком). */
class WhitelistActivity : AppCompatActivity() {

    private val app by lazy { CallShiftApp.from(this) }
    private lateinit var ui: FormUi
    private lateinit var list: LinearLayout
    private lateinit var etNumber: EditText

    private val pickContact = registerForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        uri ?: return@registerForActivityResult
        val id = contentResolver.query(uri, arrayOf(ContactsContract.Contacts._ID), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return@registerForActivityResult
        val numbers = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?", arrayOf(id), null,
        )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.orEmpty()
        if (numbers.isEmpty()) toast("У контакта нет номеров") else numbers.forEach { add(it) }
    }

    private val askContacts = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pickContact.launch(null) else toast("Нужен доступ к контактам")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Белый список"
        ui = FormUi(this)
        ui.title("Белый список")
        ui.hint("Звонки с этих номеров всегда проходят как обычно — их не сбросит ни правило, ни автоответчик. " +
            "Подходит для близких, школы, врача. Можно указать шаблон, например +358401*.")

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        etNumber = EditText(this).apply {
            hint = "Номер телефона"; inputType = android.text.InputType.TYPE_CLASS_PHONE
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_muted))
        }
        row.addView(etNumber, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialButton(this).apply {
            text = "Добавить"
            setOnClickListener { add(etNumber.text.toString()); etNumber.setText("") }
        })
        ui.add(row)
        ui.add(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Выбрать из контактов"
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(this@WhitelistActivity, Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED) pickContact.launch(null)
                else askContacts.launch(Manifest.permission.READ_CONTACTS)
            }
        })
        ui.header("Номера")
        list = ui.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL })
        setContentView(ui.scroll)
        render()
    }

    private fun add(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val value = if (t.contains('*')) t.replace(" ", "")
        else app.normalizer.normalize(t).e164 ?: t.filter { it.isDigit() || it == '+' }
        if (value.isEmpty()) { toast("Некорректный номер"); return }
        app.settings.setWhitelist(app.settings.whitelist + value)
        render()
    }

    private fun render() {
        list.removeAllViews()
        val items = app.settings.whitelist.sorted()
        if (items.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Пока пусто"; setTextColor(ContextCompat.getColor(context, R.color.text_muted))
            })
            return
        }
        items.forEach { n ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(ContextCompat.getColor(context, R.color.surface_card))
                val p = ui.dp(12); setPadding(p, ui.dp(4), ui.dp(4), ui.dp(4))
            }
            row.addView(TextView(this).apply {
                text = n; textSize = 17f; setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))
                background = null; contentDescription = "Удалить $n"
                setOnClickListener { app.settings.setWhitelist(app.settings.whitelist - n); render() }
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            list.addView(row, ui.lp(bottom = 6))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
