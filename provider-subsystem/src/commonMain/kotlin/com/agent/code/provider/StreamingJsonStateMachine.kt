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
            if (!consume(c)) return
        }
    }

    // Feeds one character through the state machine; returns false when a
    // bracket mismatch stops the feed (the caller then returns).
    private fun consume(c: Char): Boolean {
        when {
            c == '"' && !isEscaped -> {
                inString = !inString
                isEscaped = false
            }
            !inString && (c == '{' || c == '[') -> openContainer(c)
            !inString && (c == '}' || c == ']') -> return closeContainer(c)
            c == '\\' && !isEscaped -> isEscaped = true
            else -> isEscaped = false
        }
        return true
    }

    private fun openContainer(c: Char) {
        stack.add(if (c == '{') '}' else ']')
        sawContainer = true
        isEscaped = false
    }

    private fun closeContainer(c: Char): Boolean {
        if (stack.isEmpty() || stack.last() != c) {
            errored = true
            return false
        }
        stack.removeAt(stack.lastIndex)
        if (stack.isEmpty() && sawContainer) finished = true
        isEscaped = false
        return true
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
