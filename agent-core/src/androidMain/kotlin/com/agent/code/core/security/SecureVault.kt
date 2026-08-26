package com.agent.code.core.security

// ponytail: host/Android in-memory store. Production must back with
// AndroidKeyStore (hardware-backed) so secrets are not resident in process
// memory; swap this map for KeyStore("AndroidKeyStore") + a wrapping key when
// device hardening is wired (see Development_Doc §12.1).
actual class SecureVault {
    private val store = mutableMapOf<String, String>()
    actual suspend fun storeKey(alias: String, secret: String) {
        store[alias] = secret
    }

    actual suspend fun getKey(alias: String): String? = store[alias]
    actual suspend fun deleteKey(alias: String) {
        store.remove(alias)
    }
}
