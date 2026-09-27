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
    /** Never use list order to break a tie. */
    fun choose(priorities: List<Int?>): Int? {
        val best = priorities.filterNotNull().minOrNull() ?: return null
        return priorities.indices.filter { priorities[it] == best }.singleOrNull()
    }
}
