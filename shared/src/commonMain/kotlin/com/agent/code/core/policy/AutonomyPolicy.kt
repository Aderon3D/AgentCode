package com.agent.code.core.policy

enum class AutonomyLevel { FULL_AUTONOMY, MICRO_AGENTIC }

data class AutonomyPolicy(
    val mode: AutonomyLevel = AutonomyLevel.FULL_AUTONOMY,
    val autoApproveFileEdits: Boolean = true,
    val autoApproveReadCommands: Boolean = true,
    val autoApproveSafeBuilds: Boolean = true,
    val maxAutoFixAttempts: Int = 5,
    val haltOnDestructiveCommands: Boolean = true
)
