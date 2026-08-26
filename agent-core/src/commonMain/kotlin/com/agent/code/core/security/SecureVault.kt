package com.agent.code.core.security

expect class SecureVault {
    suspend fun storeKey(alias: String, secret: String)
    suspend fun getKey(alias: String): String?
    suspend fun deleteKey(alias: String)
}
