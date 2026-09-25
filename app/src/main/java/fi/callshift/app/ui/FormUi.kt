package fi.callshift.app.ui

import android.app.Activity
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import fi.callshift.app.R

/** Мини-конструктор простых экранов настроек в стиле приложения. */
class FormUi(private val a: Activity) {
    val root = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(16); setPadding(p, p, p, p)
    }
    val scroll = ScrollView(a).apply {
        setBackgroundColor(ContextCompat.getColor(a, R.color.brand_primary))
        addView(root)
    }

    fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), a.resources.displayMetrics).toInt()

    fun lp(top: Int = 0, bottom: Int = 8) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    fun title(text: String) = TextView(a).apply {
        this.text = text; textSize = 22f
        setTextColor(ContextCompat.getColor(a, R.color.text_primary))
        paint.isFakeBoldText = true
        root.addView(this, lp(bottom = 12))
    }

    fun header(text: String) = TextView(a).apply {
        this.text = text; textSize = 16f
        setTextColor(ContextCompat.getColor(a, R.color.brand_accent))
        paint.isFakeBoldText = true
        root.addView(this, lp(top = 12, bottom = 4))
    }

    fun hint(text: String) = TextView(a).apply {
        this.text = text; textSize = 14f
        setTextColor(ContextCompat.getColor(a, R.color.text_secondary))
        root.addView(this, lp(bottom = 6))
    }

    fun <T : android.view.View> add(v: T, top: Int = 0, bottom: Int = 8): T { root.addView(v, lp(top, bottom)); return v }
}
