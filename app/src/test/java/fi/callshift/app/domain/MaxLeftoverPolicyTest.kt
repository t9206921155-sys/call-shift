package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxLeftoverPolicyTest {
    private val editor = "ru.oneme.app:id/composer_input"
    private val version = 9012L

    @Test fun keyBindsEditorVersionAndTextWithoutKeepingThem() {
        val a = MaxLeftoverPolicy.key(editor, version, "Тестовый ответ CallShift")
        val b = MaxLeftoverPolicy.key(editor, version, "Тестовый ответ CallShift")
        val otherText = MaxLeftoverPolicy.key(editor, version, "Другой текст")
        val otherEditor = MaxLeftoverPolicy.key("ru.oneme.app:id/other", version, "Тестовый ответ CallShift")
        val otherVersion = MaxLeftoverPolicy.key(editor, version + 1, "Тестовый ответ CallShift")
        assertEquals(a, b)
        for (different in listOf(otherText, otherEditor, otherVersion)) assertNotEquals(a, different)
        assertFalse(a.contains("CallShift"))
        assertFalse(a.contains(editor))
        assertTrue(a.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun onlyRegisteredAppTextCountsAsOwn() {
        val registry = MaxLeftoverPolicy.remember(emptyList(), MaxLeftoverPolicy.key(editor, version, "свой"))
        assertTrue(MaxLeftoverPolicy.isOwn(registry, MaxLeftoverPolicy.key(editor, version, "свой")))
        assertFalse(MaxLeftoverPolicy.isOwn(registry, MaxLeftoverPolicy.key(editor, version, "чужой")))
        assertFalse(MaxLeftoverPolicy.isOwn(emptyList(), MaxLeftoverPolicy.key(editor, version, "свой")))
    }

    @Test fun registryIsBoundedNewestFirstAndRefreshesRepeats() {
        var registry = emptyList<String>()
        val keys = (1..12).map { MaxLeftoverPolicy.key(editor, version, "текст-$it") }
        keys.forEach { registry = MaxLeftoverPolicy.remember(registry, it) }
        assertEquals(MaxLeftoverPolicy.LIMIT, registry.size)
        assertEquals(keys.last(), registry.first())
        assertFalse(registry.contains(keys.first()))
        registry = MaxLeftoverPolicy.remember(registry, keys[keys.size - 2])
        assertEquals(MaxLeftoverPolicy.LIMIT, registry.size)
        assertEquals(keys[keys.size - 2], registry.first())
        assertEquals(1, registry.count { it == keys[keys.size - 2] })
    }

    @Test fun forgetRemovesOnlyTheNamedKey() {
        val keep = MaxLeftoverPolicy.key(editor, version, "keep")
        val drop = MaxLeftoverPolicy.key(editor, version, "drop")
        val registry = MaxLeftoverPolicy.remember(MaxLeftoverPolicy.remember(emptyList(), keep), drop)
        val after = MaxLeftoverPolicy.forget(registry, drop)
        assertFalse(MaxLeftoverPolicy.isOwn(after, drop))
        assertTrue(MaxLeftoverPolicy.isOwn(after, keep))
        assertEquals(registry, MaxLeftoverPolicy.forget(registry, MaxLeftoverPolicy.key(editor, version, "absent")))
    }

    @Test fun limitIsClampedSoARunCanNeverGrowWithoutBound() {
        val registry = MaxLeftoverPolicy.remember(emptyList(), "k", limit = 0)
        assertEquals(listOf("k"), registry)
        assertEquals(64, MaxLeftoverPolicy.remember((1..80).map { "k$it" }, "new", limit = 500).size)
    }

    @Test fun draftRefusalPrefixKeepsItsClosedStopCode() {
        val d = MaxUiDiagnostics { 100 }
        d.start(1000)
        d.stopReason("В чате есть черновик — CallShift не трогает чужие тексты. Удалите текст из поля сообщения в MAX вручную и повторите. Если поле выглядит пустым, MAX не обозначил подсказку как подсказку — пришлите отчёт кнопкой «Скопировать всё для поддержки»")
        assertTrue(d.report(36, "0.8.55").contains("Stop reason=DRAFT_NOT_EMPTY"))
        d.stopReason("В открытом чате есть черновик — поиск не запускается. Удалите текст из поля сообщения в MAX вручную и повторите; поле CallShift не меняет")
        assertTrue(d.report(36, "0.8.55").contains("Stop reason=DRAFT_NOT_EMPTY"))
        d.stopReason("В чате есть чужой черновик") // a renamed prefix must not silently keep the code
        assertFalse(d.report(36, "0.8.55").contains("Stop reason=DRAFT_NOT_EMPTY"))
        d.clear()
        assertTrue(d.report(36, "0.8.55").contains("Stop reason=NONE"))
    }
}
