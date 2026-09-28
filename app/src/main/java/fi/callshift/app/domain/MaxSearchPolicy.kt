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
    private fun label(value: String?): String = value.orEmpty().trim()
        .replace(Regex("[\\s\u00a0]+"), " ").lowercase(java.util.Locale.ROOT)
    fun isGlobalSearchLabel(value: String?): Boolean = label(value) in
        setOf("люди, чаты и сообщения", "people, chats and messages", "people, chats, and messages")
    fun isGlobalSearchSection(value: String?): Boolean = label(value) in
        setOf("все контакты", "недавно искали", "all contacts", "recent searches")
    fun isSearchLabel(value: String?): Boolean = isGlobalSearchLabel(value) || label(value) in
        setOf("поиск", "поиск по чатам", "поиск контактов", "search", "search chats", "search contacts")
}
