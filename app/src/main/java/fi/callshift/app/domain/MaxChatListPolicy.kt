package fi.callshift.app.domain

/** A search icon by itself is not proof of global search: it may search this chat. */
object MaxChatListPolicy {
    fun chatsLabel(text: String?): Boolean = text?.trim()?.lowercase(java.util.Locale.ROOT) in setOf("чаты", "chats")
    /** The already-open global search need not expose a selected Chats tab. */
    fun openSearch(composers: Int, phoneLabels: Int, editables: Int, globalFields: Int,
        sectionMarker: Boolean, passwords: Int): Boolean = composers == 0 && phoneLabels == 0 &&
        editables == 1 && globalFields == 1 && sectionMarker && passwords == 0
    fun confirmed(composers: Int, phoneLabels: Int, visibleCollections: Int, chatsMarker: Boolean,
        editables: Int, searchFields: Int, passwords: Int = 0): Boolean = passwords == 0 && composers == 0 && phoneLabels == 0 &&
        visibleCollections > 0 && chatsMarker && searchFields in 0..1 && editables == searchFields
}
