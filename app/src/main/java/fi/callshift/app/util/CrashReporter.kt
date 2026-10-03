package fi.callshift.app.util

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Сохраняет текст последнего падения в файл; MainActivity показывает его при следующем запуске,
 * чтобы пользователь мог скопировать и прислать ошибку без adb.
 */
object CrashReporter {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val version = runCatching {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName
                }.getOrNull()
                File(app.filesDir, FILE).writeText(
                    "CallShift $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
                        "Поток: ${thread.name}\n\n$sw",
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Вернуть и удалить отчёт о последнем падении (или null). */
    fun takeLastCrash(context: Context): String? {
        val f = File(context.applicationContext.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
