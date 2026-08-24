package com.agent.code

import com.agent.code.core.lock.*
import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.KotlinParser
import com.agent.code.workspace.StubKotlinParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class M2TreeSitterTest {

    private val parser = StubKotlinParser()

    // --- Tier 2: AST slicing (computeImpactedSymbols) ---

    @Test
    fun tier2NoChangesReturnsEmpty() {
        val lockManager = WorkspaceLockManager()
        val funnel = SemanticConflictFunnel(lockManager, parser = parser)
        val impacted = funnel.computeImpactedSymbols("fun foo() = 1", "fun foo() = 1")
        assertTrue(impacted.isEmpty())
    }

    @Test
    fun tier2NewFunctionDetected() {
        val lockManager = WorkspaceLockManager()
        val funnel = SemanticConflictFunnel(lockManager, parser = parser)
        val impacted = funnel.computeImpactedSymbols("", "fun bar() = 2")
        // StubKotlinParser returns empty, so impacted is empty — expected for stub
        // Real parser would detect "bar"
        assertTrue(impacted.isEmpty())
    }

    @Test
    fun tier2WithRealParserDetectsChanges() {
        // Use a simple inline parser test — verify the funnel delegates to parser
        val lockManager = WorkspaceLockManager()
        val testParser = object : KotlinParser {
            override fun collectSymbolNames(source: String): List<KotlinParser.SymbolInfo> {
                // Fake: extract function names from "fun <name>" pattern
                val regex = Regex("""fun\s+(\w+)""")
                return regex.findAll(source).map { m ->
                    KotlinParser.SymbolInfo(m.groupValues[1], "function_declaration", 1, 1)
                }.toList()
            }
            override fun getNodeAtByte(source: String, byteOffset: Int) = intArrayOf()
        }
        val funnel = SemanticConflictFunnel(lockManager, parser = testParser)
        val impacted = funnel.computeImpactedSymbols("fun foo() = 1", "fun foo() = 1\nfun bar() = 2")
        assertEquals(setOf("bar"), impacted)
    }

    @Test
    fun tier2DetectsRemovedFunction() {
        val lockManager = WorkspaceLockManager()
        val testParser = object : KotlinParser {
            override fun collectSymbolNames(source: String): List<KotlinParser.SymbolInfo> {
                val regex = Regex("""fun\s+(\w+)""")
                return regex.findAll(source).map { m ->
                    KotlinParser.SymbolInfo(m.groupValues[1], "function_declaration", 1, 1)
                }.toList()
            }
            override fun getNodeAtByte(source: String, byteOffset: Int) = intArrayOf()
        }
        val funnel = SemanticConflictFunnel(lockManager, parser = testParser)
        val impacted = funnel.computeImpactedSymbols("fun foo() = 1\nfun bar() = 2", "fun foo() = 1")
        assertEquals(setOf("bar"), impacted)
    }

    // --- Tier 4: targeted test selection ---

    @Test
    fun tier4FindsReferencingTestFiles() {
        val lockManager = WorkspaceLockManager()
        val testParser = object : KotlinParser {
            override fun collectSymbolNames(source: String): List<KotlinParser.SymbolInfo> {
                // Fake: all identifiers in source are "symbols"
                val regex = Regex("""\b(\w+)\b""")
                return regex.findAll(source).take(5).map { m ->
                    KotlinParser.SymbolInfo(m.groupValues[1], "identifier", 1, 1)
                }.toList()
            }
            override fun getNodeAtByte(source: String, byteOffset: Int) = intArrayOf()
        }
        val funnel = SemanticConflictFunnel(lockManager, parser = testParser)

        val testFiles = mapOf(
            VirtualPath.of("/src/FooTest.kt") to "import com.foo.Foo\nfun testFoo() { Foo().bar() }",
            VirtualPath.of("/src/BarTest.kt") to "import com.bar.Baz\nfun testBaz() { Baz().qux() }",
            VirtualPath.of("/src/Foo.kt") to "class Foo { fun bar() = 1 }",
        )

        val targeted = funnel.findTargetedTests(setOf("Foo"), testFiles)
        // Should find FooTest.kt (references "Foo" in text)
        assertTrue(targeted.any { it.rawPath.contains("FooTest") })
    }

    @Test
    fun tier4EmptySymbolsReturnsEmpty() {
        val lockManager = WorkspaceLockManager()
        val funnel = SemanticConflictFunnel(lockManager, parser = parser)
        val targeted = funnel.findTargetedTests(emptySet(), emptyMap())
        assertTrue(targeted.isEmpty())
    }

    @Test
    fun tier4NoParserReturnsEmpty() {
        val lockManager = WorkspaceLockManager()
        val funnel = SemanticConflictFunnel(lockManager) // no parser
        val targeted = funnel.findTargetedTests(setOf("Foo"), mapOf(
            VirtualPath.of("/src/FooTest.kt") to "class FooTest { fun test() {} }"
        ))
        assertTrue(targeted.isEmpty())
    }

    // --- FileWatcher ---

    @Test
    fun fileWatcherDetectsCreate() = runBlocking {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "fw-test-${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val watcher = FileWatcher()
            val events = mutableListOf<Pair<VirtualPath, ChangeType>>()
            val latch = CompletableDeferred<Unit>()

            watcher.startWatching(VirtualPath.of(tmpDir.absolutePath)) { path, type ->
                events.add(path to type)
                if (events.size >= 1) latch.complete(Unit)
            }

            delay(200) // let watcher start
            File(tmpDir, "new-file.txt").writeText("hello")
            delay(500) // let event propagate

            assertTrue(events.isNotEmpty(), "Expected at least one event, got ${events.size}")
            assertTrue(events.any { it.second == ChangeType.CREATED })
            watcher.stopWatching()
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun fileWatcherStopWatching() {
        val watcher = FileWatcher()
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "fw-stop-${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val events = mutableListOf<Pair<VirtualPath, ChangeType>>()
            watcher.startWatching(VirtualPath.of(tmpDir.absolutePath)) { path, type ->
                events.add(path to type)
            }
            watcher.stopWatching()
            // After stop, no new events should arrive
            File(tmpDir, "after-stop.txt").writeText("nope")
            Thread.sleep(500)
            assertTrue(events.isEmpty(), "Expected no events after stop, got ${events.size}")
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
