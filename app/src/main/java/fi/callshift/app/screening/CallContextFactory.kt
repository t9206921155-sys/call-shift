package fi.callshift.app.screening

import android.telecom.Call
import fi.callshift.app.CallShiftApp
import fi.callshift.app.domain.CallContext
import fi.callshift.app.domain.Direction
import fi.callshift.app.domain.PhoneAccountRef
import fi.callshift.app.domain.Signal

/** Shared context for screening and the default-dialer fallback. */
class CallContextFactory(private val app: CallShiftApp) {
    /** Формируем доменный контекст из системного Call.Details. */
    fun buildContext(details: Call.Details): CallContext {
        val raw = runCatching { details.handle?.schemeSpecificPart }.getOrNull()
        val normalized = app.normalizer.normalize(raw)
        val isEmergency = normalized.isEmergency ||
            app.normalizer.looksLikeLocalEmergency(raw.orEmpty()) ||
            hasHiddenProperty(details, "PROPERTY_EMERGENCY_CALLBACK")

        return CallContext(
            rawHandle = raw,
            e164 = normalized.e164,
            national = normalized.national,
            // CallScreeningService вызывается только для входящих вызовов.
            direction = Direction.INCOMING,
            phoneAccount = resolvePhoneAccount(details),
            isEmergency = isEmergency,
            isSelfManaged = runCatching {
                details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED)
            }.getOrDefault(false),
            signals = currentSignals(),
        )
    }

    /**
     * Best-effort определение SIM (Приложение E, P-2/P-3):
     *  1) reflection к скрытому getPhoneAccountHandle();
     *  2) ключи phoneAccount в extras;
     *  3) null → «неизвестно» → правило для конкретной SIM не применяется.
     */
    private fun resolvePhoneAccount(details: Call.Details): PhoneAccountRef? {
        // 0) Публичный API: Call.Details.getAccountHandle().
        runCatching {
            val id = details.accountHandle?.id
            if (!id.isNullOrBlank()) {
                return PhoneAccountRef(id = id, label = app.telecom.phoneAccounts()[id] ?: id)
            }
        }
        runCatching {
            val method = details.javaClass.getMethod("getPhoneAccountHandle")
            val handle = method.invoke(details)
            if (handle != null) {
                val id = (handle as? android.telecom.PhoneAccountHandle)?.id
                if (!id.isNullOrBlank()) {
                    return PhoneAccountRef(id = id, label = app.telecom.phoneAccounts()[id] ?: id)
                }
            }
        }
        runCatching {
            val extras = details.extras ?: return@runCatching
            for (key in extras.keySet()) {
                if (key.contains("phone_account", ignoreCase = true)) {
                    // Do not parse toString(): it can contain the component/user,
                    // not the SIM identifier. Only trust a typed account handle.
                    val handle = extras.get(key) as? android.telecom.PhoneAccountHandle ?: continue
                    val id = handle.id
                    if (id.isNotBlank()) return PhoneAccountRef(id = id, label = id)
                }
            }
        }
        // Запасной способ: какая SIM сейчас в состоянии «звонит».
        app.telecom.ringingAccountId()?.let { id ->
            return PhoneAccountRef(id = id, label = app.telecom.phoneAccounts()[id] ?: id)
        }
        return null
    }

    private fun hasHiddenProperty(details: Call.Details, constantName: String): Boolean {
        val value = runCatching {
            Call.Details::class.java.getField(constantName).getInt(null)
        }.getOrNull() ?: return false
        return runCatching { details.hasProperty(value) }.getOrDefault(false)
    }

    /** Дешёвые сигналы окружения для условий правил (ТЗ п. 9.4). */
    private fun currentSignals(): Map<Signal, String> {
        val signals = mutableMapOf<Signal, String>()
        runCatching {
            val bm = app.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level > 0) signals[Signal.BATTERY] = level.toString()
        }
        runCatching {
            val cm = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            signals[Signal.NETWORK] = when {
                caps == null -> "NONE"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "MOBILE"
                else -> "OTHER"
            }
        }
        return signals
    }

}
