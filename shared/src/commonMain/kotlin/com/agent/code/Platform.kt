package com.agent.code

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform