package com.starpilot.galaxy

import android.content.Context
import android.net.NetworkCapabilities
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Use an internet network even while Galaxy has bound the app to comma's offline Wi-Fi. */
class DiscordRelayHttp(context: Context) : DiscordReportClient.Http {
    private val manager = Networks.manager(context.applicationContext)
    override fun send(method: String, url: String, headers: Map<String, String>, body: String?): DiscordReportClient.Response {
        require(url.startsWith(DiscordReportClient.BASE + "/"))
        val network = (listOfNotNull(manager.activeNetwork) + manager.allNetworks.toList()).firstOrNull {
            val caps = manager.getNetworkCapabilities(it)
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } ?: throw IOException("No internet connection")
        return DiscordReportClient.UrlConnectionHttp.sendOn(network.openConnection(URL(url)) as HttpURLConnection, method, headers, body)
    }
}
