package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath
import java.io.File

class RealFileSystem : FileSystemProvider {
    override fun read(path: VirtualPath): String =
        File(path.rawPath).readText()

    override fun write(path: VirtualPath, content: String) {
        val file = File(path.rawPath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override fun exists(path: VirtualPath): Boolean =
        File(path.rawPath).exists()
}
