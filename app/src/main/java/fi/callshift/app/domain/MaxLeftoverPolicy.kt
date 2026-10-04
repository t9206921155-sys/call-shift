package fi.callshift.app.domain

/** Bounded memory of drafts CallShift itself wrote into a MAX composer.
 *
 * A stopped run can leave its own text in the chat: the live write succeeded but the
 * send button never fired, the final re-check stopped the scenario, or training was
 * aborted after auto-typing. MAX restores that chat on the next launch, so without
 * recognition such a leftover blocks every later run — including real call replies —
 * even though the app wrote it itself, and the user can only recover by deleting the
 * draft by hand.
 *
 * Only a SHA-256 over (editor id, MAX version, text) is remembered: the text, the
 * chat and the recipient never are. A draft matching such a fingerprint is provably
 * the app's own leftover and may be cleared once. Anything else is the user's content
 * and is never touched — the same fail-closed rule the exact-text check always had. */
object MaxLeftoverPolicy {
    const val LIMIT = 8

    fun key(editorId: String, version: Long, text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$editorId\u0000$version\u0000$text".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Newest first, bounded; a repeated key is refreshed in place, never duplicated. */
    fun remember(registry: List<String>, key: String, limit: Int = LIMIT): List<String> =
        (listOf(key) + registry.filter { it != key }).take(limit.coerceIn(1, 64))

    fun isOwn(registry: List<String>, key: String): Boolean = registry.contains(key)

    fun forget(registry: List<String>, key: String): List<String> = registry.filter { it != key }
}
