package com.agent.code.core.tools

import kotlin.time.DurationUnit
import kotlin.time.toDuration

class CircuitBreaker(private val openFor: Set<String> = emptySet()) {
    fun isOpen(providerId: String): Boolean = providerId in openFor
}

data class TaskSafetyBudget(
    val maxCostUsd: Double = 1.50,
    val maxToolCallsCount: Int = 40,
    val maxExecutionTimeMs: Long = 10 * 60 * 1000L
)

class BudgetTrackingCircuitBreaker(
    private val providerOpenFor: Set<String> = emptySet(),
    private val budget: TaskSafetyBudget = TaskSafetyBudget()
) {
    private var currentCostUsd = 0.0
    private var toolCallsCount = 0
    private val startMark = kotlin.time.TimeSource.Monotonic.markNow()

    fun isOpen(providerId: String): Boolean = providerId in providerOpenFor

    fun trackUsage(cost: Double) {
        currentCostUsd += cost
        if (currentCostUsd >= budget.maxCostUsd) throw CircuitBreakerException("Budget Limit Exceeded ($${currentCostUsd})")
    }

    fun incrementToolCall() {
        toolCallsCount++
        if (toolCallsCount >= budget.maxToolCallsCount) throw CircuitBreakerException("Tool Cap Reached ($toolCallsCount calls)")
    }

    fun checkTimeBudget() {
        val elapsed = startMark.elapsedNow()
        if (elapsed >= budget.maxExecutionTimeMs.toDuration(DurationUnit.MILLISECONDS))
            throw CircuitBreakerException("Time Budget Exceeded (${elapsed.inWholeMilliseconds}ms)")
    }

    fun snapshot() = BudgetSnapshot(currentCostUsd, toolCallsCount, budget)
}

data class BudgetSnapshot(val costUsd: Double, val toolCalls: Int, val budget: TaskSafetyBudget)

class CircuitBreakerException(message: String) : Exception(message)
