package fi.callshift.app.ui

import android.content.Context
import android.widget.ArrayAdapter
import fi.callshift.app.R

/** Адаптер для Spinner со светлым текстом на тёмном фоне (и в свёрнутом, и в раскрытом виде). */
fun darkSpinnerAdapter(context: Context, items: List<String>): ArrayAdapter<String> =
    ArrayAdapter(context, R.layout.spinner_item, items).apply {
        setDropDownViewResource(R.layout.spinner_dropdown_item)
    }
