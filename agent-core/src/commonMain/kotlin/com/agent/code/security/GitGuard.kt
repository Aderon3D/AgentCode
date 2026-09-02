package com.agent.code.security

object GitGuard {
    private val protectedBranches = setOf("main", "master", "release", "prod")

    private val blockedSubcommands = setOf(
        "push --force",
        "push -f",
        "push --forceall",
        "push --force-with-lease",
        "reset --hard",
        "rebase --onto",
        "filter-branch",
        "commit --amend",
    )

    fun isAllowed(subcommand: String, args: String): GitDecision {
        val normalized = "$subcommand $args".trim().lowercase()

        for (blocked in blockedSubcommands) {
            if (normalized == blocked || normalized.startsWith("$blocked ") || normalized.startsWith("$blocked=")) {
                return GitDecision.Blocked("subcommand blocked: $blocked")
            }
        }

        if (subcommand == "push") {
            val targetBranch = extractPushTarget(args)
            if (targetBranch != null && targetBranch in protectedBranches) {
                return GitDecision.Blocked("push to protected branch '$targetBranch' denied")
            }
        }

        if (subcommand == "checkout" || subcommand == "switch") {
            val target = extractCheckoutTarget(args)
            if (target != null && target in protectedBranches) {
                return GitDecision.Blocked("checkout of protected branch '$target' denied")
            }
        }

        if (subcommand == "branch" && args.contains("-D")) {
            return GitDecision.Blocked("force-delete branch denied")
        }

        return GitDecision.Allowed
    }

    private fun extractPushTarget(args: String): String? {
        val parts = args.split("\\s+".toRegex()).filter { it.isNotBlank() && !it.startsWith("-") }
        if (parts.size < 2) return parts.lastOrNull()
        val refspec = parts.last()
        return if (refspec.contains(":")) {
            refspec.substringAfter(":").removePrefix("refs/heads/")
        } else {
            refspec.removePrefix("refs/heads/")
        }
    }

    private fun extractCheckoutTarget(args: String): String? {
        val parts = args.split("\\s+".toRegex()).filter { it.isNotBlank() && !it.startsWith("-") }
        return parts.firstOrNull()
    }
}

sealed interface GitDecision {
    data object Allowed : GitDecision
    data class Blocked(val reason: String) : GitDecision
}
