package com.starpilot.galaxy

import android.app.Activity
import android.content.Intent
import android.net.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** A small card over the maps app: comma finds the shared place and starts the route, or the place waits until comma is reachable. */
class ShareActivity : Activity() {
    private val sent = AtomicBoolean(false)
    private val attached = AtomicBoolean(true)
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private var text = ""
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (state != null) { finish(); return }
        val dp = resources.displayMetrics.density
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleSmall)
        status = TextView(this).apply { setTextColor(0xFFF3F2F8.toInt()); textSize = 16f; setPadding((14 * dp).toInt(), 0, 0, 0); setText(R.string.sending_destination) }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = getDrawable(R.drawable.dialog_surface)
            setPadding((36 * dp).toInt(), (34 * dp).toInt(), (36 * dp).toInt(), (34 * dp).toInt())
            setOnClickListener { finish() }
            addView(progress, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        setContentView(FrameLayout(this).apply {
            setOnClickListener { finish() }
            setOnApplyWindowInsetsListener { view, insets -> systemBarInsets(insets).let { view.setPadding(it.left, it.top, it.right, it.bottom) }; insets }
            addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
        text = SharedDestination.shareText(intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: intent.dataString ?: "")
        if (text.isBlank()) { finish(); return }
        val short = SharedDestination.SHORT_LINK.find(text)?.value ?: return send(text)
        val network = Networks.internet(this)
        if (network != null) Thread { send(text.replace(short, expand(network, short))) }.start() else {
            // No validated network yet: ask for mobile data, which also works while the phone is on comma's hotspot.
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { Thread { send(text.replace(short, expand(network, short))) }.start() }
            }
            callback = cb
            runCatching { Networks.manager(this).requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb) }
        }
        handler.postDelayed({ send(text) }, 12000)
    }

    private fun expand(network: Network, short: String): String {
        var result = short
        try {
            repeat(5) {
                val url = URL(result)
                require(url.protocol == "https" && SharedDestination.isGoogleMapsHost(url.host))
                val connection = network.openConnection(url) as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false; connection.connectTimeout = 1500; connection.readTimeout = 1500
                    if (connection.responseCode in 300..399) result = URL(url, connection.getHeaderField("Location") ?: return@repeat).toString()
                } finally { connection.disconnect() }
            }
        } catch (_: Exception) { }
        return result
    }

    private fun send(shared: String) {
        if (!sent.compareAndSet(false, true)) return
        PendingShare.share(this, shared) { outcome, reason ->
            if (attached.compareAndSet(true, false)) { runOnUiThread { result(outcome, reason, shared) }; true } else false
        }
        if (attached.get()) handler.postDelayed({ if (attached.compareAndSet(true, false)) show(getString(R.string.share_still_sending), 2500) }, 45000)
    }

    private fun result(outcome: CommaNavigation.Outcome?, reason: String, shared: String) {
        val message = when (outcome) {
            is CommaNavigation.Outcome.Started -> { ShareShortcut.publish(applicationContext); getString(R.string.destination_sent, outcome.name) }
            is CommaNavigation.Outcome.Refused -> getString(R.string.share_refused, outcome.message)
            CommaNavigation.Outcome.NeedsSearch -> {
                startActivity(Intent(applicationContext, MainActivity::class.java).putExtra("share", shared)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                return finish()
            }
            null -> getString(R.string.share_saved) + if (reason == CommaSender.UNREACHABLE) "" else "\n$reason"
        }
        if (isFinishing || isDestroyed) Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        else show(message, if (outcome is CommaNavigation.Outcome.Started) 1200 else 3500)
    }

    private fun show(message: String, finishAfter: Long) {
        progress.visibility = View.GONE
        status.text = message
        handler.postDelayed({ finish() }, finishAfter)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        attached.set(false)
        // Leaving before the short link opened still sends the place; the answer comes as a notification.
        if (text.isNotBlank()) send(text)
        callback?.let { runCatching { Networks.manager(this).unregisterNetworkCallback(it) } }
        super.onDestroy()
    }
}
