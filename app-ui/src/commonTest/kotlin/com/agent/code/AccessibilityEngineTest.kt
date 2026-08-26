package com.agent.code

import com.agent.code.ui.StubAccessibilityEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccessibilityEngineTest {

    @Test
    fun `stub engine returns empty tree and succeeds actions`() = runTest {
        val engine = StubAccessibilityEngine()
        assertEquals("<root/>", engine.dumpSemanticTreeXml())
        assertTrue(engine.captureScreenshot() == null)
        assertTrue(engine.performClick(com.agent.code.ui.UiElementSelector()).isSuccess)
        assertTrue(
            engine.performInputText(
                com.agent.code.ui.UiElementSelector(),
                "hi"
            ).isSuccess
        )
        assertTrue(
            engine.performSwipe(0, 0, 10, 10, 100).isSuccess
        )
    }
}
