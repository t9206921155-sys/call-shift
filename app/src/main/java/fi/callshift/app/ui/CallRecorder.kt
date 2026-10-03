package fi.callshift.app.ui

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись разговора. Android не даёт сторонним приложениям прямой доступ к линии
 * (источник VOICE_CALL — только системным), поэтому пишем с микрофона: ваш голос
 * слышно всегда, собеседника — хорошо при включённом динамике.
 */
object CallRecorder {
    private var recorder: MediaRecorder? = null
    var file: File? = null; private set
    var startedAt: Long = 0L; private set
    val isRecording get() = recorder != null

    fun dir(ctx: Context): File =
        File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir, "CallShift").apply { mkdirs() }

    fun start(ctx: Context, number: String?): Result<File> = runCatching {
        stop()
        val safe = (number ?: "hidden").filter { it.isDigit() || it == '+' }.ifEmpty { "hidden" }
        val f = File(dir(ctx), SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date()) + "_" + safe + ".m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            // VOICE_RECOGNITION даёт чистый сигнал без шумодава; при ошибке — обычный MIC.
            runCatching { r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION) }
                .onFailure { r.setAudioSource(MediaRecorder.AudioSource.MIC) }
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (t: Throwable) {
            runCatching { r.release() }
            f.delete()
            throw t
        }
        recorder = r; file = f; startedAt = System.currentTimeMillis()
        f
    }

    /** Останавливает запись; возвращает файл или null. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        runCatching { r.release() }
        val f = file
        if (!ok) f?.delete()
        return f?.takeIf { ok && it.exists() }
    }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles { f -> f.extension == "m4a" }?.sortedByDescending { it.lastModified() }.orEmpty()
}
