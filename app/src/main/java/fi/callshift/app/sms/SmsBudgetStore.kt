package fi.callshift.app.sms

import android.content.Context
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.SmsSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Reserve billable segments BEFORE SmsManager, atomically across all callers/SIMs. */
object SmsBudgetStore {
    private val mutex = Mutex()
    private fun prefs(c: Context) = c.getSharedPreferences("sms_budget_v1", Context.MODE_PRIVATE)
    private fun read(c: Context) = Json.decodeFromString<List<SmsSafety.Reservation>>(prefs(c).getString("ledger", "[]")!!)
    suspend fun used(c: Context): Int = withContext(Dispatchers.IO) { mutex.withLock { SmsSafety.used(read(c), System.currentTimeMillis()) } }
    suspend fun reserve(c: Context, parts: Int): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val settings = CallShiftApp.from(c).settings
            val items = SmsSafety.reserve(read(c), System.currentTimeMillis(), parts, settings.smsDailyLimit) ?: return@withLock false
            check(prefs(c).edit().putString("ledger", Json.encodeToString(items)).commit()) { "SMS budget storage failed" }
            true
        }
    }
}
