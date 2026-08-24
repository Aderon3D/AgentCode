package com.agent.code.workspace

/**
 * Stub KotlinParser for host tests (no JNI available).
 * Returns empty results — Tier 2+4 pass through without AST analysis.
 */
class StubKotlinParser : KotlinParser {
    override fun collectSymbolNames(source: String): List<KotlinParser.SymbolInfo> = emptyList()
    override fun getNodeAtByte(source: String, byteOffset: Int): IntArray = intArrayOf()
}
