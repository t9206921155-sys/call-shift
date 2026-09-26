package fi.callshift.app.data

import android.content.Context
import fi.callshift.app.domain.Rule
import fi.callshift.app.domain.RuleStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Хранилище правил в JSON-файле (референс-M1/M2).
 *
 * Почему не Room прямо сейчас: объём данных — десятки правил, а требования ТЗ
 * к надёжности (fail-open, бюджет 3 с) важнее способа хранения. Интерфейс
 * [RuleStore] позволяет перейти на Room без изменения домена и UI
 * (ТЗ п. 6.5: «Room (rules, events, cf_state)» — целевое состояние).
 *
 * Гарантии реализации:
 *  - атомарная запись через временный файл + rename (защита от битого JSON);
 *  - мьютекс на запись/чтение в пределах процесса;
 *  - кэш в памяти — чтение правил в screening-пути не касается диска
 *    (ТЗ NFR-1: p95 ≤ 300 мс);
 *  - битый файл не роняет перехват: сохраняется копия, стартуем с пустого
 *    набора (fail-open, NFR-2, тест 5.12 чек-листа).
 */
class JsonFileRuleStore(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RuleStore {

    @Serializable
    private data class RuleFile(val version: Int = 1, val rules: List<Rule> = emptyList())

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val file: File = File(context.applicationContext.filesDir, FILE_NAME)
    private val mutex = Mutex()

    @Volatile
    private var cache: List<Rule> = emptyList()

    @Volatile
    private var loaded = false

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            cache = readFromDisk()
            loaded = true
        }
    }

    private fun readFromDisk(): List<Rule> = try {
        val atomic = android.util.AtomicFile(file)
        if (!file.exists() && !File(file.path + ".bak").exists()) emptyList()
        else json.decodeFromString<RuleFile>(atomic.openRead().bufferedReader().use { it.readText() }).rules
            .sortedBy { it.priority }
    } catch (t: Throwable) {
        runCatching { file.renameTo(File(file.parentFile, "$FILE_NAME.corrupt-${System.currentTimeMillis()}")) }
        emptyList()
    }

    private fun writeLocked(rules: List<Rule>) {
        val sorted = rules.sortedBy { it.priority }
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.encodeToString(RuleFile(rules = sorted)).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) { atomic.failWrite(stream); throw error }
        cache = sorted
    }

    override suspend fun rules(): List<Rule> = withContext(io) {
        ensureLoaded()
        cache
    }

    override suspend fun save(rule: Rule): Rule = withContext(io) {
        ensureLoaded()
        mutex.withLock {
            val now = System.currentTimeMillis()
            val existing = cache.firstOrNull { it.id == rule.id }
            val toSave = rule.copy(
                id = if (rule.id == 0L) (cache.maxOfOrNull { it.id } ?: 0L) + 1 else rule.id,
                createdAt = existing?.createdAt ?: if (rule.createdAt == 0L) now else rule.createdAt,
                updatedAt = now,
            )
            val updated = if (existing == null) cache + toSave else cache.map { if (it.id == toSave.id) toSave else it }
            writeLocked(updated)
            toSave
        }
    }

    override suspend fun delete(id: Long) = withContext(io) {
        ensureLoaded()
        mutex.withLock { writeLocked(cache.filterNot { it.id == id }) }
    }

    override suspend fun setEnabled(id: Long, enabled: Boolean) = withContext(io) {
        ensureLoaded()
        mutex.withLock {
            writeLocked(cache.map { if (it.id == id) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it })
        }
    }

    override suspend fun replaceAll(rules: List<Rule>) = withContext(io) {
        mutex.withLock { writeLocked(rules); loaded = true }
    }

    override suspend fun nextId(): Long = withContext(io) {
        ensureLoaded()
        (cache.maxOfOrNull { it.id } ?: 0L) + 1
    }

    /** Экспорт/импорт правил в JSON (FR-1.7). */
    suspend fun exportJson(): String = withContext(io) {
        ensureLoaded()
        json.encodeToString(RuleFile(rules = cache))
    }

    suspend fun importJson(text: String): Int = withContext(io) {
        val parsed = runCatching { json.decodeFromString<RuleFile>(text) }.getOrNull()
            ?: return@withContext 0
        mutex.withLock { writeLocked(parsed.rules) }
        loaded = true
        parsed.rules.size
    }

    companion object {
        private const val FILE_NAME = "rules.json"
    }
}
