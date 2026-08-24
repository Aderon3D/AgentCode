package com.agent.code.workspace

/**
 * TreeSitterBackend — JNI bridge to tree-sitter with Kotlin grammar.
 * Provides Kotlin source parsing for AST-based change-impact analysis.
 *
 * Usage:
 *   TreeSitterBackend.init()
 *   val symbols = TreeSitterBackend.collectSymbolNames(kotlinSource)
 *   TreeSitterBackend.destroy()
 */
object TreeSitterBackend : KotlinParser {

    init {
        System.loadLibrary("tree_sitter_jni")
    }

    @Volatile
    private var initialized = false

    fun init() {
        if (!initialized) {
            nativeInit()
            initialized = true
        }
    }

    fun destroy() {
        if (initialized) {
            nativeDestroy()
            initialized = false
        }
    }

    /**
     * Parse source and return S-expression of root node.
     */
    fun parse(source: String): String? {
        init()
        return nativeParse(source)
    }

    /**
     * Collect declaration symbols from Kotlin source.
     * Returns list of "name:type:startLine:endLine" strings.
     * Types: function_declaration, class_declaration, interface_declaration,
     *        object_declaration, property_declaration, val_declaration,
     *        var_declaration, type_alias
     */
    override fun collectSymbolNames(source: String): List<KotlinParser.SymbolInfo> {
        init()
        val raw = nativeCollectSymbolNames(source) ?: return emptyList()
        return raw.mapNotNull { entry ->
            val parts = entry.split(":")
            if (parts.size >= 4) {
                KotlinParser.SymbolInfo(
                    name = parts[0],
                    type = parts[1],
                    startLine = parts[2].toIntOrNull() ?: 0,
                    endLine = parts[3].toIntOrNull() ?: 0,
                )
            } else null
        }
    }

    /**
     * Collect symbol ranges as flat int array.
     * Returns [count, startLine, endLine, startCol, endCol, ...] per symbol.
     */
    fun collectSymbolRanges(source: String): IntArray {
        init()
        return nativeCollectSymbolRanges(source) ?: intArrayOf()
    }

    /**
     * Get the smallest node containing a byte offset.
     * Returns [startLine, endLine, startCol, endCol, startByte, endByte].
     */
    override fun getNodeAtByte(source: String, byteOffset: Int): IntArray {
        init()
        return nativeGetNodeAtByte(source, byteOffset) ?: intArrayOf()
    }

    // JNI declarations
    private external fun nativeInit()
    private external fun nativeDestroy()
    private external fun nativeParse(source: String): String?
    private external fun nativeCollectSymbolNames(source: String): Array<String>?
    private external fun nativeCollectSymbolRanges(source: String): IntArray?
    private external fun nativeGetNodeAtByte(source: String, byteOffset: Int): IntArray?

}
