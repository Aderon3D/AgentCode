package com.agent.code.workspace

/**
 * Abstraction over Kotlin source parsing for AST-based analysis.
 * Implementations: TreeSitterBackend (JNI, android), StubKotlinParser (host tests).
 */
interface KotlinParser {
    /**
     * Parse source and return declaration symbols.
     */
    fun collectSymbolNames(source: String): List<SymbolInfo>

    /**
     * Get the smallest declaration node containing a byte offset.
     * Returns [startLine, endLine, startCol, endCol, startByte, endByte] or empty.
     */
    fun getNodeAtByte(source: String, byteOffset: Int): IntArray

    data class SymbolInfo(
        val name: String,
        val type: String,
        val startLine: Int,
        val endLine: Int,
    )
}
