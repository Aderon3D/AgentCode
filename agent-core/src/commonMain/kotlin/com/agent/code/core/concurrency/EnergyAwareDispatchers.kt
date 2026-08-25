package com.agent.code.core.concurrency

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

object EnergyAwareDispatchers {
    val EfficiencyIO: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(4)

    val ComputeBurst: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism((availableProcessors() - 2).coerceAtLeast(2))
}

internal expect fun availableProcessors(): Int
