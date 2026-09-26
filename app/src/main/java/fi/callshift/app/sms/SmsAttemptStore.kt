package fi.callshift.app.sms

import android.content.Context
import androidx.work.*
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.SmsAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/** Callback metadata survives service/process recreation. No message body is stored. */
object SmsAttemptStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun prefs(c: Context) = c.getSharedPreferences("sms_attempts_v1", Context.MODE_PRIVATE)

    suspend fun create(c: Context, attempt: SmsAttempt) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val id = requireNotNull(attempt.event.eventId)
            val p = prefs(c)
            // Bounded retention, including final receipts. Unknown late callbacks
            // after eviction are ignored, never attributed to another SMS.
            val old = p.all.mapNotNull { (key, raw) ->
                runCatching { key to json.decodeFromString<SmsAttempt>(raw as String).event.ts }.getOrNull()
            }.sortedBy { it.second }
            val edit = p.edit()
            old.take((old.size - 499).coerceAtLeast(0)).forEach { edit.remove(it.first) }
            check(edit.putString(id, json.encodeToString(attempt)).commit()) { "Не удалось сохранить статус SMS" }
            publish(c, attempt)
            for ((kind, minutes) in listOf("send" to 2L, "delivery" to 1440L)) {
                val work = OneTimeWorkRequestBuilder<SmsTimeoutWorker>()
                    .setInputData(workDataOf("id" to id, "kind" to kind))
                    .setInitialDelay(minutes, TimeUnit.MINUTES).build()
                WorkManager.getInstance(c).enqueueUniqueWork("sms:$id:$kind", ExistingWorkPolicy.KEEP, work)
            }
        }
    }

    suspend fun update(c: Context, id: String, change: (SmsAttempt) -> SmsAttempt) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val raw = prefs(c).getString(id, null) ?: return@withLock
            val before = runCatching { json.decodeFromString<SmsAttempt>(raw) }.getOrNull() ?: return@withLock
            val after = change(before)
            // Publish even duplicate receipts: repairs a journal write interrupted
            // after the durable state was committed in a previous process.
            check(prefs(c).edit().putString(id, json.encodeToString(after)).commit())
            publish(c, after)
        }
    }

    private suspend fun publish(c: Context, a: SmsAttempt) {
        val result = a.status()
        CallShiftApp.from(c).eventStore.record(a.event.copy(
            result = result, errorMessage = a.message(),
            errorCode = if (result in listOf("FAILED", "DELIVERY_FAILED", "UNKNOWN", "DELIVERY_UNCONFIRMED"))
                "sms_${result.lowercase()}" else null,
        ))
    }
}

class SmsTimeoutWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        return try {
            SmsAttemptStore.update(applicationContext, id) {
                if (inputData.getString("kind") == "send") it.copy(sendTimedOut = true)
                else it.copy(deliveryTimedOut = true)
            }
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}
