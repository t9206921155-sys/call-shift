package fi.callshift.app.max

import android.content.Context
import fi.callshift.app.domain.MaxRoutePolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Same backup-excluded store as the MAX layout; no contacts or recipient numbers. */
class MaxRouteStore(context: Context) {
    private val p = context.getSharedPreferences("max_ui_v1", Context.MODE_PRIVATE)
    fun routes(): Map<String, MaxRoutePolicy.Route> = runCatching {
        Json.decodeFromString<Map<String, MaxRoutePolicy.Route>>(p.getString("sender_routes", "{}")!!)
    }.getOrDefault(emptyMap())
    fun put(account: String, route: MaxRoutePolicy.Route?) {
        val next = routes().toMutableMap().apply { if (route == null) remove(account) else put(account, route) }
        check(p.edit().putString("sender_routes", Json.encodeToString(next))
            .putBoolean("live", false).remove("route_test:$account").commit())
    }
    private fun fingerprint(route: MaxRoutePolicy.Route) = Json.encodeToString(route)
    fun tested(account: String, route: MaxRoutePolicy.Route) = p.getString("route_test:$account", null) == fingerprint(route)
    fun passed(account: String, route: MaxRoutePolicy.Route) {
        if (routes()[account] == route) check(p.edit().putString("route_test:$account", fingerprint(route)).commit())
    }
}
