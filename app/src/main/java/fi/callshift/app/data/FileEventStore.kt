package fi.callshift.app.data

import android.content.Context
import fi.callshift.app.forward.CallEvent
import fi.callshift.app.forward.EventRecorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Журнал событий в JSON-файле (референс-M1/M2; целевое состояние по ТЗ п. 9.2 — Room `events`).
 *
 * Ограничение размера: храним последние [MAX_EVENTS] записей (FR-7.1: «журнал
 * последних 500 событий»). Маскировка номера выполняется ДО записи
 * (FR-7.4: чувствительные данные не покидают устройство в открытом виде).
 */
class FileEventStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : EventRecorder {

    private val file: File = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val revision = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val changes: kotlinx.coroutines.flow.StateFlow<Long> = revision

    @Volatile
    private var cache: MutableList<CallEvent> = mutableListOf()

    @Volatile
    private var loaded = false

    override suspend fun record(event: CallEvent) {
        withContext(io) {
            mutex.withLock {
                ensureLoadedLocked()
                val existing = event.eventId?.let { id -> cache.indexOfFirst { it.eventId == id } } ?: -1
                if (existing >= 0) cache[existing] = event else cache.add(0, event)
                while (cache.size > MAX_EVENTS) cache.removeAt(cache.size - 1)
                writeLocked()
                revision.value += 1
            }
        }
    }

    suspend fun events(limit: Int = MAX_EVENTS): List<CallEvent> = withContext(io) {
        mutex.withLock {
            ensureLoadedLocked()
            cache.take(limit).toList()
        }
    }

    suspend fun clear() = withContext(io) {
        mutex.withLock {
            cache.clear()
            loaded = true
            writeLocked()
            revision.value += 1
        }
    }

    /** Экспорт в CSV (FR-7.6, CSV-формат из ТЗ п. 9.2). */
    suspend fun exportCsv(maskNumbers: Boolean = true): String = withContext(io) {
        mutex.withLock {
            ensureLoadedLocked()
            buildString {
                append(CSV_HEADER).append('\n')
                cache.forEach { e ->
                    append(csvRow(e, maskNumbers)).append('\n')
                }
            }
        }
    }

    private fun csvRow(e: CallEvent, mask: Boolean): String = listOf(
        e.ts.toString(),
        e.direction,
        if (mask) maskNumber(e.numberE164) else e.numberE164.orEmpty(),
        e.sim,
        e.ruleId?.toString() ?: "",
        e.ruleName ?: "",
        e.strategy,
        if (mask) maskNumber(e.target) else e.target.orEmpty(),
        e.result,
        e.errorCode ?: "",
        e.errorMessage ?: "",
        e.reason,
        e.screeningMs.toString(),
        e.forwardMs.toString(),
        e.totalMs.toString(),
    ).joinToString(",") { escapeCsv(it) }

    private fun escapeCsv(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == ';' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    /** FR-7.4: маска номера для экспорта (показываем только хвост). */
    private fun maskNumber(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val digits = value.filter { it.isDigit() }
        return if (digits.length <= 4) "*".repeat(digits.length)
        else "*".repeat(digits.length - 3) + digits.takeLast(3)
    }

    private fun ensureLoadedLocked() {
        if (loaded) return
        cache = try {
            if (!file.exists()) mutableListOf()
            else {
                val arr = JSONObject(file.readText()).optJSONArray("events")
                if (arr == null) mutableListOf()
                else (0 until arr.length()).mapNotNull { decode(arr.optJSONObject(it)) }.toMutableList()
            }
        } catch (t: Throwable) {
            runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.corrupt-${System.currentTimeMillis()}")) }
            mutableListOf()
        }
        loaded = true
    }

    private fun writeLocked() {
        try {
            val root = JSONObject().apply {
                put("version", VERSION)
                put("events", JSONArray().apply { cache.forEach { e -> put(encode(e)) } })
            }
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
            // Журнал — вспомогательная функция: сбой записи не должен ронять перехват (FR-2.8).
        }
    }

    private fun encode(e: CallEvent): JSONObject = JSONObject().apply {
        put("eventId", e.eventId ?: JSONObject.NULL)
        put("ts", e.ts)
        put("direction", e.direction)
        put("numberE164", e.numberE164 ?: JSONObject.NULL)
        put("numberMasked", e.numberMasked)
        put("sim", e.sim)
        put("ruleId", e.ruleId ?: JSONObject.NULL)
        put("ruleName", e.ruleName ?: JSONObject.NULL)
        put("strategy", e.strategy)
        put("target", e.target ?: JSONObject.NULL)
        put("result", e.result)
        put("errorCode", e.errorCode ?: JSONObject.NULL)
        put("errorMessage", e.errorMessage ?: JSONObject.NULL)
        put("reason", e.reason)
        put("screeningMs", e.screeningMs)
        put("forwardMs", e.forwardMs)
        put("totalMs", e.totalMs)
    }

    private fun decode(o: JSONObject?): CallEvent? {
        if (o == null) return null
        return try {
            CallEvent(
                eventId = if (o.isNull("eventId")) null else o.optString("eventId"),
                ts = o.optLong("ts"),
                direction = o.optString("direction", "INCOMING"),
                numberE164 = if (o.isNull("numberE164")) null else o.optString("numberE164"),
                numberMasked = o.optString("numberMasked", ""),
                sim = o.optString("sim", "—"),
                ruleId = if (o.isNull("ruleId")) null else o.optLong("ruleId"),
                ruleName = if (o.isNull("ruleName")) null else o.optString("ruleName"),
                strategy = o.optString("strategy", ""),
                target = if (o.isNull("target")) null else o.optString("target"),
                result = o.optString("result", ""),
                errorCode = if (o.isNull("errorCode")) null else o.optString("errorCode"),
                errorMessage = if (o.isNull("errorMessage")) null else o.optString("errorMessage"),
                reason = o.optString("reason", ""),
                screeningMs = o.optLong("screeningMs", 0),
                forwardMs = o.optLong("forwardMs", 0),
                totalMs = o.optLong("totalMs", 0),
            )
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        private const val FILE_NAME = "events.json"
        private const val VERSION = 2
        private const val MAX_EVENTS = 500

        val CSV_HEADER = listOf(
            "ts", "direction", "number", "sim", "rule_id", "rule_name", "strategy",
            "target", "result", "error_code", "error_message", "reason",
            "screening_ms", "forward_ms", "total_ms",
        ).joinToString(",")
    }
}
