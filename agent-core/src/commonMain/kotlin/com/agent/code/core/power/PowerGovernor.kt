package com.agent.code.core.power

import com.agent.code.core.fsm.OperatingProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-adaptive power governor (§2.3 Development_Doc.md).
 * Monitors battery/thermal state and exposes an [OperatingProfile] via StateFlow.
 *
 * Host/JVM: always returns [OperatingProfile.BALANCED_BATTERY].
 * Android: observes BroadcastReceiver (battery) + PowerManager thermal listener.
 */
interface PowerGovernor {
    val currentProfile: StateFlow<OperatingProfile>
}
