package com.agent.code.core.journal

class InMemoryWalStore : WalStore {
    private val events = mutableListOf<String>()

    override fun append(serialized: String) {
        events.add(serialized)
    }

    override fun replay(): List<String> = events.toList()

    override fun clear() {
        events.clear()
    }
}
