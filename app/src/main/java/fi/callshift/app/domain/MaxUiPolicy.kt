package fi.callshift.app.domain

/** Fail-closed gates; names, coordinates and fuzzy recipient matches are never identities. */
object MaxUiPolicy {
    const val CHANNEL = "MAX_UI"
    const val PACKAGE = "ru.oneme.app"
    /** Only an explicit Android hint flag AND exact hint equality can mean empty text. */
    fun editableText(text: String?, hint: String?, showingHint: Boolean): String =
        if (showingHint && !hint.isNullOrEmpty() && text == hint) "" else text.orEmpty()
    fun phone(raw: String): String? {
        if (raw.isBlank() || raw.any { it !in "+0123456789 ()-.\u00a0" }) return null
        val trimmed = raw.trim()
        if (trimmed.count { it == '+' } > 1 || ('+' in trimmed && !trimmed.startsWith('+'))) return null
        val digits = raw.filter { it in '0'..'9' }
        val canonical = if (trimmed.startsWith("+")) "+$digits"
            else if (digits.length == 11 && digits.startsWith("8")) "+7" + digits.drop(1)
            else if (digits.length == 11 && digits.startsWith("7")) "+$digits"
            else return null
        return canonical.takeIf(ReplyChannel::isPhoneAddress)
    }
    data class Check(val enabled: Boolean, val unlocked: Boolean, val packageName: String,
        val versionMatches: Boolean, val headerCount: Int, val headerPhone: String?,
        val expectedPhone: String, val inputCount: Int, val draft: String)
    fun block(c: Check): String? = when {
        !c.enabled -> "Автоматизация MAX выключена"
        !c.unlocked -> "Экран выключен или заблокирован"
        c.packageName != PACKAGE -> "На экране не MAX"
        !c.versionMatches -> "MAX обновился или профиль интерфейса не настроен"
        c.headerCount != 1 || c.headerPhone == null || !MaxSearchPolicy.equivalent(c.headerPhone, c.expectedPhone) -> "Номер в шапке чата не совпал. По имени получателя не выбираем"
        c.inputCount != 1 -> "Поле сообщения не определено однозначно"
        c.draft.isNotEmpty() -> "В чате уже есть черновик — он не будет заменён"
        else -> null
    }
}
