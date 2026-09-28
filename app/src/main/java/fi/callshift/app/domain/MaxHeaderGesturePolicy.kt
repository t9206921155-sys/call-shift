package fi.callshift.app.domain

/** A tap inside a freshly resolved title, never a saved screen coordinate. */
object MaxHeaderGesturePolicy {
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    }
    data class Point(val x: Float, val y: Float)
    fun point(title: Rect, window: Rect, density: Float, obstacles: List<Rect>): Point? {
        if (!density.isFinite() || density <= 0 || window.width <= 0 || window.height <= 0) return null
        if (title.left < window.left || title.right > window.right || title.top < window.top ||
            title.bottom > window.top + window.height / 3 || title.width < 16 * density ||
            title.height < 10 * density || title.height > 120 * density) return null
        val p = Point(title.left + title.width / 2f, title.top + title.height / 2f)
        return p.takeIf { obstacles.none { it.contains(p.x, p.y) } }
    }
    fun expectedEvent(now: Long, issuedAt: Long, eventAt: Long, window: Int, eventWindow: Int): Boolean =
        issuedAt >= 0 && now >= issuedAt && now - issuedAt <= 1500 &&
            eventAt in issuedAt..now && window >= 0 && (eventWindow == window || eventWindow == -1)
}
