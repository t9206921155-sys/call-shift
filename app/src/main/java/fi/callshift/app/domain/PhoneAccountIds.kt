package fi.callshift.app.domain

/** Resolve old saved identifiers only when they identify exactly one account. */
object PhoneAccountIds {
    data class Account(val id: String, val legacyRepresentation: String)

    fun resolve(saved: String?, accounts: List<Account>): String? {
        if (saved.isNullOrBlank()) return null
        accounts.firstOrNull { it.id == saved }?.let { return it.id }
        return accounts.filter {
            val legacy = it.legacyRepresentation
            val oldId = legacy.substringAfterLast('[', "").substringBefore(']', "")
                .ifBlank { legacy }
            saved == oldId
        }.singleOrNull()?.id
    }
}
