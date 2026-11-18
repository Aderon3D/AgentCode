package com.agent.code.core.conversation

import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.FileNode
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.PatchOperation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileConversationStoreTest {

    private val file = VirtualPath.of("/files/conversation.jsonl")

    private class FakeFileSystem : FileSystemProvider {
        val files = mutableMapOf<String, String>()
        override suspend fun read(path: VirtualPath) = files[path.rawPath]
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("not found: ${path.rawPath}"))
        override suspend fun write(path: VirtualPath, content: String): Result<Unit> {
            files[path.rawPath] = content
            return Result.success(Unit)
        }
        override suspend fun exists(path: VirtualPath) = files.containsKey(path.rawPath)
        override suspend fun delete(path: VirtualPath): Result<Unit> {
            files.remove(path.rawPath)
            return Result.success(Unit)
        }
        override suspend fun applyPatch(path: VirtualPath, patches: List<PatchOperation>) =
            Result.failure<Unit>(UnsupportedOperationException())
        override suspend fun walkTree(root: VirtualPath, maxDepth: Int, ignorePatterns: List<String>) =
            Result.failure<FileNode.Directory>(UnsupportedOperationException())
    }

    @Test
    fun roundTripsTurnsAndNewestSession() = runTest {
        val fs = FakeFileSystem()
        val store = FileConversationStore(fs, file)
        store.append(Turn("first", listOf("""{"a":1}""")), "ses_1")
        store.append(Turn("second\nline", listOf("""{"b":2}""", """{"c":"x\ny"}""")), "ses_2")

        val loaded = store.load()

        assertEquals("ses_2", loaded.sessionId)
        assertEquals(listOf("first", "second\nline"), loaded.turns.map { it.goal })
        assertEquals(listOf("""{"a":1}"""), loaded.turns[0].lines)
        assertEquals(listOf("""{"b":2}""", """{"c":"x\ny"}"""), loaded.turns[1].lines)
    }

    @Test
    fun emptyAndClearedConversationsLoadEmpty() = runTest {
        val fs = FakeFileSystem()
        val store = FileConversationStore(fs, file)
        assertEquals(Conversation(null, emptyList()), store.load())

        store.append(Turn("hi", listOf("line")), "ses_1")
        store.clear()
        assertEquals(Conversation(null, emptyList()), store.load())
        assertFalse(fs.files.containsKey(file.rawPath))
    }

    @Test
    fun skipsCorruptLinesInsteadOfLosingTheConversation() = runTest {
        val fs = FakeFileSystem()
        fs.files[file.rawPath] = """{"goal":"kept","lines":["l"]}""" + "\n{not json\n"
        val loaded = FileConversationStore(fs, file).load()
        assertEquals(listOf("kept"), loaded.turns.map { it.goal })
    }

    @Test
    fun dropsOldestTurnsToStayUnderTheCap() = runTest {
        val fs = FakeFileSystem()
        val store = FileConversationStore(fs, file, maxChars = 200)
        repeat(5) { i -> store.append(Turn("goal-$i-" + "x".repeat(40), listOf("line")), "ses_$i") }

        val loaded = store.load()

        assertTrue(fs.files.getValue(file.rawPath).length <= 200, "file not capped")
        assertTrue(loaded.turns.size < 5, "expected old turns dropped, got ${loaded.turns.size}")
        assertTrue(loaded.turns.last().goal.startsWith("goal-4-"), "newest turn must survive")
    }
}
