package fi.callshift.app.telegram

import android.content.Context
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.SmsSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reserve a Telegram auto-reply BEFORE the TDLib request, atomically across callers.
 * Same bounded ledger as SMS/MAX: a runaway scenario can never flood the account. */
object TelegramLimitStore {
    private val mutex = Mutex()
    private fun prefs(c: Context) = c.getSharedPreferences("tg_budget_v1", Context.MODE_PRIVATE)
    private fun read(c: Context) =
        Json.decodeFromString<List<SmsSafety.Reservation>>(prefs(c).getString("ledger", "[]")!!)

    suspend fun used(c: Context): Int = withContext(Dispatchers.IO) {
        mutex.withLock { SmsSafety.used(read(c), System.currentTimeMillis()) }
    }

    suspend fun reserve(c: Context): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val limit = CallShiftApp.from(c).settings.telegramDailyLimit
            val items = SmsSafety.reserve(read(c), System.currentTimeMillis(), 1, limit) ?: return@withLock false
            check(prefs(c).edit().putString("ledger", Json.encodeToString(items)).commit()) { "Telegram budget storage failed" }
            true
        }
    }
}
