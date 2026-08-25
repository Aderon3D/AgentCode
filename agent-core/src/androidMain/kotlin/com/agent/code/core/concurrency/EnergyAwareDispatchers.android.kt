package com.agent.code.core.concurrency

internal actual fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()
