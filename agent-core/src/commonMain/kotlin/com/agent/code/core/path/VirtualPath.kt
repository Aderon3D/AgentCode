package com.agent.code.core.path

@JvmInline
@kotlinx.serialization.Serializable
value class VirtualPath private constructor(val rawPath: String) {
    val isAbsolute: Boolean get() = rawPath.startsWith("/") || WINDOWS_DRIVE_REGEX.matches(rawPath)
    val fileName: String get() = rawPath.substringAfterLast('/').substringAfterLast('\\')
    val extension: String get() = fileName.substringAfterLast('.', "")

    fun resolve(child: String): VirtualPath {
        val sanitizedChild = child.replace('\\', '/')
        val cleanBase = rawPath.trimEnd('/', '\\')
        return VirtualPath("$cleanBase/$sanitizedChild")
    }

    fun parent(): VirtualPath? {
        if (rawPath == "/") return null
        val lastSlash = maxOf(rawPath.lastIndexOf('/'), rawPath.lastIndexOf('\\'))
        return when {
            lastSlash < 0 -> null
            lastSlash == 0 -> VirtualPath("/")
            else -> {
                val base = rawPath.substring(0, lastSlash)
                if (base.matches(Regex("^[a-zA-Z]:$"))) VirtualPath("$base/") else VirtualPath(base)
            }
        }
    }

    companion object {
        private val WINDOWS_DRIVE_REGEX = Regex("^[a-zA-Z]:[/\\\\].*")
        fun of(path: String): VirtualPath = VirtualPath(path.replace('\\', '/'))
    }
}
