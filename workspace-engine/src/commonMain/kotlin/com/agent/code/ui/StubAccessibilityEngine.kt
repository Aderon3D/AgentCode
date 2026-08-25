package com.agent.code.ui

// Host/JVM stub accessibility engine — no Android service available on JVM.
// Returns an empty semantic tree and succeeds all actions.
class StubAccessibilityEngine : AccessibilityEngine {
    override suspend fun dumpSemanticTreeXml(): String = "<root/>"
    override suspend fun captureScreenshot(): ByteArray? = null
    override suspend fun performClick(selector: UiElementSelector): Result<Unit> = Result.success(Unit)
    override suspend fun performInputText(selector: UiElementSelector, text: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun performSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long
    ): Result<Unit> = Result.success(Unit)
}
