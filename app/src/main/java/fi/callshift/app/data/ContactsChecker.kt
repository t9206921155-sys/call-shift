package fi.callshift.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import fi.callshift.app.domain.ContactChecker
import fi.callshift.app.domain.PhoneNumberNormalizer

/**
 * Проверка «номер в контактах» через ContactsContract (FR-2.3).
 *
 * Деградация (ТЗ п. 8.2, R-6): при отсутствии READ_CONTACTS возвращаем null
 * (неизвестно) — правило с условием «из контактов» тогда не срабатывает,
 * и вызов идёт по политике по умолчанию. Это безопасно и явно видно в журнале.
 */
class ContactsChecker(
    private val context: Context,
    private val normalizer: PhoneNumberNormalizer,
) : ContactChecker {

    /** contactId → (displayName, список нормализованных номеров). */
    private data class ContactEntry(val id: Long, val name: String, val numbers: Set<String>)

    @Volatile
    private var cache: Map<String, ContactEntry> = emptyMap()

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var cacheValid = false

    override suspend fun contains(e164: String?): Boolean? {
        if (e164.isNullOrBlank()) return false
        if (!hasPermission()) return null
        val key = normalizer.normalize(e164).e164 ?: e164
        return resolveCache()[key] != null
    }

    suspend fun contactName(e164: String?): String? {
        if (e164.isNullOrBlank()) return null
        if (!hasPermission()) return null
        val key = normalizer.normalize(e164).e164 ?: e164
        return resolveCache()[key]?.name
    }

    /** Для UI выбора «контакт из списка» (FR-2.4). */
    suspend fun allContacts(): List<Pair<Long, String>> {
        if (!hasPermission()) return emptyList()
        return resolveCache().values.map { it.id to it.name }.distinctBy { it.first }.sortedBy { it.second }
    }

    /** Вызов при изменении контактов/правил (FR-8.2: invalidate on contact change). */
    fun invalidate() {
        cacheValid = false
    }

    private fun resolveCache(): Map<String, ContactEntry> {
        val now = System.currentTimeMillis()
        if (cacheValid && now - cachedAt < CACHE_TTL_MS) return cache
        val fresh = runCatching { load() }.getOrDefault(emptyMap())
        cache = fresh
        cachedAt = now
        cacheValid = true
        return fresh
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private fun load(): Map<String, ContactEntry> {
        val result = mutableMapOf<String, ContactEntry>()
        val resolver = context.contentResolver
        // Один проход по связке «контакт → телефон», чтобы не делать N запросов.
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            if (idIdx < 0 || numIdx < 0) return@use

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIdx)
                val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "—" else "—"
                val raw = cursor.getString(numIdx) ?: continue
                val key = normalizer.normalize(raw).e164 ?: continue
                val existing = result[key]
                result[key] = if (existing == null) {
                    ContactEntry(id, name, setOf(key))
                } else {
                    existing.copy(numbers = existing.numbers + key)
                }
            }
        }
        return result
    }

    companion object {
        /** Кэш на 10 минут: чтение контактов в screening-пути недопустимо медленно. */
        private const val CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
