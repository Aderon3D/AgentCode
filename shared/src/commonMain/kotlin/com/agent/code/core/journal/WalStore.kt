package com.agent.code.core.journal

interface WalStore {
    fun append(serialized: String)
    fun replay(): List<String>
    fun clear()
}
