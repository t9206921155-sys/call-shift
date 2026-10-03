package fi.callshift.app.domain

/** A display name only identifies a candidate, never authorizes sending. */
object MaxProfilePolicy {
    fun titleId(id: String): Boolean = Regex("(^|_)title(_|$)", RegexOption.IGNORE_CASE)
        .containsMatchIn(id.substringAfterLast('/'))

    fun phoneLabel(text: String?): Boolean = text?.trim()?.lowercase(java.util.Locale.ROOT) in
        setOf("номер телефона", "телефон", "phone number", "phone")
    data class PhoneField(val found: Boolean, val phone: String?)
    /** Values must come from the small labelled section, never the whole profile screen. */
    fun readPhoneField(label: String?, values: List<String>): PhoneField {
        if (!phoneLabel(label)) return PhoneField(false, null)
        val phones = values.mapNotNull(MaxUiPolicy::phone).distinct()
        return PhoneField(phones.isNotEmpty(), phones.singleOrNull())
    }
    fun verifiedPhone(label: String?, values: List<String>, expected: String): String? =
        readPhoneField(label, values).phone?.takeIf { MaxSearchPolicy.equivalent(it, expected) }
    fun candidateNameMatches(label: String?, name: String?): Boolean =
        !name.isNullOrBlank() && !label.isNullOrBlank() && label.trim().equals(name.trim(), ignoreCase = true)
}
