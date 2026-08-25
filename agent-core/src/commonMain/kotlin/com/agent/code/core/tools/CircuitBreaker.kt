package com.agent.code.core.tools

class CircuitBreaker(private val openFor: Set<String> = emptySet()) {
    fun isOpen(providerId: String): Boolean = providerId in openFor
}
