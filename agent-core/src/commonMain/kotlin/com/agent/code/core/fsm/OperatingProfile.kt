package com.agent.code.core.fsm

/**
 * Thermal/power operating profile (§2.3 Development_Doc.md).
 * Drives concurrency limits and pacing in AgentOrchestrator.
 */
enum class OperatingProfile {
    /** Charging and cool: max concurrency, no pacing delay. */
    TURBO_PLUGGED,
    /** Battery >20% and normal temp: 1-2 concurrent agents, 50ms pacing. */
    BALANCED_BATTERY,
    /** Battery <20% or thermal severe: serialize steps, 500ms pacing. */
    ECO_PRESERVATION
}
