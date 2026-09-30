package fi.callshift.app.domain

/** One-time training of the MAX send control by a single deliberate user tap.
 * Stores only how the button is recognized and pressed (resource id, class, composer
 * shape, MAX version). Recipient, chat, message text and screen coordinates are never stored. */
object MaxSendTrainingPolicy {
    @kotlinx.serialization.Serializable
    data class Rule(val version: Long, val header: String, val input: String,
        val targetId: String, val targetClass: String, val shape: String, val gesture: Boolean)

    /** A clickable control captured in the send row next to the message editor. */
    data class Candidate(val id: String, val className: String, val clickable: Boolean)

    enum class Status(val explanation: String) {
        IDLE("Обучение кнопки отправки ещё не запускалось"),
        WAIT_CHAT("Откройте любой безопасный чат MAX, введите короткий текст и подождите подсказку"),
        WAIT_TAP("Черновик виден. Нажмите синюю стрелку отправки ОДИН раз"),
        VERIFY("Проверяем, что нажатие отправило сообщение"),
        SAVED("Кнопка отправки распознана и сохранена. Вернитесь в CallShift и запустите тестовую отправку"),
        NO_SERVICE("Служба MAX не подключена"),
        NO_LAYOUT("Сначала нужен сохранённый профиль чата для текущей версии MAX"),
        BUSY("MAX занят сценарием или другим обучением; сначала завершите его"),
        NO_EDITOR("Поле сообщения не распознано; обучение остановлено"),
        NO_CANDIDATES("Возле поля сообщения нет доступной кнопки; обучение остановлено"),
        FOREIGN("Нажатие не соответствует кнопке возле поля сообщения. Ничего не сохранено"),
        SOURCE_MISSING("Android не передал источник нажатия, а кнопку возле поля нельзя определить однозначно. Ничего не сохранено"),
        NOT_SENT("После нажатия текст не исчез — сообщение не ушло; ничего не сохранено"),
        TIMEOUT("Время обучения истекло; действие не сохранено"),
        STOPPED("Обучение остановлено; новое действие не сохранено"),
        ERROR("Ошибка обучения; новое действие не сохранено"),
    }

    enum class ResolveReason { SOURCE, SOURCE_ANCESTOR, CLASS_UNIQUE, SOLE_CANDIDATE, AMBIGUOUS, FOREIGN, EMPTY }
    data class Resolution(val reason: ResolveReason, val candidate: Candidate? = null)

    /** Identify which captured row control the click event pressed.
     * [chain] is the pressed node followed by its ancestors, nearest first, as
     * (resource id, class) pairs; a missing event source contributes its class only.
     * A missing source is accepted only when the answer stays unambiguous. */
    fun resolve(candidates: List<Candidate>, chain: List<Pair<String, String>>,
        sourcePresent: Boolean): Resolution {
        if (candidates.isEmpty()) return Resolution(ResolveReason.EMPTY)
        val clicked = chain.firstOrNull()
            ?: return if (candidates.size == 1) Resolution(ResolveReason.SOLE_CANDIDATE, candidates.single())
            else Resolution(ResolveReason.FOREIGN)
        if (!sourcePresent) {
            // No source node: the event class is the only usable hint, and only when unique.
            val byClass = candidates.filter { it.className == clicked.second }
            if (byClass.size == 1) return Resolution(ResolveReason.CLASS_UNIQUE, byClass.single())
            if (byClass.isEmpty() && candidates.size == 1)
                return Resolution(ResolveReason.SOLE_CANDIDATE, candidates.single())
            return Resolution(if (byClass.isEmpty()) ResolveReason.FOREIGN else ResolveReason.AMBIGUOUS)
        }
        val direct = candidates.filter { it.id == clicked.first && it.className == clicked.second }
        if (direct.size == 1) return Resolution(ResolveReason.SOURCE, direct.single())
        if (direct.size > 1) return Resolution(ResolveReason.AMBIGUOUS)
        // The pressed icon may be a non-clickable child of the actionable wrapper.
        for (step in chain.drop(1)) {
            val wrapped = candidates.filter { it.id == step.first && it.className == step.second }
            if (wrapped.size == 1) return Resolution(ResolveReason.SOURCE_ANCESTOR, wrapped.single())
            if (wrapped.size > 1) return Resolution(ResolveReason.AMBIGUOUS)
        }
        return Resolution(ResolveReason.FOREIGN)
    }

    /** Without a proven click event the action itself was never demonstrated,
     * so replay uses a real anchored tap instead of ACTION_CLICK. */
    fun replayGesture(reason: ResolveReason) = reason == ResolveReason.SOLE_CANDIDATE

    fun rule(version: Long, header: String, input: String,
        resolution: Resolution, shape: String): Rule? {
        val candidate = resolution.candidate ?: return null
        if (candidate.className.isBlank() || shape.isBlank() || version < 0 ||
            header.isBlank() || input.isBlank()) return null
        return Rule(version, header, input, candidate.id, candidate.className, shape,
            replayGesture(resolution.reason))
    }

    /** A saved rule replays only for the same MAX version and the same saved chat profile. */
    fun replayable(saved: Rule, version: Long, header: String, input: String): Boolean =
        saved.version >= 0 && saved.version == version && saved.header == header &&
            saved.input == input && saved.targetClass.isNotBlank() && saved.shape.isNotBlank()

    /** A single row control with exactly the learned identity; callers still deduplicate
     * parent/child duplicates and require composer geometry to match. */
    fun identityMatches(targetId: String, targetClass: String, id: String?, className: String?): Boolean =
        (id ?: "") == targetId && className.orEmpty() == targetClass
}
