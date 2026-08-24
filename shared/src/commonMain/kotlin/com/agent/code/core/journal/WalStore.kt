package com.agent.code.core.journal

interface WalStore {
    fun append(serialized: String)
    fun replay(): List<String>
    fun clear()
    // ponytail: prune corrupt lines from the durable log; default no-op for
    // in-memory stores. Real backends override to rewrite the file.
    fun selfHeal(): Int = 0
}
