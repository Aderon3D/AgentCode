package com.agent.code

import com.agent.code.core.tools.ToolResult
import com.agent.code.ui.InspectUiTool
import com.agent.code.ui.InteractUiTool
import com.agent.code.ui.StubAccessibilityEngine
import com.agent.code.workspace.InMemoryFileSystem
import com.agent.code.workspace.StubProcessRunner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiToolsTest {

    private val engine = StubAccessibilityEngine()
    private val fs = InMemoryFileSystem()
    private val pr = StubProcessRunner()

    @Test
    fun `inspect returns semantic tree and succeeds`() = runTest {
        val result = InspectUiTool(engine).execute("{}", fs, pr)
        assertTrue(result.isSuccess)
        assertTrue(result.output.contains("<root"))
    }

    @Test
    fun `interact click dispatches and succeeds`() = runTest {
        val result = InteractUiTool(engine).execute(
            """{"action":"click","resourceId":"com.x:id/btn"}""", fs, pr
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `interact type dispatches input text`() = runTest {
        val result = InteractUiTool(engine).execute(
            """{"action":"type","text":"hello"}""", fs, pr
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun `interact rejects malformed json`() = runTest {
        val result = InteractUiTool(engine).execute("not json", fs, pr)
        assertFalse(result.isSuccess)
    }
}
