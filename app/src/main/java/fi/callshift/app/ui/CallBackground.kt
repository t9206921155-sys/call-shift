package fi.callshift.app.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.View
import java.io.File

/** Обои экранов набора и звонка: готовые градиенты или своя картинка из галереи. */
object CallBackground {
    data class Preset(val key: String, val title: String, val colors: IntArray)

    val presets = listOf(
        Preset("emerald", "Изумруд (стандарт)", intArrayOf(0xFF1F4A35.toInt(), 0xFF12271C.toInt(), 0xFF0A140F.toInt())),
        Preset("night", "Ночь", intArrayOf(0xFF1A2340.toInt(), 0xFF0E1426.toInt(), 0xFF05070F.toInt())),
        Preset("sunset", "Закат", intArrayOf(0xFF17323A.toInt(), 0xFF2A2A2E.toInt(), 0xFF3A2416.toInt())),
        Preset("ocean", "Океан", intArrayOf(0xFF0D4C63.toInt(), 0xFF0A2E3F.toInt(), 0xFF061820.toInt())),
        Preset("graphite", "Графит", intArrayOf(0xFF3A3D42.toInt(), 0xFF222428.toInt(), 0xFF111214.toInt())),
        Preset("berry", "Ягода", intArrayOf(0xFF4A1F3D.toInt(), 0xFF2A1226.toInt(), 0xFF120810.toInt())),
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("call_bg", Context.MODE_PRIVATE)
    private fun file(ctx: Context) = File(ctx.filesDir, "call_wallpaper.jpg")

    fun current(ctx: Context): String = prefs(ctx).getString("mode", "emerald") ?: "emerald"
    fun dim(ctx: Context): Int = prefs(ctx).getInt("dim", 45)

    fun setPreset(ctx: Context, key: String) = prefs(ctx).edit().putString("mode", key).apply()
    fun setDim(ctx: Context, percent: Int) = prefs(ctx).edit().putInt("dim", percent).apply()

    /** Сохраняет картинку, обрезанную под пропорции экрана (без искажений). */
    fun setCustom(ctx: Context, uri: Uri): Boolean = runCatching {
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtMost(1440)
        val h = (w.toLong() * dm.heightPixels / dm.widthPixels).toInt()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val src = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val cw = (w / scale).toInt().coerceAtMost(src.width)
        val ch = (h / scale).toInt().coerceAtMost(src.height)
        val cropped = Bitmap.createBitmap(src, (src.width - cw) / 2, (src.height - ch) / 2, cw, ch)
        val out = Bitmap.createScaledBitmap(cropped, w, h, true)
        file(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        prefs(ctx).edit().putString("mode", "custom").apply()
        true
    }.getOrDefault(false)

    fun drawable(ctx: Context): Drawable {
        val mode = current(ctx)
        if (mode == "custom") {
            val f = file(ctx)
            if (f.exists()) {
                BitmapFactory.decodeFile(f.absolutePath)?.let { bmp ->
                    val alpha = (dim(ctx) * 255 / 100).coerceIn(0, 230)
                    return LayerDrawable(arrayOf(BitmapDrawable(ctx.resources, bmp), ColorDrawable(alpha shl 24)))
                }
            }
        }
        val p = presets.firstOrNull { it.key == mode } ?: presets.first()
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, p.colors)
    }

    fun apply(activity: Activity, root: View) {
        root.background = drawable(activity)
    }
}
