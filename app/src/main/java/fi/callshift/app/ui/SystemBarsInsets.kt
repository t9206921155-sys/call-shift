package fi.callshift.app.ui

import android.app.Activity
import android.app.Application
import android.os.Bundle

import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import fi.callshift.app.R

/**
 * Android 15 (targetSdk 35) рисует окно под строкой состояния и панелью навигации.
 * Для КАЖДОГО экрана приложения добавляем корневому View отступы по размеру системных
 * панелей, выреза камеры и клавиатуры — фон остаётся на весь экран, а кнопки и текст
 * не залезают под часы и кнопки навигации.
 */
object SystemBarsInsets : Application.ActivityLifecycleCallbacks {

    fun install(app: Application) = app.registerActivityLifecycleCallbacks(this)

    override fun onActivityStarted(activity: Activity) = apply(activity)

    fun apply(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = content.getChildAt(0) ?: return
        if (root.getTag(R.id.tag_insets_applied) == true) return
        root.setTag(R.id.tag_insets_applied, true)

        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        // Тёмный фон → светлые значки; светлая тема → тёмные. Экраны звонка всегда тёмные.
        val night = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val alwaysDark = activity is InCallActivity || activity is DialerActivity
        val lightBars = !night && !alwaysDark
        WindowCompat.getInsetsController(activity.window, root).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }

        val base = Padding(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                base.left + bars.left,
                base.top + bars.top,
                base.right + bars.right,
                base.bottom + maxOf(bars.bottom, ime.bottom),
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    private data class Padding(val left: Int, val top: Int, val right: Int, val bottom: Int)

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

