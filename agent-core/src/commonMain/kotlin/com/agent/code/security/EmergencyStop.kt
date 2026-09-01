package com.agent.code.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object EmergencyStop {
    private val _stopped = MutableStateFlow(false)
    val stopped: StateFlow<Boolean> = _stopped.asStateFlow()

    fun activate() { _stopped.value = true }
    fun reset() { _stopped.value = false }
    fun isStopped(): Boolean = _stopped.value

    fun check() {
        if (_stopped.value) throw IllegalStateException("Emergency stop activated")
    }
}
