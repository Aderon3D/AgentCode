package com.agent.code.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

object EnergyAwareDispatchers {
    val EfficiencyIO: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

    val ComputeBurst: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(
        (Runtime.getRuntime().availableProcessors() - 2).coerceAtLeast(2)
    )
}
