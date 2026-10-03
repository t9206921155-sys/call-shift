package fi.callshift.app.domain

/** Segment callbacks may arrive out of order or be repeated. Sending is not delivery. */
class SmsSendTracker(private val count: Int) {
    init { require(count > 0) }
    private val results = mutableMapOf<Int, Int>()
    @Synchronized fun accept(index: Int, result: Int): List<Int>? {
        if (index !in 0 until count || index in results) return null
        results[index] = result
        return if (results.size == count) (0 until count).map { results.getValue(it) } else null
    }
}
