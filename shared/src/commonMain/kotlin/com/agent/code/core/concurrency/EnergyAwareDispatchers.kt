package com.agent.code.core.concurrency

import kotlinx.coroutines.Dispatchers

object EnergyAwareDispatchers {
    val EfficiencyIO: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

    val ComputeBurst: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default.limitedParallelism(
        (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)
    )
}
