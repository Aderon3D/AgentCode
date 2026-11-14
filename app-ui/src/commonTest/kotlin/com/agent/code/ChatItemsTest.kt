package com.agent.code

import com.agent.code.ui.ChatItem
import com.agent.code.ui.MdBlock
import com.agent.code.ui.UiEvent
import com.agent.code.ui.chatItems
import com.agent.code.ui.inlineMarkdown
import com.agent.code.ui.parseMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatItemsTest {

    @Test
    fun toolStartAndFinishMergeIntoOneRow() {
        val items = chatItems(
            listOf(
                UiEvent.UserMessage("list files"),
                UiEvent.Thinking("step 1 · mimo"),
                UiEvent.Message("On it."),
                UiEvent.ToolStarted("bash", """{"command":"ls"}"""),
                UiEvent.ToolFinished("bash", true, "a.txt\nb.txt"),
                UiEvent.Iteration(1),
                UiEvent.Complete("On it."),
            )
        )
        assertEquals(listOf("User", "Agent", "Tool", "Note"), items.map { it::class.simpleName })
        assertEquals("list files", (items[0] as ChatItem.User).text)
        assertEquals("On it.", (items[1] as ChatItem.Agent).text)
        val tool = items[2] as ChatItem.Tool
        assertEquals("bash", tool.name)
        assertEquals("""{"command":"ls"}""", tool.args)
        assertEquals(true, tool.ok)
        assertEquals("a.txt\nb.txt", tool.output)
        assertTrue((items[3] as ChatItem.Note).text == "done")
    }

    @Test
    fun failedTaskBecomesErrorNote() {
        val items = chatItems(listOf(UiEvent.Failed("boom")))
        assertEquals(1, items.size)
        val note = items[0] as ChatItem.Note
        assertEquals("boom", note.text)
        assertTrue(note.error)
    }

    @Test
    fun markdownBlocksSplitCodeHeadingAndBullets() {
        val blocks = parseMarkdown(
            """
            # Title
            Some **bold** text.

            - one
            - two

            ```
            val x = 1
            ```
            """.trimIndent()
        )
        assertEquals(5, blocks.size)
        assertEquals("Title", (blocks[0] as MdBlock.Heading).text)
        assertEquals(1, (blocks[0] as MdBlock.Heading).level)
        assertTrue((blocks[1] as MdBlock.Paragraph).text.contains("**bold**"))
        assertEquals("one", (blocks[2] as MdBlock.Bullet).text)
        assertEquals("two", (blocks[3] as MdBlock.Bullet).text)
        assertEquals("val x = 1", (blocks[4] as MdBlock.Code).code)
    }

    @Test
    fun inlineLinkKeepsLabelAndBoldIsStyled() {
        val s = inlineMarkdown("see [docs](https://x.dev) and **now**")
        assertEquals("see docs and now", s.text)
        assertTrue(s.spanStyles.isNotEmpty())
    }

    @Test
    fun unclosedMarkersKeepTextAndTerminate() {
        // Streaming can hand us a half-written marker; it must render as text,
        // never loop or swallow the rest of the reply.
        assertEquals("**half", inlineMarkdown("**half").text)
        assertEquals("`half", inlineMarkdown("`half").text)
        assertEquals("[half((", inlineMarkdown("[half((").text)
        assertEquals("*a", inlineMarkdown("*a").text)
        assertEquals("****", inlineMarkdown("****").text)
    }

    @Test
    fun crlfInputParsesLikeLf() {
        val blocks = parseMarkdown("# T\r\nbody\r\n\r\n- x\r\n")
        assertEquals("T", (blocks[0] as MdBlock.Heading).text)
        assertEquals("body", (blocks[1] as MdBlock.Paragraph).text)
        assertEquals("x", (blocks[2] as MdBlock.Bullet).text)
    }

    @Test
    fun unclosedCodeFenceKeepsRemainingLines() {
        val blocks = parseMarkdown("```\na\nb")
        assertEquals(1, blocks.size)
        assertEquals("a\nb", (blocks[0] as MdBlock.Code).code)
    }

    @Test
    fun hashtagIsTextNotHeading() {
        val blocks = parseMarkdown("#hashtag")
        assertEquals(1, blocks.size)
        assertEquals("#hashtag", (blocks[0] as MdBlock.Paragraph).text)
    }
}
