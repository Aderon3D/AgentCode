package com.agent.code.core.power

import com.agent.code.core.fsm.OperatingProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Host/JVM stub governor — always BALANCED. No battery/thermal signals on JVM.
 */
class StubPowerGovernor : PowerGovernor {
    override val currentProfile: StateFlow<OperatingProfile> =
        MutableStateFlow(OperatingProfile.BALANCED_BATTERY)
}
