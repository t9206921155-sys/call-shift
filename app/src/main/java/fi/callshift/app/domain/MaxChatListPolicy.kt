package fi.callshift.app.domain

/** A search icon by itself is not proof of global search: it may search this chat. */
object MaxChatListPolicy {
    fun chatsLabel(text: String?): Boolean = text?.trim()?.lowercase(java.util.Locale.ROOT) in setOf("чаты", "chats")
    fun confirmed(composers: Int, phoneLabels: Int, visibleCollections: Int, chatsMarker: Boolean,
        editables: Int, searchFields: Int): Boolean = composers == 0 && phoneLabels == 0 &&
        visibleCollections > 0 && chatsMarker && searchFields in 0..1 && editables == searchFields
}
