package com.agent.code.provider

import com.agent.code.core.tools.CircuitBreaker

enum class TaskComplexity { LOW_LINT_FORMAT, MEDIUM_CODE_EDIT, HIGH_ARCHITECTURAL_PLAN }

class ProviderRegistry {
    private val providers = mutableMapOf<String, LlmProvider>()

    fun register(provider: LlmProvider) {
        providers[provider.providerId] = provider
    }

    fun getProvider(id: String): LlmProvider? = providers[id]

    fun all(): List<LlmProvider> = providers.values.toList()
}

class HierarchicalModelRouter(
    private val providerRegistry: ProviderRegistry,
    private val circuitBreaker: CircuitBreaker
) {
    fun selectModel(complexity: TaskComplexity): LlmProvider {
        val candidates = when (complexity) {
            TaskComplexity.LOW_LINT_FORMAT ->
                listOf("deepseek-coder", "claude-3-5-haiku", "qwen-2.5-coder", "omniroute-fast")
            TaskComplexity.MEDIUM_CODE_EDIT ->
                listOf("gpt-4o-mini", "claude-3-5-sonnet", "omniroute-mid")
            TaskComplexity.HIGH_ARCHITECTURAL_PLAN ->
                listOf("claude-3-7-sonnet", "deepseek-r1", "gpt-4o", "omniroute-deep")
        }
        for (id in candidates) {
            val p = providerRegistry.getProvider(id) ?: continue
            if (circuitBreaker.isOpen(p.providerId)) continue
            return p
        }
        error("No available provider for complexity $complexity")
    }
}
