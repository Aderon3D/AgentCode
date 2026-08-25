package com.agent.code.provider

class StreamingJsonStateMachine {
    private val stack = mutableListOf<Char>()
    private var inString = false
    private var isEscaped = false
    private var finished = false
    private var sawContainer = false
    private var errored = false

    val isComplete: Boolean get() = finished
    val hasError: Boolean get() = errored

    fun feed(chunk: String) {
        if (finished || errored) return
        for (c in chunk) {
            when {
                c == '"' && !isEscaped -> {
                    inString = !inString
                    isEscaped = false
                }
                !inString && c == '{' -> { stack.add('}'); sawContainer = true; isEscaped = false }
                !inString && c == '[' -> { stack.add(']'); sawContainer = true; isEscaped = false }
                !inString && (c == '}' || c == ']') -> {
                    if (stack.isEmpty() || stack.last() != c) {
                        errored = true
                        return
                    }
                    stack.removeAt(stack.lastIndex)
                    if (stack.isEmpty() && sawContainer) finished = true
                    isEscaped = false
                }
                c == '\\' && !isEscaped -> isEscaped = true
                else -> isEscaped = false
            }
        }
    }

    fun reset() {
        stack.clear()
        inString = false
        isEscaped = false
        finished = false
        sawContainer = false
        errored = false
    }
}
