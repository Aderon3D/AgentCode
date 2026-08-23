package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath

interface FileSystemProvider {
    fun read(path: VirtualPath): String
    fun write(path: VirtualPath, content: String)
    fun exists(path: VirtualPath): Boolean
}
