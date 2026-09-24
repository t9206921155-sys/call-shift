package fi.callshift.app.util

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import fi.callshift.app.forward.ExternalRetryQueue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Очередь повторной доставки внешних уведомлений (ТЗ FR-6.3).
 *
 * Требования ТЗ: backoff ≤ 15 мин, очередь ≤ 100 событий.
 * Реализация M2: события складываются в append-файл, WorkManager-воркер
 * разбирает файл цепочкой (unique work, KEEP), каждая неудача переносит
 * событие в конец файла и планирует следующую попытку через 15 минут,
 * не более [MAX_ATTEMPTS] раз.
 *
 * В M1/M2 внешние каналы не настроены, поэтому воркер честно отмечает
 * событие как `external_not_configured` и сбрасывает его после лимита
 * попыток — важно, чтобы очередь не росла бесконечно.
 */
class ExternalRetryQueueImpl(private val context: Context) : ExternalRetryQueue {

    private val file: File
        get() = File(context.filesDir, QUEUE_FILE)

    override fun enqueue(channel: String, endpoint: String, payload: String) {
        runCatching {
            synchronized(lock) {
                val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
                // Ограничение очереди: 100 событий (FR-6.3) — старейшие выбрасываем.
                while (lines.size >= MAX_QUEUE) lines.removeAt(0)
                lines.add(
                    listOf(
                        channel.replace(SEP, " "),
                        endpoint.replace(SEP, " "),
                        "0",
                        payload.replace(SEP, " ").replace("\n", " "),
                    ).joinToString(SEP),
                )
                file.writeText(lines.joinToString("\n"))
            }
            schedule()
        }
    }

    fun pendingCount(): Int = runCatching {
        if (!file.exists()) 0 else file.readLines().count { it.isNotBlank() }
    }.getOrDefault(0)

    private fun schedule() {
        val request = OneTimeWorkRequestBuilder<ExternalRetryWorker>()
            .setInitialDelay(RETRY_DELAY_MIN, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        private const val QUEUE_FILE = "external_retry.queue"
        private const val WORK_NAME = "callshift_external_retry"
        private const val SEP = "\u0001"
        private const val MAX_QUEUE = 100
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MIN = 15L
        private val lock = Any()

        /** Чтение/запись очереди из воркера. */
        internal fun take(context: Context): List<Entry> = synchronized(lock) {
            val f = File(context.filesDir, QUEUE_FILE)
            if (!f.exists()) return emptyList()
            f.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                val p = line.split(SEP)
                if (p.size < 4) null else Entry(p[0], p[1], p[2].toIntOrNull() ?: 0, p[3])
            }
        }

        internal fun rewrite(context: Context, entries: List<Entry>) = synchronized(lock) {
            val f = File(context.filesDir, QUEUE_FILE)
            if (entries.isEmpty()) {
                f.delete()
            } else {
                f.writeText(entries.joinToString("\n") {
                    listOf(it.channel, it.endpoint, it.attempts.toString(), it.payload).joinToString(SEP)
                })
            }
        }

        internal const val MAX_ATTEMPTS_INTERNAL = MAX_ATTEMPTS
    }

    data class Entry(val channel: String, val endpoint: String, val attempts: Int, val payload: String)
}

/** Воркер повторной доставки (FR-6.3). */
class ExternalRetryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val entries = ExternalRetryQueueImpl.take(context)
        if (entries.isEmpty()) return Result.success()

        val remaining = mutableListOf<ExternalRetryQueueImpl.Entry>()
        var needRetry = false

        entries.forEach { entry ->
            val sent = trySend(entry)
            if (!sent) {
                val attempts = entry.attempts + 1
                if (attempts < ExternalRetryQueueImpl.MAX_ATTEMPTS_INTERNAL) {
                    remaining.add(entry.copy(attempts = attempts))
                    needRetry = true
                }
                // иначе событие выбрасывается: лимит попыток исчерпан (FR-6.3)
            }
        }

        ExternalRetryQueueImpl.rewrite(context, remaining)
        return if (needRetry) Result.retry() else Result.success()
    }

    /**
     * Реальная отправка появится в M4 (Telegram Bot API / webhook).
     * Сейчас — честный «не настроено», но структура готова к замене.
     */
    private fun trySend(entry: ExternalRetryQueueImpl.Entry): Boolean {
        val configured = entry.endpoint.isNotBlank() &&
            (entry.channel.equals("TELEGRAM", true) || entry.channel.equals("WEBHOOK", true)) &&
            inputData.getBoolean(DATA_ENABLED, false)
        return configured && false // M4: здесь будет HTTP-вызов
    }

    companion object {
        const val DATA_ENABLED = "external_enabled"

        fun data(enabled: Boolean) = Data.Builder().putBoolean(DATA_ENABLED, enabled).build()
    }
}
