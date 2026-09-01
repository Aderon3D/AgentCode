package com.agent.code.security

object CommandAllowlist {
    private val interpreterPrefixes = setOf(
        "sh", "bash", "zsh", "env", "eval",
        "python", "python3", "ruby", "perl", "node", "php"
    )

    private val blockedCommands = setOf(
        "rm -rf /",
        "rm -rf /*",
        "mkfs",
        "dd if=",
        ":(){ :|:& };:",
        "chmod -R 777 /",
        "wget",
        "curl",
        "nc ",
        "ncat",
        "socat",
        "shutdown",
        "reboot",
        "halt",
        "poweroff",
        "init 0",
        "init 6",
        "kill -9 1",
        "killall",
        "pkill",
        "kill -1",
        "kill -2",
    )

    private val blockedPatterns = listOf(
        Regex("""rm\s+.*-rf\s+/\s"""),
        Regex("""rm\s+-[a-z]*r[a-z]*f"""),
        Regex(""">\s*/dev/sd"""),
        Regex("""mkfs\."""),
        Regex("""mount\s"""),
        Regex("""umount\s"""),
        Regex("""/etc/passwd"""),
        Regex("""/etc/shadow"""),
        Regex("""\.ssh/"""),
        Regex("""authorized_keys"""),
        Regex("""crontab"""),
        Regex("""systemctl"""),
        Regex("""service\s+\w+\s+stop"""),
        Regex("""kill\s+-9\s+\d+"""),
    )

    fun isAllowed(command: String): Boolean {
        val normalized = command.trim().lowercase()
        val firstToken = normalized.split("\\s+".toRegex()).firstOrNull() ?: return false
        if (firstToken in interpreterPrefixes) return false
        if (blockedCommands.any { normalized.startsWith(it) }) return false
        if (blockedPatterns.any { it.containsMatchIn(normalized) }) return false
        return true
    }

    fun reason(command: String): String? {
        val normalized = command.trim().lowercase()
        val firstToken = normalized.split("\\s+".toRegex()).firstOrNull() ?: return null
        if (firstToken in interpreterPrefixes) return "blocked interpreter: $firstToken"
        val blocked = blockedCommands.firstOrNull { normalized.startsWith(it) }
        if (blocked != null) return "blocked command: $blocked"
        val pattern = blockedPatterns.firstOrNull { it.containsMatchIn(normalized) }
        if (pattern != null) return "blocked pattern: $pattern"
        return null
    }
}
