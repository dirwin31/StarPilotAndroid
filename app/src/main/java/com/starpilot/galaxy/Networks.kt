package com.starpilot.galaxy

import android.content.Context
import android.net.*
import java.net.Inet4Address

object Networks {
    const val ORIGIN = "http://172.31.254.1:8082"
    const val PROTOCOL = 1
    fun manager(context: Context) = context.getSystemService(ConnectivityManager::class.java)
    fun wifi(context: Context, hotspotOnly: Boolean = true): Pair<Network, Inet4Address>? {
        val cm = manager(context)
        return cm.allNetworks.firstNotNullOfOrNull { network ->
            if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) return@firstNotNullOfOrNull null
            cm.getLinkProperties(network)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { it.isSiteLocalAddress && (!hotspotOnly || it.hostAddress!!.startsWith("172.31.254.")) }
                ?.let { network to it }
        }
    }
    /** A validated network with internet, comma's hotspot last. */
    fun internet(context: Context): Network? {
        val cm = manager(context)
        val hotspot = wifi(context)?.first
        return (listOfNotNull(cm.activeNetwork) + cm.allNetworks).sortedBy { it == hotspot }.firstOrNull {
            cm.getNetworkCapabilities(it)?.let { caps -> caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } == true
        }
    }
    /** The Wi-Fi network that reaches comma and its origin: the hotspot, else the LAN host the app saved when it found comma. */
    fun commaLan(context: Context): Pair<Network, String>? {
        val hotspot = wifi(context)
        val network = hotspot ?: wifi(context, false) ?: return null
        val saved = context.getSharedPreferences("companion", Context.MODE_PRIVATE).getString("host", "") ?: ""
        val origin = if (hotspot != null) ORIGIN else localOrigin(saved)?.takeIf { it != ORIGIN } ?: return null
        return network.first to origin
    }
    /** Accept what people type: "192.168.1.10", "192.168.1.10:8082" or a full URL. localOrigin still validates it. */
    fun typedAddress(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        val scheme = if ("://" in trimmed) trimmed.substringBefore("://") else "http"
        val rest = trimmed.substringAfter("://")
        val authority = rest.substringBefore('/')
        val path = rest.removePrefix(authority)
        return "$scheme://$authority${if (':' in authority) "" else ":8082"}$path"
    }
    fun tunnelOrigin(value: String, slug: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme == "https" && uri.host == "galaxy.firestar.link" && uri.port == -1 && uri.userInfo == null &&
            TunnelCredential.SLUG.matches(slug) && uri.rawPath.startsWith("/$slug/") &&
            uri.path.split('/').none { it == "." || it == ".." } && !uri.path.contains('\\') && !uri.path.contains("//") && !uri.path.contains('%')
    }.getOrDefault(false)
    fun localOrigin(value: String): String? {
        val uri = Uri.parse(value)
        if (uri.scheme != "http" || uri.port != 8082 || uri.userInfo != null) return null
        val octets = uri.host?.split('.')?.map { it.toIntOrNull() ?: return null } ?: return null
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        if (!(octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168))) return null
        return "http://${octets.joinToString(".")}:8082"
    }
}

class AndroidCellularTransport(private val network: Network) : CellularTransport {
    override fun resolve(host: String) = network.getAllByName(host)
    override fun socket() = network.socketFactory.createSocket()
    override fun datagram() = java.net.DatagramSocket(null).apply { network.bindSocket(this); bind(java.net.InetSocketAddress(0)) }
}
