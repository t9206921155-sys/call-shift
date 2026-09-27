package fi.callshift.app.domain

/** No manufacturer names, SIM ordinal guesses, coordinates or recipient mappings. */
object MaxRoutePolicy {
    @kotlinx.serialization.Serializable
    data class Picker(val packageName: String, val version: Long, val windowClass: String,
        val labels: List<String>, val shape: String)
    @kotlinx.serialization.Serializable
    data class Route(val picker: Picker? = null, val label: String? = null)
    fun chooserTitle(text: String): Boolean = text.trim().lowercase(java.util.Locale.ROOT) in setOf(
        "выберите приложение для открытия", "открыть с помощью", "open with", "choose an app", "complete action using")
    fun candidateLabel(text: String): Boolean = text == "MAX" ||
        (text.startsWith("MAX (") && text.endsWith(")") && text.length in 7..50 && !text.contains('\n'))
    fun validLabels(labels: List<String>): Boolean = labels.size == 2 && labels.distinct().size == 2 &&
        labels.contains("MAX") && labels.all(::candidateLabel)
    fun matches(expected: Picker, actual: Picker, target: String): Boolean =
        validLabels(expected.labels) && validLabels(actual.labels) && target in expected.labels &&
            expected.packageName == actual.packageName && expected.version == actual.version &&
            expected.windowClass == actual.windowClass && expected.shape.isNotBlank() && expected.shape == actual.shape && expected.labels.toSet() == actual.labels.toSet()
    fun resolve(accountId: String?, activeIds: Set<String>, routes: Map<String, Route>): Route? =
        accountId?.takeIf { it in activeIds }?.let(routes::get)
}
