package com.agent.code.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agent.code.core.tools.CircuitBreaker
import com.agent.code.provider.HierarchicalModelRouter
import com.agent.code.provider.LlmEvent
import com.agent.code.provider.LlmProvider
import com.agent.code.provider.LlmRequest
import com.agent.code.provider.ProviderRegistry
import com.agent.code.provider.TaskComplexity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * M3 cost-routing panel. Surfaces the [HierarchicalModelRouter] selection per
 * [TaskComplexity] so the operator can see which model each tier would dispatch.
 */
@Composable
fun CostRoutingPanel(router: HierarchicalModelRouter) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Cost Routing", fontWeight = FontWeight.Bold)
        for (c in TaskComplexity.entries) {
            val name = runCatching { router.selectModel(c).displayName }.getOrDefault("unavailable")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(c.name)
                Text(name)
            }
        }
    }
}

/** Host/demo router wired with stub providers covering every candidate id. */
fun demoModelRouter(): HierarchicalModelRouter {
    val registry = ProviderRegistry()
    listOf(
        "deepseek-coder", "claude-3-5-haiku", "qwen-2.5-coder", "omniroute-fast",
        "gpt-4o-mini", "claude-3-5-sonnet", "omniroute-mid",
        "claude-3-7-sonnet", "deepseek-r1", "gpt-4o", "omniroute-deep",
    ).forEach { id -> registry.register(StubProvider(id, id.replaceFirstChar { it.uppercase() })) }
    return HierarchicalModelRouter(registry, CircuitBreaker())
}

private class StubProvider(
    override val providerId: String,
    override val displayName: String,
) : LlmProvider {
    override fun streamCompletion(request: LlmRequest): Flow<LlmEvent> = emptyFlow()
    override suspend fun healthCheck(): Result<List<String>> = Result.success(emptyList())
}
