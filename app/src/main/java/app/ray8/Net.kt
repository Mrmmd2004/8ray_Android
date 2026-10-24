package app.ray8

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

object Net {
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun isIp(s: String) = ipv4.matches(s) || (':' in s && s.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' })

    fun freePort(): Int = ServerSocket(0).use { it.localPort }

    fun portOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
        true
    } catch (_: Exception) {
        false
    }

    /** The real Wi-Fi / mobile network, never the VPN. */
    fun physicalNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    /** First IPv4 resolver of the physical network (so domestic sites resolve like they do without VPN). */
    fun systemDns(ctx: Context): String? = try {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = physicalNetwork(ctx)
        cm?.getLinkProperties(net)?.dnsServers
            ?.filterIsInstance<Inet4Address>()
            ?.mapNotNull { it.hostAddress }
            ?.firstOrNull { !it.startsWith("127.") && !it.startsWith("198.18.") && it != "0.0.0.0" }
    } catch (_: Exception) {
        null
    }

    /** Address used by sing-box to resolve names directly (outside the tunnel). */
    fun directDns(ctx: Context, s: Settings): String {
        val custom = s.directDns.trim()
        if (custom.isNotEmpty()) return custom
        return systemDns(ctx) ?: "1.1.1.1"
    }
}
