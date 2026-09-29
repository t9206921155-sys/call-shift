package fi.callshift.app.domain

import org.junit.Assert.*
import org.junit.Test

class MaxProfileReturnPolicyTest {
    private val anchor = MaxProfileReturnPolicy.Anchor("header", "input", "TextView", "EditText", "shape")
    @Test fun recreatedNodesAreAllowedOnlyWithStableAnchorAndVerifiedPhone() {
        // A new instance of the same structural anchor does not carry old Android node IDs.
        val issue = MaxProfileReturnPolicy.issue(true, true, anchor, anchor.copy(), 1, "")
        assertNull(issue)
        assertTrue(MaxProfileReturnPolicy.authorize(true, issue, true))
        assertFalse(MaxProfileReturnPolicy.authorize(false, issue, true)) // namesake / wrong phone
        assertFalse(MaxProfileReturnPolicy.authorize(true, issue, false)) // partial return frame
        assertNotNull(MaxProfileReturnPolicy.issue(true, true, anchor, anchor.copy(toolbarShape = "changed"), 1, ""))
    }
    @Test fun eachRefusalIsSpecificAndCannotAuthorizeInput() {
        val cases = listOf(
            MaxProfileReturnPolicy.issue(false, true, anchor, anchor, 1, "") to MaxProfileReturnPolicy.Issue.WINDOW,
            MaxProfileReturnPolicy.issue(true, false, anchor, anchor, 1, "") to MaxProfileReturnPolicy.Issue.CAPTION,
            MaxProfileReturnPolicy.issue(true, true, anchor, null, 1, "") to MaxProfileReturnPolicy.Issue.LAYOUT,
            MaxProfileReturnPolicy.issue(true, true, anchor, anchor, 2, "") to MaxProfileReturnPolicy.Issue.EDITORS,
            MaxProfileReturnPolicy.issue(true, true, anchor, anchor, 1, "Текст") to MaxProfileReturnPolicy.Issue.DRAFT
        )
        for ((actual, expected) in cases) {
            assertEquals(expected, actual)
            assertFalse(MaxProfileReturnPolicy.authorize(true, actual, true))
        }
        for (other in listOf(anchor.copy(headerId = "other"), anchor.copy(editorId = "other"),
            anchor.copy(headerClass = "other"), anchor.copy(editorClass = "other")))
            assertEquals(MaxProfileReturnPolicy.Issue.LAYOUT, MaxProfileReturnPolicy.issue(true, true, anchor, other, 1, ""))
    }
}
