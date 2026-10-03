package fi.callshift.app.domain

import kotlinx.serialization.Serializable

object SmsSafety {
    const val WINDOW_MS = 24 * 60 * 60 * 1000L
    const val DEFAULT_LIMIT = 20
    @Serializable data class Reservation(val at: Long, val parts: Int)
    fun active(items: List<Reservation>, now: Long) = items.filter { now < it.at || now - it.at < WINDOW_MS }
    fun used(items: List<Reservation>, now: Long): Int = active(items, now).sumOf { it.parts }
    fun reserve(items: List<Reservation>, now: Long, parts: Int, limit: Int): List<Reservation>? {
        require(limit in 1..1000 && parts in 1..1000)
        require(items.size <= 1000 && items.all { it.parts in 1..1000 && it.at >= 0 })
        val current = active(items, now)
        if (current.sumOf { it.parts.toLong() } + parts > limit) return null
        return current + Reservation(now, parts)
    }
    fun cooldownKey(channel: String, number: String, sim: String?, perSim: Boolean): String {
        if (!perSim) return legacyKey(channel, number)
        // Length-prefix avoids account IDs containing punctuation causing collisions.
        val account = sim ?: "unknown"
        return "sim:${account.length}:$account:${legacyKey(channel, number)}"
    }
    fun legacyKey(channel: String, number: String) = if (channel == "SMS") number else "$channel:$number"
}
