package fi.callshift.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainTest {

    private val normalizer = PhoneNumberNormalizer(defaultRegion = "FI")

    @Test
    fun testDomesticEightNormalizesIndependentlyOfRegion() {
        for (region in listOf("FI", "RU", "US")) {
            val n = PhoneNumberNormalizer(region)
            for (raw in listOf("89161234567", "8 (916) 123-45-67", "tel:89161234567;123")) {
                assertEquals(n.normalize("+79161234567").e164, n.normalize(raw).e164)
                assertTrue(n.normalize(raw).isValid)
            }
        }
    }

    @Test
    fun testDomesticEightMatchesSavedRulesInBothDirections() {
        assertTrue(NumberMatcher.matches("8 (916) 123-45-67", "+79161234567"))
        assertTrue(NumberMatcher.matches("+79161234567", "89161234567"))
        assertTrue(NumberMatcher.matches("+79*", "89161234567"))
        assertFalse(NumberMatcher.matches("89161234567", "+79161234568"))
        assertFalse(NumberMatcher.matches("+89161234567", "+79161234567"))
        assertFalse(NumberMatcher.matches("8123", "7123"))
        assertFalse(NumberMatcher.matches("891612345678", "791612345678"))
    }

    @Test
    fun testNormalizeFinnishMobileNumber() {
        val result = normalizer.normalize("040 123 4567")
        assertEquals("+358401234567", result.e164)
        assertTrue(result.isValid)
        assertFalse(result.isEmergency)
    }

    @Test
    fun testNormalizeInternationalPlus() {
        val result = normalizer.normalize("+358401234567")
        assertEquals("+358401234567", result.e164)
        assertTrue(result.isValid)
    }

    @Test
    fun testEmergencyNumber112() {
        val result = normalizer.normalize("112")
        assertTrue(result.isEmergency)
    }

    @Test
    fun testEmergencyNumber911() {
        val result = normalizer.normalize("911")
        assertTrue(result.isEmergency)
    }

    @Test
    fun testBlankNumberReturnsNull() {
        val result = normalizer.normalize("   ")
        assertNull(result.e164)
        assertFalse(result.isValid)
    }

    @Test
    fun testMaskNumber() {
        assertEquals("unknown", normalizer.mask(null))
        assertEquals("****", normalizer.mask("1234"))
        val masked = normalizer.mask("+358401234567")
        assertTrue(masked.endsWith("567"))
        assertTrue(masked.contains("*"))
    }

    // --- NumberMatcher ---

    @Test
    fun testExactMatch() {
        assertTrue(NumberMatcher.matches("+358401234567", "+358401234567"))
        assertTrue(NumberMatcher.matches("+358 40 123 4567", "+358401234567"))
        assertFalse(NumberMatcher.matches("+358401234567", "+358409999999"))
    }

    @Test
    fun testPrefixWildcard() {
        assertTrue(NumberMatcher.matches("+35840*", "+358401234567"))
        assertTrue(NumberMatcher.matches("+35840*", "+35840000"))
        assertFalse(NumberMatcher.matches("+35840*", "+358501234567"))
    }

    @Test
    fun testSuffixWildcard() {
        assertTrue(NumberMatcher.matches("*4567", "+358401234567"))
        assertFalse(NumberMatcher.matches("*4567", "+358401234568"))
    }

    @Test
    fun testSingleDigitWildcard() {
        assertTrue(NumberMatcher.matches("+35840?", "+358401"))
        assertFalse(NumberMatcher.matches("+35840?", "+3584012"))
    }

    @Test
    fun testMatchesAny() {
        val list = listOf("+35840*", "+35850*")
        assertTrue(NumberMatcher.matchesAny(list, "+358401234567"))
        assertTrue(NumberMatcher.matchesAny(list, "+358509999999"))
        assertFalse(NumberMatcher.matchesAny(list, "+358601234567"))
    }

    @Test
    fun testValidPatternCheck() {
        assertTrue(NumberMatcher.isValidPattern("+35840*"))
        assertTrue(NumberMatcher.isValidPattern("*4567"))
        assertTrue(NumberMatcher.isValidPattern("+35840?"))
        assertFalse(NumberMatcher.isValidPattern(""))
        assertFalse(NumberMatcher.isValidPattern("abc*"))
    }

    // --- ScheduleMatcher ---

    @Test
    fun testScheduleDayMatch() {
        assertTrue(ScheduleMatcher.dayMatches(emptyList(), 1))
        assertTrue(ScheduleMatcher.dayMatches(listOf(1, 2, 3), 1))
        assertFalse(ScheduleMatcher.dayMatches(listOf(2, 3), 1))
    }

    @Test
    fun testScheduleTimeDayWindow() {
        val noon = java.time.LocalTime.of(12, 0)
        assertTrue(ScheduleMatcher.timeMatches("09:00", "18:00", noon))
        val night = java.time.LocalTime.of(22, 0)
        assertFalse(ScheduleMatcher.timeMatches("09:00", "18:00", night))
    }

    @Test
    fun testScheduleTimeMidnightWindow() {
        val lateNight = java.time.LocalTime.of(23, 0)
        val earlyMorning = java.time.LocalTime.of(5, 0)
        val afternoon = java.time.LocalTime.of(14, 0)

        assertTrue(ScheduleMatcher.timeMatches("22:00", "07:00", lateNight))
        assertTrue(ScheduleMatcher.timeMatches("22:00", "07:00", earlyMorning))
        assertFalse(ScheduleMatcher.timeMatches("22:00", "07:00", afternoon))
    }

    // --- DtmfTransmitter (M2) ---

    @Test
    fun testDtmfBuildSequence() {
        val seq = DtmfTransmitter.buildSequence("+358401234567")
        assertEquals("358401234567#", seq)
    }

    @Test
    fun testDtmfBuildSequenceWithPrefix() {
        val seq = DtmfTransmitter.buildSequence("+358401234567", prefix = "*9")
        assertEquals("*9358401234567#", seq)
    }

    @Test
    fun testDtmfBuildSequenceNullOnBlank() {
        assertNull(DtmfTransmitter.buildSequence(""))
        assertNull(DtmfTransmitter.buildSequence(null))
    }

    @Test
    fun testDtmfToChars() {
        val chars = DtmfTransmitter.toChars("123#")
        assertEquals(listOf('1', '2', '3', '#'), chars)
    }
}
