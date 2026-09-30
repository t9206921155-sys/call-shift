package fi.callshift.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaxSendTrainingPolicyTest {
    private val attach = MaxSendTrainingPolicy.Candidate("ru.oneme.app:id/attach", "android.widget.ImageView", true)
    private val send = MaxSendTrainingPolicy.Candidate("ru.oneme.app:id/send", "android.widget.ImageView", true)
    private fun chain(id: String?, cls: String?, ancestors: List<Pair<String, String>> = emptyList()) =
        listOf((id ?: "") to cls.orEmpty()) + ancestors

    @Test fun `direct source matches the pressed control`() {
        val r = MaxSendTrainingPolicy.resolve(listOf(attach, send), chain(send.id, send.className), true)
        assertEquals(MaxSendTrainingPolicy.ResolveReason.SOURCE, r.reason)
        assertEquals(send, r.candidate)
    }

    @Test fun `pressed icon inside a clickable wrapper resolves to the wrapper`() {
        val icon = "ru.oneme.app:id/icon" to "android.widget.ImageView"
        val r = MaxSendTrainingPolicy.resolve(listOf(attach, send),
            chain(icon.first, icon.second, listOf(send.id to send.className, "root" to "android.widget.FrameLayout")), true)
        assertEquals(MaxSendTrainingPolicy.ResolveReason.SOURCE_ANCESTOR, r.reason)
        assertEquals(send, r.candidate)
    }

    @Test fun `tap outside the composer row is foreign`() {
        val r = MaxSendTrainingPolicy.resolve(listOf(attach, send),
            chain("ru.oneme.app:id/back", "android.widget.ImageView"), true)
        assertEquals(MaxSendTrainingPolicy.ResolveReason.FOREIGN, r.reason)
        assertNull(r.candidate)
    }

    @Test fun `identical twins in the row can not be told apart`() {
        val r = MaxSendTrainingPolicy.resolve(listOf(send, send.copy(clickable = false)),
            chain(send.id, send.className), true)
        assertEquals(MaxSendTrainingPolicy.ResolveReason.AMBIGUOUS, r.reason)
    }

    @Test fun `missing source recovers by unique class`() {
        val text = MaxSendTrainingPolicy.Candidate("ru.oneme.app:id/send", "com.max.ui.SendButton", true)
        val r = MaxSendTrainingPolicy.resolve(listOf(attach, text), chain(null, "com.max.ui.SendButton"), false)
        assertEquals(MaxSendTrainingPolicy.ResolveReason.CLASS_UNIQUE, r.reason)
        assertEquals(text, r.candidate)
        assertFalse(MaxSendTrainingPolicy.replayGesture(r.reason))
    }

    @Test fun `missing source with same class twins stays unresolved`() {
        val r = MaxSendTrainingPolicy.resolve(listOf(attach, send, send.copy(clickable = false)),
            chain(null, send.className), false)
        assertEquals(MaxSendTrainingPolicy.ResolveReason.AMBIGUOUS, r.reason)
        assertNull(r.candidate)
    }

    @Test fun `missing source falls back to a single row control`() {
        val r = MaxSendTrainingPolicy.resolve(listOf(send), chain(null, "android.view.View"), false)
        assertEquals(MaxSendTrainingPolicy.ResolveReason.SOLE_CANDIDATE, r.reason)
        assertEquals(send, r.candidate)
        assertTrue(MaxSendTrainingPolicy.replayGesture(r.reason))
    }

    @Test fun `class recovery applies to a single clickable with blank id`() {
        val bare = MaxSendTrainingPolicy.Candidate("", "com.max.ui.SendButton", true)
        val r = MaxSendTrainingPolicy.resolve(listOf(bare), chain(null, "com.max.ui.SendButton"), false)
        assertEquals(MaxSendTrainingPolicy.ResolveReason.CLASS_UNIQUE, r.reason)
        assertEquals(bare, r.candidate)
    }

    @Test fun `missing source with several unknown controls is rejected`() {
        val r = MaxSendTrainingPolicy.resolve(listOf(attach, send), chain(null, "android.view.View"), false)
        assertEquals(MaxSendTrainingPolicy.ResolveReason.FOREIGN, r.reason)
    }

    @Test fun `empty row is never resolvable`() {
        val r = MaxSendTrainingPolicy.resolve(emptyList(), chain(send.id, send.className), true)
        assertEquals(MaxSendTrainingPolicy.ResolveReason.EMPTY, r.reason)
    }

    @Test fun `rule is built with tap replay only for unproven actions`() {
        val proven = MaxSendTrainingPolicy.rule(6840, "h", "i",
            MaxSendTrainingPolicy.resolve(listOf(attach, send), chain(send.id, send.className), true), "shape")
        assertEquals(MaxSendTrainingPolicy.Rule(6840, "h", "i", send.id, send.className, "shape", false), proven)
        val unproven = MaxSendTrainingPolicy.rule(6840, "h", "i",
            MaxSendTrainingPolicy.resolve(listOf(send), chain(null, "android.view.View"), false), "shape")
        assertEquals(MaxSendTrainingPolicy.Rule(6840, "h", "i", send.id, send.className, "shape", true), unproven)
    }

    @Test fun `rule needs identity and shape`() {
        assertNull(MaxSendTrainingPolicy.rule(6840, "h", "i",
            MaxSendTrainingPolicy.resolve(listOf(MaxSendTrainingPolicy.Candidate("", "", true)), chain(null, ""), false), "shape"))
        assertNull(MaxSendTrainingPolicy.rule(6840, "h", "i",
            MaxSendTrainingPolicy.resolve(listOf(send), chain(null, "android.view.View"), false), ""))
        assertNull(MaxSendTrainingPolicy.rule(-1, "h", "i",
            MaxSendTrainingPolicy.resolve(listOf(send), chain(null, "android.view.View"), false), "shape"))
    }

    @Test fun `replay is bound to the trained version and profile`() {
        val rule = MaxSendTrainingPolicy.Rule(6840, "h1", "i1", send.id, send.className, "shape", false)
        assertTrue(MaxSendTrainingPolicy.replayable(rule, 6840, "h1", "i1"))
        assertFalse(MaxSendTrainingPolicy.replayable(rule, 6841, "h1", "i1"))
        assertFalse(MaxSendTrainingPolicy.replayable(rule, 6840, "h2", "i1"))
        assertFalse(MaxSendTrainingPolicy.replayable(rule, 6840, "h1", "i2"))
        assertFalse(MaxSendTrainingPolicy.replayable(rule.copy(targetClass = ""), 6840, "h1", "i1"))
        assertFalse(MaxSendTrainingPolicy.replayable(rule.copy(shape = ""), 6840, "h1", "i1"))
    }

    @Test fun `identity comparison treats a missing id as empty`() {
        assertTrue(MaxSendTrainingPolicy.identityMatches("", send.className, null, send.className))
        assertTrue(MaxSendTrainingPolicy.identityMatches(send.id, send.className, send.id, send.className))
        assertFalse(MaxSendTrainingPolicy.identityMatches(send.id, send.className, attach.id, send.className))
        assertFalse(MaxSendTrainingPolicy.identityMatches(send.id, send.className, send.id, "android.widget.TextView"))
    }
}
