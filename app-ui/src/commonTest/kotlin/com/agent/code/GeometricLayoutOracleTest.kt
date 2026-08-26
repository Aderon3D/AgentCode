package com.agent.code

import com.agent.code.ui.GeometricLayoutOracle
import com.agent.code.ui.Rect
import com.agent.code.ui.UiElementNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeometricLayoutOracleTest {

    private val oracle = GeometricLayoutOracle()

    @Test
    fun `clean layout yields no bugs`() {
        val elements = listOf(
            UiElementNode("a", Rect(0, 0, 10, 10), Rect(0, 0, 100, 100), isDecorative = false),
            UiElementNode("b", Rect(20, 20, 30, 30), Rect(0, 0, 100, 100), isDecorative = false)
        )
        assertTrue(oracle.verifyLayoutCorrectness(elements).isEmpty())
    }

    @Test
    fun `element outside parent is flagged as clipping`() {
        val elements = listOf(
            UiElementNode("a", Rect(-5, 0, 10, 10), Rect(0, 0, 100, 100), isDecorative = false)
        )
        val bugs = oracle.verifyLayoutCorrectness(elements)
        assertEquals(1, bugs.size)
        assertTrue(bugs[0] is com.agent.code.ui.LayoutBug.Clipping)
        assertEquals("a", (bugs[0] as com.agent.code.ui.LayoutBug.Clipping).elementId)
    }

    @Test
    fun `overlapping non-decorative elements flagged`() {
        val elements = listOf(
            UiElementNode("a", Rect(0, 0, 20, 20), Rect(0, 0, 100, 100), isDecorative = false),
            UiElementNode("b", Rect(10, 10, 30, 30), Rect(0, 0, 100, 100), isDecorative = false)
        )
        val bugs = oracle.verifyLayoutCorrectness(elements)
        assertTrue(bugs.any { it is com.agent.code.ui.LayoutBug.Overlap })
    }

    @Test
    fun `decorative elements never flagged for overlap`() {
        val elements = listOf(
            UiElementNode("a", Rect(0, 0, 20, 20), Rect(0, 0, 100, 100), isDecorative = true),
            UiElementNode("b", Rect(10, 10, 30, 30), Rect(0, 0, 100, 100), isDecorative = true)
        )
        assertTrue(oracle.verifyLayoutCorrectness(elements).isEmpty())
    }
}
