package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MaxHeaderGesturePolicyTest {
    private val screen = MaxHeaderGesturePolicy.Rect(0, 0, 1080, 2400)
    private val title = MaxHeaderGesturePolicy.Rect(200, 100, 800, 180)
    @Test fun pointFollowsTitleNotSavedScreenCoordinates() {
        assertEquals(MaxHeaderGesturePolicy.Point(500f, 140f), MaxHeaderGesturePolicy.point(title, screen, 3f, emptyList()))
        val moved = MaxHeaderGesturePolicy.Rect(300, 200, 900, 280)
        assertEquals(MaxHeaderGesturePolicy.Point(600f, 240f), MaxHeaderGesturePolicy.point(moved, screen, 3f, emptyList()))
        assertNull(MaxHeaderGesturePolicy.point(title, screen, Float.NaN, emptyList()))
        assertNull(MaxHeaderGesturePolicy.point(title, screen, 0f, emptyList()))
    }
    @Test fun overlaysAndCompetingControlsRejectTap() {
        assertNull(MaxHeaderGesturePolicy.point(title, screen, 3f, listOf(screen)))
        assertNull(MaxHeaderGesturePolicy.point(title, screen, 3f, listOf(MaxHeaderGesturePolicy.Rect(490, 130, 550, 170))))
        // A keyboard below the title is not an obstacle to the title.
        assertNotNull(MaxHeaderGesturePolicy.point(title, screen, 3f, listOf(MaxHeaderGesturePolicy.Rect(0, 1200, 1080, 2400))))
    }
    @Test fun offScreenTinyOrNonHeaderRegionsRejectTap() {
        for (bad in listOf(MaxHeaderGesturePolicy.Rect(-1, 100, 800, 180),
            MaxHeaderGesturePolicy.Rect(200, -1, 800, 180), MaxHeaderGesturePolicy.Rect(200, 100, 1081, 180),
            MaxHeaderGesturePolicy.Rect(200, 801, 800, 881), MaxHeaderGesturePolicy.Rect(200, 100, 201, 180),
            MaxHeaderGesturePolicy.Rect(200, 100, 800, 101), MaxHeaderGesturePolicy.Rect(200, 100, 800, 600)))
            assertNull(bad.toString(), MaxHeaderGesturePolicy.point(bad, screen, 3f, emptyList()))
    }
    @Test fun injectedAcknowledgementDoesNotNeedManualSourceButIsBounded() {
        assertTrue(MaxHeaderGesturePolicy.expectedEvent(1200, 1000, 1100, 7, 7))
        // Android may omit both source and windowId. The injected tap still needs phone/return proof.
        assertTrue(MaxHeaderGesturePolicy.expectedEvent(1200, 1000, 1100, 7, -1))
        assertFalse(MaxHeaderGesturePolicy.expectedEvent(2600, 1000, 1100, 7, 7))
        assertFalse(MaxHeaderGesturePolicy.expectedEvent(1200, 1000, 999, 7, 7))
        assertFalse(MaxHeaderGesturePolicy.expectedEvent(1200, 1000, 1201, 7, 7))
        assertFalse(MaxHeaderGesturePolicy.expectedEvent(1200, 1000, 1100, 7, 8))
        assertFalse(MaxHeaderGesturePolicy.expectedEvent(1200, -1, 1100, 7, 7))
    }
    @Test fun gesturePermissionIsPartOfLearnedRuleNotImplicitInOldRules() {
        val old = MaxCardLearningPolicy.Rule(10, "header", "input", "header", "TextView", "shape")
        val gesture = old.copy(gesture = true)
        assertFalse(MaxCardLearningPolicy.matches(old, gesture))
        assertTrue(MaxCardLearningPolicy.matches(gesture, gesture.copy()))
        assertFalse(Json.decodeFromString<MaxCardLearningPolicy.Rule>(Json.encodeToString(old)).gesture)
        assertTrue(Json.decodeFromString<MaxCardLearningPolicy.Rule>(Json.encodeToString(gesture)).gesture)
        assertFalse(Json.encodeToString(gesture).contains("coordinate"))
        assertFalse(Json.encodeToString(gesture).contains("phone"))
    }
    @Test fun calibrationReturnUsesStableStructureNotRecycledNodeIds() {
        fun matches(window: Boolean = true, title: Boolean = true, editor: Boolean = true,
            caption: Boolean = true, count: Int = 1, empty: Boolean = true) =
            MaxCardLearningPolicy.trainingReturn(window, title, editor, caption, count, empty)
        assertTrue(matches())
        assertFalse(matches(window = false))
        assertFalse(matches(title = false))
        assertFalse(matches(editor = false))
        assertFalse(matches(caption = false))
        assertFalse(matches(count = 0))
        assertFalse(matches(count = 2))
        assertFalse(matches(empty = false))
    }
    @Test fun twoVerifiedCardsAndReturnsStillRequiredAfterAutomaticFirstTap() {
        val proof = MaxCardReplayProof()
        assertTrue(proof.manualCard("89991234567"))
        assertFalse(proof.canSave())
        assertTrue(proof.returned())
        assertTrue(proof.claimReplay())
        assertTrue(proof.replayCard("+79991234567"))
        assertFalse(proof.canSave())
        assertTrue(proof.returned())
        assertTrue(proof.canSave())
    }
}
