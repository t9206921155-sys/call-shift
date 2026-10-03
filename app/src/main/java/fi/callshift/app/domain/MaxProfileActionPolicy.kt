package fi.callshift.app.domain

/** Labels identify a navigation action only, never the recipient. No coordinates or name guesses. */
object MaxProfileActionPolicy {
    fun supportsClick(clickable: Boolean, declaresClick: Boolean) = clickable || declaresClick
    fun priority(text: String?, description: String?): Int? {
        val labels = listOfNotNull(text, description).map { it.trim().lowercase(java.util.Locale.ROOT) }
        // Conflicting phone-call/menu semantics must not be overridden by an avatar label.
        if (labels.any { label -> listOf("звон", "call", "video", "видео", "меню", "ещё", "more", "menu").any(label::contains) }) return null
        if (labels.any { it in setOf("профиль", "открыть профиль", "профиль контакта", "информация о контакте", "информация о пользователе", "profile", "open profile", "view profile", "contact info", "contact information") }) return 0
        if (labels.any { it in setOf("аватар", "аватар контакта", "фото профиля", "фотография профиля", "avatar", "profile picture", "profile photo", "contact photo") }) return 1
        return null
    }
    /** Conservative resource semantics for clients that expose IDs but no spoken label. */
    fun profileResource(id: String): Boolean {
        if (!id.startsWith(MaxUiPolicy.PACKAGE + ":id/")) return false
        val name = id.substringAfter('/').lowercase(java.util.Locale.ROOT)
        if (Regex("(^|_)(call|video|back|more|menu|edit|delete|remove|block|report|settings)(_|$)").containsMatchIn(name)) return false
        return name in setOf("avatar", "userpic", "profile", "profile_avatar", "profile_picture", "profile_photo",
            "contact_avatar", "user_avatar", "chat_avatar", "header_avatar", "toolbar_avatar",
            "contact_profile", "user_profile", "chat_profile", "header_profile", "toolbar_profile")
    }
    /** Never use list order to break a tie. */
    fun choose(priorities: List<Int?>): Int? {
        val best = priorities.filterNotNull().minOrNull() ?: return null
        return priorities.indices.filter { priorities[it] == best }.singleOrNull()
    }
}
