package com.agent.code.ui

// §6.3 Development_Doc.md — Accessibility Engine contract (M4).
// Interface is platform-agnostic; Android provides a real impl via
// AccessibilityService, host/JVM uses StubAccessibilityEngine.

enum class UiActionType { CLICK, LONG_CLICK, TYPE_TEXT, SWIPE, CLEAR_TEXT }

data class UiElementSelector(
    val resourceId: String? = null,
    val textMatches: String? = null,
    val targetBoundsCenter: Pair<Int, Int>? = null
)

interface AccessibilityEngine {
    suspend fun dumpSemanticTreeXml(): String
    suspend fun captureScreenshot(): ByteArray?
    suspend fun performClick(selector: UiElementSelector): Result<Unit>
    suspend fun performInputText(selector: UiElementSelector, text: String): Result<Unit>
    suspend fun performSwipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long
    ): Result<Unit>
}
