package fi.callshift.app.domain

/** Semantic evidence only: an arrow's appearance or rightmost position is not evidence. */
object MaxSendActionPolicy {
    private val labels = setOf("отправить", "отправить сообщение", "send", "send message")
    private val forbidden = Regex("(^|[ _])(attach|attachment|file|photo|video|voice|audio|record|schedule|scheduled|menu|options|call|forward|delete|payment)([ _]|$)")
    private fun normalized(value: String?) = value.orEmpty().trim().lowercase(java.util.Locale.ROOT)
        .replace(Regex("\\s+"), " ").removeSuffix(", кнопка").removeSuffix(", button").trim()
    fun conflict(text: String?, description: String?, id: String?): Boolean {
        val words = listOf(normalized(text), normalized(description))
        return words.any { forbidden.containsMatchIn(it) || listOf("прикреп", "вложен", "скреп", "микроф", "голосов", "запис", "заплан", "позвон", "видеозвон").any(it::contains) } ||
            forbidden.containsMatchIn(id.orEmpty().substringAfter('/').lowercase(java.util.Locale.ROOT))
    }
    fun evidence(text: String?, description: String?, id: String?): Boolean {
        if (conflict(text, description, id)) return false
        val words = listOf(normalized(text), normalized(description))
        val resource = id.orEmpty().substringAfter('/').lowercase(java.util.Locale.ROOT)
        if (words.any { it in labels }) return true
        if (!id.orEmpty().startsWith(MaxUiPolicy.PACKAGE + ":id/")) return false
        return resource in setOf("send", "send_button", "button_send", "btn_send", "send_message", "send_message_button",
            "message_send", "message_send_button", "chat_send", "chat_send_button", "chat_input_send", "chat_input_send_button",
            "input_send", "input_send_button", "composer_send", "composer_send_button")
    }
    /** A generic wrapper cannot turn its neighbouring paperclip into Send. */
    fun redundantParent(actionableDescendants: Int, soleChildIsCandidate: Boolean) =
        actionableDescendants == 1 && soleChildIsCandidate
    fun wrapperSafe(editables: Int, actionableDescendants: Int, sendLabels: Int) =
        editables == 0 && actionableDescendants == 0 && sendLabels == 1
}
