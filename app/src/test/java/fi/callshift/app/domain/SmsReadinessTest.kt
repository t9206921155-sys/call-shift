package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class SmsReadinessTest {
    private val ready = SmsReadiness.Input(28, true, true, true, false, true, true, 2, 2)
    @Test fun android9RequiresDialer() {
        assertTrue(SmsReadiness.problems(ready).isEmpty())
        assertTrue(SmsReadiness.problems(ready.copy(dialer = false, screening = true)).any { it.contains("Android 9") })
    }
    @Test fun modernAndroidAllowsEitherRole() {
        for (api in 29..36) {
            assertTrue(SmsReadiness.problems(ready.copy(api = api, dialer = false, screening = true)).isEmpty())
            assertTrue(SmsReadiness.problems(ready.copy(api = api)).isEmpty())
        }
    }
    @Test fun missingHardwarePermissionOrSimNotReady() {
        assertFalse(SmsReadiness.problems(ready.copy(smsCapable = false)).isEmpty())
        assertFalse(SmsReadiness.problems(ready.copy(smsPermission = false)).isEmpty())
        assertFalse(SmsReadiness.problems(ready.copy(phonePermission = false)).isEmpty())
        assertFalse(SmsReadiness.problems(ready.copy(accounts = 0, usableAccounts = 0)).isEmpty())
        assertFalse(SmsReadiness.problems(ready.copy(usableAccounts = 1)).isEmpty())
    }
}
