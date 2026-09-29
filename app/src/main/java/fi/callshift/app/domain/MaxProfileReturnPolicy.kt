package fi.callshift.app.domain

/** Verifies the controlled Back transition, not a recipient by display name alone. */
object MaxProfileReturnPolicy {
    data class Anchor(val headerId: String, val editorId: String, val headerClass: String,
        val editorClass: String, val toolbarShape: String)
    enum class Issue(val message: String) {
        WINDOW("MAX: после карточки изменилось окно чата"),
        CAPTION("MAX: после карточки изменился заголовок чата"),
        LAYOUT("MAX: после карточки изменилась структура заголовка или редактора"),
        EDITORS("MAX: после карточки поле сообщения не единственное"),
        DRAFT("MAX: после карточки поле сообщения непустое; текст не изменён")
    }
    fun issue(sameWindow: Boolean, sameCaption: Boolean, before: Anchor?, after: Anchor?,
        editableCount: Int, draft: String): Issue? = when {
        !sameWindow -> Issue.WINDOW
        !sameCaption -> Issue.CAPTION
        before == null || after == null || before != after || before.headerId.isBlank() ||
            before.editorId.isBlank() || before.headerClass.isBlank() || before.editorClass.isBlank() ||
            before.toolbarShape.isBlank() -> Issue.LAYOUT
        editableCount != 1 -> Issue.EDITORS
        draft.isNotEmpty() -> Issue.DRAFT
        else -> null
    }
    fun authorize(phoneVerified: Boolean, issue: Issue?, stable: Boolean) = phoneVerified && issue == null && stable
}
