package fi.callshift.app.domain

/** One physical gesture at a time. Stopping a scenario is not proof Android stopped its gesture. */
class MaxGestureFlight {
    private var serial = 0L
    private var active: Long? = null
    private var started = 0L
    val busy get() = active != null
    fun begin(now: Long): Long? {
        if (busy) return null
        started = now
        return (++serial).also { active = it }
    }
    /** Only that dispatch's completion/cancellation/rejection can release the physical action. */
    fun resolve(token: Long): Boolean {
        if (active != token) return false
        active = null
        return true
    }
    fun overdue(now: Long) = busy && (now < started || now - started >= 2000)
}
