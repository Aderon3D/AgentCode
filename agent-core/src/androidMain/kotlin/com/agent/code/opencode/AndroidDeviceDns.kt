package com.agent.code.opencode

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/**
 * Gives [PlatformOps] (an object with no Context) a way to read the device's
 * configured DNS servers. Set once from the Activity; left null in tests, where
 * [servers] returns empty and callers fall back to public resolvers.
 */
object AndroidDeviceDns {
    @Volatile
    var context: Context? = null

    fun servers(): List<String> {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return emptyList()
        val out = LinkedHashSet<String>()
        // Active network first; fall back to any network that has servers.
        val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
        for (n in networks) {
            val props = runCatching { cm.getLinkProperties(n) }.getOrNull() ?: continue
            for (addr in props.dnsServers) {
                // The shim is IPv4-only (dnsshim.c), so AAAA entries are useless.
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                out += addr.hostAddress ?: continue
            }
            if (out.isNotEmpty()) break
        }
        return out.toList()
    }
}
