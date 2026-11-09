package com.starpilot.galaxy

import android.app.Activity
import android.content.Intent
import android.net.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class ShareActivity : Activity() {
    private val done = AtomicBoolean(false)
    private val resolved = AtomicBoolean(false)
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        status = TextView(this).apply { setText(R.string.reading_destination); setPadding(40, 100, 40, 40) }
        setContentView(status)
        val text = (intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.dataString ?: "").take(8192)
        val short = SharedDestination.SHORT_LINK.find(text)?.value
        if (short == null) { deliver(text); return }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Thread {
                    var result: String = short
                    try {
                        repeat(5) {
                            val url = URL(result)
                            require(url.protocol == "https" && SharedDestination.isGoogleMapsHost(url.host))
                            val connection = network.openConnection(url) as HttpURLConnection
                            try {
                                connection.instanceFollowRedirects = false; connection.connectTimeout = 1500; connection.readTimeout = 1500
                                val code = connection.responseCode
                                if (code in 300..399) {
                                    val next = connection.getHeaderField("Location") ?: return@repeat
                                    result = URL(url, next).toString()
                                }
                            } finally { connection.disconnect() }
                        }
                    } catch (_: Exception) { }
                    deliver(text.replace(short, result))
                }.start()
            }
        }
        callback = cb
        Networks.manager(this).requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
        handler.postDelayed({ deliver(text) }, 12000)
    }
    /** A place with coordinates goes straight to comma and starts the route; anything else opens in Galaxy. */
    private fun deliver(text: String) { runOnUiThread {
        if (done.get() || !resolved.compareAndSet(false, true)) return@runOnUiThread
        val place = SharedDestination.parse(text) as? SharedDestination.Place ?: return@runOnUiThread complete(text)
        status.text = getString(R.string.sending_destination, place.name)
        handler.postDelayed({ complete(text) }, 45000)
        Thread {
            val reason = CommaSender(this).send(place)
            runOnUiThread {
                if (done.get()) return@runOnUiThread
                if (reason != null) {
                    Toast.makeText(applicationContext, getString(R.string.destination_not_sent, reason), Toast.LENGTH_LONG).show()
                    return@runOnUiThread complete(text)
                }
                done.set(true)
                Toast.makeText(applicationContext, getString(R.string.destination_sent, place.name), Toast.LENGTH_LONG).show()
                finish()
            }
        }.start()
    } }
    private fun complete(text: String) { runOnUiThread {
        if (!done.compareAndSet(false, true)) return@runOnUiThread
        startActivity(Intent(this, MainActivity::class.java).putExtra("share", text).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    } }
    override fun onDestroy() {
        done.set(true); handler.removeCallbacksAndMessages(null)
        callback?.let { Networks.manager(this).unregisterNetworkCallback(it) }
        super.onDestroy()
    }
}
