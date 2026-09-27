package fi.callshift.app.domain

/** Search only uses equivalent full numbers, never contact names or partial digits. */
object MaxSearchPolicy {
    fun queries(raw: String): List<String> {
        val canonical = MaxUiPolicy.phone(raw) ?: return emptyList()
        return if (canonical.length == 12 && canonical.startsWith("+7"))
            listOf(canonical, "8" + canonical.drop(2)) else listOf(canonical)
    }
    fun equivalent(left: String?, right: String?): Boolean {
        val a = left?.let(MaxUiPolicy::phone) ?: return false
        val b = right?.let(MaxUiPolicy::phone) ?: return false
        return a == b
    }
    fun isSearchLabel(value: String?): Boolean = value?.trim()?.lowercase(java.util.Locale.ROOT) in
        setOf("поиск", "поиск по чатам", "поиск контактов", "search", "search chats", "search contacts")
}
