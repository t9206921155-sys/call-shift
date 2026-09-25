package fi.callshift.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import fi.callshift.app.domain.T9
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Контакты и журнал звонков для экрана набора. */
class PhoneBook(private val ctx: Context) {

    data class Contact(val name: String, val number: String, val label: String, val starred: Boolean)

    data class Recent(
        val number: String,
        val name: String?,
        val type: Int,
        val date: Long,
        val durationSec: Long,
        val count: Int,
        val accountId: String?,
    )

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    val canReadContacts get() = granted(Manifest.permission.READ_CONTACTS)
    val canReadCallLog get() = granted(Manifest.permission.READ_CALL_LOG)

    @Volatile private var cache: List<Contact>? = null

    suspend fun contacts(refresh: Boolean = false): List<Contact> = withContext(Dispatchers.IO) {
        if (!canReadContacts) return@withContext emptyList()
        cache?.takeIf { !refresh }?.let { return@withContext it }
        val P = ContactsContract.CommonDataKinds.Phone
        val out = mutableListOf<Contact>()
        runCatching {
            ctx.contentResolver.query(
                P.CONTENT_URI,
                arrayOf(P.DISPLAY_NAME, P.NUMBER, P.TYPE, P.LABEL, P.STARRED),
                null, null, "${P.DISPLAY_NAME} COLLATE LOCALIZED ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    val label = P.getTypeLabel(ctx.resources, c.getInt(2), c.getString(3)).toString()
                    out += Contact(name, number, label, c.getInt(4) == 1)
                }
            }
        }
        out.distinctBy { it.name + "|" + it.number.filter { ch -> ch.isDigit() }.takeLast(9) }.also { cache = it }
    }

    suspend fun recents(limit: Int = 300): List<Recent> = withContext(Dispatchers.IO) {
        if (!canReadCallLog) return@withContext emptyList()
        val C = CallLog.Calls
        val raw = mutableListOf<Recent>()
        runCatching {
            ctx.contentResolver.query(
                C.CONTENT_URI,
                arrayOf(C.NUMBER, C.CACHED_NAME, C.TYPE, C.DATE, C.DURATION, C.PHONE_ACCOUNT_ID),
                null, null, "${C.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && raw.size < limit) {
                    raw += Recent(c.getString(0).orEmpty(), c.getString(1)?.takeIf { it.isNotBlank() },
                        c.getInt(2), c.getLong(3), c.getLong(4), 1, c.getString(5))
                }
            }
        }
        // Склеиваем подряд идущие звонки с одного номера одного типа: «(3)».
        val grouped = mutableListOf<Recent>()
        for (r in raw) {
            val last = grouped.lastOrNull()
            if (last != null && last.number == r.number && last.type == r.type) {
                grouped[grouped.size - 1] = last.copy(count = last.count + 1)
            } else grouped += r
        }
        grouped
    }

    /** T9-подсказки для набранных цифр: контакты и номера из недавних. */
    suspend fun t9(query: String, max: Int = 30): List<Contact> {
        if (query.count { it.isDigit() } < 1) return emptyList()
        val fromContacts = contacts().map { it to T9.score(query, it.name, it.number) }
        val known = fromContacts.map { it.first.number.filter { ch -> ch.isDigit() }.takeLast(9) }.toSet()
        val fromRecents = recents(150).asSequence()
            .filter { it.name == null && it.number.isNotBlank() }
            .distinctBy { it.number }
            .filter { it.number.filter { ch -> ch.isDigit() }.takeLast(9) !in known }
            .map { Contact(it.number, it.number, "из недавних", false) to T9.score(query, null, it.number) }
        return (fromContacts + fromRecents).filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Contact, Int>> { it.second }.thenByDescending { it.first.starred })
            .take(max).map { it.first }
    }
}
