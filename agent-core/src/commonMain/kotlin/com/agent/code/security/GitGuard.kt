package com.agent.code.security

object GitGuard {
    private val protectedBranches = setOf("main", "master", "release", "prod")

    private val blockedSubcommands = setOf(
        "push --force",
        "push -f",
        "push --force-with-lease",
        "reset --hard",
        "rebase --onto",
        "filter-branch",
        "commit --amend",
    )

    fun isAllowed(subcommand: String, args: String): GitDecision {
        val normalized = "$subcommand $args".trim().lowercase()

        for (blocked in blockedSubcommands) {
            if (normalized.startsWith(blocked)) {
                return GitDecision.Blocked("subcommand blocked: $blocked")
            }
        }

        if (subcommand == "push") {
            val targetBranch = extractTargetBranch(args)
            if (targetBranch != null && targetBranch in protectedBranches) {
                return GitDecision.Blocked("push to protected branch '$targetBranch' denied")
            }
        }

        if (subcommand == "checkout" || subcommand == "switch") {
            val target = extractTargetBranch(args)
            if (target != null && target in protectedBranches) {
                return GitDecision.Blocked("checkout of protected branch '$target' denied")
            }
        }

        if (subcommand == "branch" && args.contains("-D")) {
            return GitDecision.Blocked("force-delete branch denied")
        }

        return GitDecision.Allowed
    }

    private fun extractTargetBranch(args: String): String? {
        val parts = args.split("\\s+".toRegex()).filter { it.isNotBlank() }
        return parts.lastOrNull()
    }
}

sealed interface GitDecision {
    data object Allowed : GitDecision
    data class Blocked(val reason: String) : GitDecision
}
