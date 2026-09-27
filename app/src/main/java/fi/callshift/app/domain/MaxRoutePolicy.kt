package fi.callshift.app.domain

/** No manufacturer names, SIM ordinal guesses, coordinates or recipient mappings. */
object MaxRoutePolicy {
    @kotlinx.serialization.Serializable
    data class Picker(val packageName: String, val version: Long, val windowClass: String,
        val labels: List<String>, val shape: String)
    @kotlinx.serialization.Serializable
    data class Route(val picker: Picker? = null, val label: String? = null)
    /** Description is a fallback only, never a way to override conflicting visible text. */
    fun accessibleLabel(text: String?, description: String?): String =
        (text?.takeIf { it.isNotBlank() } ?: description.orEmpty()).trim().replace(Regex("[\\s\u00a0]+"), " ")
    fun chooserTitle(text: String): Boolean = text.trim().lowercase(java.util.Locale.ROOT) in setOf(
        "выберите приложение для открытия", "открыть с помощью", "open with", "choose an app", "complete action using")
    fun candidateLabel(text: String): Boolean = text == "MAX" ||
        (text.startsWith("MAX (") && text.endsWith(")") && text.length in 7..50 && !text.contains('\n'))
    fun validLabels(labels: List<String>): Boolean = labels.size == 2 && labels.distinct().size == 2 &&
        labels.contains("MAX") && labels.all(::candidateLabel)
    /** Repeated caption/description nodes are allowed only when they resolve to the SAME action. */
    fun <T : Any> uniqueTargets(candidates: List<Pair<String, T>>): Map<String, T>? {
        val grouped = candidates.groupBy({ it.first }, { it.second })
        if (!validLabels(grouped.keys.toList())) return null
        val targets = grouped.mapValues { (_, values) -> values.distinct().singleOrNull() ?: return null }
        return targets.takeIf { it.values.distinct().size == 2 }
    }
    fun matches(expected: Picker, actual: Picker, target: String): Boolean =
        validLabels(expected.labels) && validLabels(actual.labels) && target in expected.labels &&
            expected.packageName == actual.packageName && expected.version == actual.version &&
            expected.windowClass == actual.windowClass && expected.shape.isNotBlank() && expected.shape == actual.shape && expected.labels.toSet() == actual.labels.toSet()
    fun resolve(accountId: String?, activeIds: Set<String>, routes: Map<String, Route>): Route? =
        accountId?.takeIf { it in activeIds }?.let(routes::get)
}
