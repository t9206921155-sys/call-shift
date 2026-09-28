package fi.callshift.app.domain

/** Final transition checks shared by the service and regression tests. No guessed identity. */
object MaxTransitionPolicy {
    // A second Back is justified ONLY if the first one demonstrably dismissed the keyboard.
    fun keyboardReturn(wasVisible: Boolean, visible: Boolean, elapsed: Long,
        sameWindow: Boolean, sameNodes: Boolean, sameCaption: Boolean,
        editableCount: Int, empty: Boolean, password: Boolean): Boolean =
        wasVisible && !visible && elapsed in 0..4000 && sameWindow && sameNodes && sameCaption &&
            editableCount == 1 && empty && !password

    data class FinalClick(val allowed: Boolean, val unlocked: Boolean, val sameWindow: Boolean,
        val routeCurrent: Boolean, val layoutCurrent: Boolean, val expectedPhone: String,
        val actualPhone: String?, val expectedText: String, val actualText: String,
        val headerCount: Int, val editorCount: Int, val editableCount: Int,
        val password: Boolean, val sameButton: Boolean)

    fun canClick(c: FinalClick): Boolean = c.allowed && c.unlocked && c.sameWindow &&
        c.routeCurrent && c.layoutCurrent && MaxSearchPolicy.equivalent(c.actualPhone, c.expectedPhone) &&
        c.expectedText.isNotBlank() && c.actualText == c.expectedText && c.headerCount == 1 &&
        c.editorCount == 1 && c.editableCount == 1 && !c.password && c.sameButton
}
