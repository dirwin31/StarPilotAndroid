package com.starpilot.galaxy

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.text.format.DateUtils
import android.view.Gravity
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Galaxy's settings screens shown without a comma, from the pages saved at the last connection.
 * Nothing here reaches the network: changes go into [OfflineChanges] and are applied on the next connection.
 */
class OfflineSession(private val activity: MainActivity, private val changes: OfflineChanges, private val exit: () -> Unit) {
    private val origin = "https://galaxy-offline.invalid"
    // Prefer the screens saved from this comma; the bundled copy is the fallback.
    private val saved: File? = GalaxyUpdates.store(activity).let { store -> store.readyRoot.also { store.inUseRoot = it } }
    private lateinit var status: TextView
    val web = WebView(activity)

    init {
        web.setBackgroundColor(Color.rgb(7, 7, 18))
        web.settings.javaScriptEnabled = true; web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false; web.settings.allowContentAccess = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.settings.blockNetworkLoads = true
        web.addJavascriptInterface(Bridge(), "StarPilotOffline")
        web.addJavascriptInterface(App(), "StarPilotApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !allowed(request.url)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (!allowed(request.url)) return response("text/plain", byteArrayOf(), 403)
                val path = request.url.path ?: "/"
                if (path.contains("..") || path.contains('%') || path.contains('\\') || path.contains("//")) return response("text/plain", byteArrayOf(), 403)
                // Page scripts are answered by offline-changes.js; anything else asking for the API gets nothing.
                if (path.startsWith("/api/")) return response("application/json", "{\"error\":\"Not available offline.\",\"code\":\"offline\"}".toByteArray(), 503)
                return runCatching {
                    var bytes = when (path) {
                        "/offline-changes.js" -> activity.assets.open("offline-changes.js").use { it.readBytes() }
                        "/data/runtime.json" -> "{\"schemaVersion\":1,\"monitor\":\"local\"}".toByteArray()
                        else -> (if (path == "/") "index.html" else path.removePrefix("/")).let { file ->
                            saved?.let { File(it, file).readBytes() } ?: activity.assets.open("demo/web/$file").use { it.readBytes() }
                        }
                    }
                    if (path == "/" || path == "/index.html") bytes = String(bytes).replace("<head>", "<head><script src=\"/offline-changes.js\"></script>").toByteArray()
                    response(when {
                        path == "/" || path.endsWith(".html") -> "text/html"
                        path.endsWith(".js") -> "text/javascript"
                        path.endsWith(".css") -> "text/css"
                        path.endsWith(".json") -> "application/json"
                        path.endsWith(".svg") -> "image/svg+xml"
                        else -> java.net.URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
                    }, bytes)
                }.getOrElse { response("text/plain", byteArrayOf(), 404) }
            }
        }
    }

    private fun allowed(uri: Uri) = uri.scheme == "https" && uri.host == "galaxy-offline.invalid" && uri.port == -1 && uri.userInfo == null
    private fun response(type: String, data: ByteArray, status: Int = 200) = WebResourceResponse(type, "UTF-8", status, if (status == 200) "OK" else "Unavailable", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(data))

    fun show() {
        val spacing = (16 * activity.resources.displayMetrics.density).toInt()
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(7, 7, 18)) }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val bars = systemBarInsets(insets)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        layout.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(spacing, 0, spacing, 0)
            status = TextView(activity).apply {
                textSize = 14f; setTextColor(Palette.ON); typeface = Palette.medium; maxLines = 2
                isClickable = true; isFocusable = true; setOnClickListener { review() }
            }
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(activity).apply {
                text = "Done"; textSize = 14f; setTextColor(Palette.VIOLET_SOFT); contentDescription = "Leave offline settings"
                isClickable = true; isFocusable = true; setPadding(spacing, spacing / 2, 0, spacing / 2); setOnClickListener { exit() }
            })
        }, LinearLayout.LayoutParams(-1, (56 * activity.resources.displayMetrics.density).toInt()))
        layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f)); activity.setContentView(layout)
        refresh()
        web.loadUrl("$origin/#/settings")
    }

    private fun refresh() {
        val target = changes.target() ?: return
        val name = target.name.ifEmpty { "StarPilot" }
        val age = DateUtils.getRelativeTimeSpanString(target.savedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        status.text = if (target.pending == 0) "Offline · $name settings as of $age" else
            "Offline · ${target.pending} change${if (target.pending == 1) "" else "s"} waiting for $name. Tap to review."
    }

    /** List what is waiting and let the person discard it. */
    private fun review() {
        val waiting = changes.describe()
        if (waiting.isEmpty()) return
        AlertDialog.Builder(activity).setTitle("Waiting to sync")
            .setMessage(waiting.joinToString("\n") + "\n\nThese are applied the next time the app connects to StarPilot.")
            .setPositiveButton("Keep", null)
            .setNegativeButton("Discard all") { _, _ -> changes.clear(); refresh(); web.reload() }.show()
    }

    fun close() {
        GalaxyUpdates.store(activity).inUseRoot = null
        web.removeJavascriptInterface("StarPilotOffline"); web.removeJavascriptInterface("StarPilotApp"); web.stopLoading(); web.destroy()
    }

    // WebView calls these classes through JavaScript interfaces.
    @Suppress("unused")
    inner class Bridge {
        @JavascriptInterface fun page(name: String) = changes.page(name)
        @JavascriptInterface fun saved(path: String) = changes.saved(path)
        @JavascriptInterface fun pending() = changes.pending()
        @JavascriptInterface fun enqueue(raw: String): Boolean = changes.enqueue(raw).also { activity.runOnUiThread { refresh() } }
        @JavascriptInterface fun drop(page: String, label: String, nth: Int) { changes.drop(page, label, nth); activity.runOnUiThread { refresh() } }
    }
    @Suppress("unused")
    inner class App {
        @JavascriptInterface fun distribution() = BuildConfig.DISTRIBUTION
        @JavascriptInterface fun version() = BuildConfig.VERSION_NAME
        @JavascriptInterface fun bluetooth() = false
        @JavascriptInterface fun connectionOptions() { activity.runOnUiThread { exit() } }
        @JavascriptInterface fun uplinkState() = "{\"enabled\":false,\"autoStart\":false,\"bytes\":0}"
        @JavascriptInterface fun sentryPush() = "{\"available\":false,\"enabled\":false,\"registered\":false}"
        @JavascriptInterface fun remoteKeyState() = "{\"present\":false,\"verified\":false}"
    }
}
