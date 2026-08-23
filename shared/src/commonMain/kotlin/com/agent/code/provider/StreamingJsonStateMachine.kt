package com.agent.code.provider

class StreamingJsonStateMachine {
    private var containerDepth = 0
    private var inString = false
    private var isEscaped = false
    private var finished = false

    val isComplete: Boolean get() = finished

    fun feed(chunk: String) {
        if (finished) return
        for (c in chunk) {
            when {
                c == '"' && !isEscaped -> {
                    inString = !inString
                    isEscaped = false
                }
                !inString && (c == '{' || c == '[') -> {
                    containerDepth++
                    isEscaped = false
                }
                !inString && (c == '}' || c == ']') -> {
                    containerDepth--
                    if (containerDepth == 0) finished = true
                    isEscaped = false
                }
                c == '\\' && !isEscaped -> isEscaped = true
                else -> isEscaped = false
            }
        }
    }

    fun reset() {
        containerDepth = 0
        inString = false
        isEscaped = false
        finished = false
    }
}
