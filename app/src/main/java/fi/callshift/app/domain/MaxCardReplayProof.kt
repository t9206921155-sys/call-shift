package fi.callshift.app.domain

/** Ephemeral evidence for one learned UI action. Never persisted or used as a contact mapping. */
class MaxCardReplayProof {
    private enum class Phase { MANUAL_CARD, FIRST_RETURN, READY, REPLAY_CARD, FINAL_RETURN, COMPLETE, FAILED }
    private var phase = Phase.MANUAL_CARD
    private var phone: String? = null

    fun reset() { phase = Phase.MANUAL_CARD; phone = null }
    private fun fail(): Boolean { phase = Phase.FAILED; phone = null; return false }
    fun manualCard(value: String): Boolean {
        if (phase != Phase.MANUAL_CARD) return fail()
        phone = MaxUiPolicy.phone(value) ?: return fail()
        phase = Phase.FIRST_RETURN
        return true
    }
    /** Call only after window, structural identity, caption and empty editor were checked. */
    fun returned(): Boolean {
        phase = when (phase) {
            Phase.FIRST_RETURN -> Phase.READY
            Phase.FINAL_RETURN -> Phase.COMPLETE
            else -> return fail()
        }
        return true
    }
    /** Consume before ACTION_CLICK, including when Android reports failure. */
    fun claimReplay(): Boolean {
        if (phase != Phase.READY) return fail()
        phase = Phase.REPLAY_CARD
        return true
    }
    fun replayCard(value: String): Boolean {
        if (phase != Phase.REPLAY_CARD || !MaxSearchPolicy.equivalent(value, phone)) return fail()
        phase = Phase.FINAL_RETURN
        return true
    }
    fun canSave() = phase == Phase.COMPLETE
}
