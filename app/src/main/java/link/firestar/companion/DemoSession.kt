package link.firestar.companion

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayInputStream

class DemoSession(private val activity: MainActivity, private val exit: () -> Unit) {
    private val origin = "https://galaxy-demo.invalid"
    private var uplink = false
    private var gps = false
    private var push = false
    private val accepted = mutableSetOf<String>()
    val web = WebView(activity)
    private val api = DemoApi({ name -> runCatching { activity.assets.open("demo/api/$name").bufferedReader().use { it.readText() } }.getOrNull() })
    private val manager = activity.getSystemService(NotificationManager::class.java)
    private val stop = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { uplink = false; gps = false; manager.cancel(Notifications.DEMO_UPLINK); manager.cancel(Notifications.DEMO_GPS) }
    }
    init {
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(stop, IntentFilter(activity.packageName + ".DEMO_STOP"), Context.RECEIVER_NOT_EXPORTED)
        else activity.registerReceiver(stop, IntentFilter(activity.packageName + ".DEMO_STOP"))
        web.setBackgroundColor(Color.rgb(7, 7, 18))
        web.settings.javaScriptEnabled = true; web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = false; web.settings.allowContentAccess = false
        web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        web.settings.blockNetworkLoads = true
        web.addJavascriptInterface(Bridge(), "StarPilotApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = !allowed(request.url)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                if (!allowed(request.url)) return response("text/plain", byteArrayOf(), 403)
                val path = request.url.path ?: "/"
                if (path.contains("..") || path.contains('%') || path.contains('\\') || path.contains("//")) return response("text/plain", byteArrayOf(), 403)
                if (path.startsWith("/api/sentry/image/")) return response("image/jpeg", activity.assets.open("demo/sample.jpg").use { it.readBytes() })
                if (path.startsWith("/api/")) {
                    val reply = api.request(request.method, path)
                    return response("application/json", reply.body.toByteArray(), reply.status)
                }
                if (path == "/demo-fetch.js") return response("text/javascript", activity.assets.open("demo/demo-fetch.js").use { it.readBytes() })
                if (path == "/data/runtime.json") return response("application/json", "{\"schemaVersion\":1,\"monitor\":\"local\",\"instance\":\"demo\"}".toByteArray())
                return runCatching {
                    var bytes = activity.assets.open("demo/web/" + if (path == "/") "index.html" else path.removePrefix("/")).use { it.readBytes() }
                    if (path == "/" || path == "/index.html") bytes = String(bytes).replace("<head>", "<head><script src=\"/demo-fetch.js\"></script>").toByteArray()
                    response(when { path == "/" || path.endsWith(".html") -> "text/html"; path.endsWith(".js") -> "text/javascript"; path.endsWith(".css") -> "text/css"; path.endsWith(".json") -> "application/json"; path.endsWith(".svg") -> "image/svg+xml"; else -> java.net.URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream" }, bytes)
                }.getOrElse { response("text/plain", byteArrayOf(), 404) }
            }
        }
    }
    private fun allowed(uri: Uri) = uri.scheme == "https" && uri.host == "galaxy-demo.invalid" && uri.port == -1 && uri.userInfo == null
    private fun response(type: String, data: ByteArray, status: Int = 200) = WebResourceResponse(type, "UTF-8", status, if (status == 200) "OK" else "Unavailable", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(data))
    fun show(route: String = "/") {
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(7, 7, 18)) }
        layout.setOnApplyWindowInsetsListener { view, insets ->
            val bars = systemBarInsets(insets)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        layout.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL
            val spacing = (16 * resources.displayMetrics.density).toInt()
            setPadding(spacing, 0, spacing, 0)
            addView(TextView(activity).apply {
                setText(R.string.demo_title); textSize = 16f; setTextColor(Palette.ON); typeface = Palette.medium
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(TextView(activity).apply {
                setText(R.string.exit_demo); textSize = 14f; setTextColor(Palette.VIOLET_SOFT)
                contentDescription = activity.getString(R.string.exit_demo); isClickable = true; isFocusable = true
                setPadding(spacing, 0, 0, 0); setOnClickListener { exit() }
            })
        }, LinearLayout.LayoutParams(-1, (52 * activity.resources.displayMetrics.density).toInt()))
        layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f)); activity.setContentView(layout)
        web.loadUrl(origin + "/#" + route)
    }
    private fun disclosure(key: String, text: String, action: () -> Unit) {
        if (key in accepted) { action(); return }
        AlertDialog.Builder(activity).setTitle("Galaxy · demo").setMessage(text).setPositiveButton("Continue") { _, _ -> accepted.add(key); action() }.setNegativeButton("Cancel", null).show()
    }
    private fun notification(id: Int, text: String) {
        if (!SentryPush.permitted(activity)) {
            if (Build.VERSION.SDK_INT >= 33) activity.requestFeaturePermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10, "Notifications")
            return
        }
        manager.createNotificationChannel(NotificationChannel(Notifications.DEMO_CHANNEL, "Galaxy demo", NotificationManager.IMPORTANCE_DEFAULT))
        val stopIntent = PendingIntent.getBroadcast(activity, 6, Intent(activity.packageName + ".DEMO_STOP").setPackage(activity.packageName), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, Notification.Builder(activity, Notifications.DEMO_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(text)
            .setContentText("Simulated: no data or location is shared").addAction(Notification.Action.Builder(null, "Stop", stopIntent).build()).build())
    }
    private fun enableUplink(on: Boolean) {
        if (!on) { uplink = false; manager.cancel(Notifications.DEMO_UPLINK); return }
        disclosure("uplink", "Shares this phone's mobile data with your StarPilot device over its Wi-Fi hotspot. Only your StarPilot can connect, and a notification with Stop shows while it's active. Your carrier may count this as hotspot usage.") {
            uplink = true; notification(Notifications.DEMO_UPLINK, "Sharing mobile data with StarPilot (demo)")
        }
    }
    private fun enableGps() {
        disclosure("gps", "Galaxy sends this phone's location to your StarPilot device to improve navigation while driving. Location is only used while the Phone GPS notification is showing.") {
            if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                activity.requestFeaturePermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 9, "Precise location")
            else gpsGranted()
        }
    }
    fun gpsGranted() { gps = true; notification(Notifications.DEMO_GPS, "Sharing phone GPS (demo)") }
    fun testAlert() {
        val id = "demo-" + System.currentTimeMillis()
        if (SentryPush.receive(activity, mapOf("type" to "sentry", "kind" to "test", "eventId" to id, "label" to "Offline demo"), demo = true)) {
            val bitmap = activity.assets.open("demo/sample.jpg").use { android.graphics.BitmapFactory.decodeStream(it) }
            val open = PendingIntent.getActivity(activity, 8, Intent(activity, MainActivity::class.java).putExtra("demo", true).putExtra("openRoute", "/cameras/events"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(Notifications.SENTRY_BASE + (id.hashCode() and 0x3FF), Notification.Builder(activity, Notifications.SENTRY_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle("Galaxy test alert").setContentText("Offline demo")
                .setStyle(Notification.BigPictureStyle().bigPicture(bitmap)).setContentIntent(open).setAutoCancel(true).build())
        } else if (Build.VERSION.SDK_INT >= 33) activity.requestFeaturePermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10, "Notifications")
    }
    private fun remoteKey() {
        val field = EditText(activity).apply { hint = "DEMO000000000000:" + "0".repeat(64) }
        AlertDialog.Builder(activity).setTitle("Demo remote key").setView(field).setPositiveButton("Check") { _, _ ->
            val valid = field.text.toString().trim() == "DEMO000000000000:" + "0".repeat(64)
            Toast.makeText(activity, if (valid) "Demo key accepted" else "Use the documented demo key", Toast.LENGTH_LONG).show()
        }.setNegativeButton("Cancel", null).show()
    }
    fun options() {
        AlertDialog.Builder(activity).setTitle("Demo connections · all simulated")
            .setItems(arrayOf("Wi-Fi / LAN · demo connected", "Remote · demo ready", "Bluetooth · demo paired", "Phone internet: $uplink", "Phone GPS: $gps", "Send test sentry alert", "Add remote key")) { _, index ->
                when (index) { 3 -> enableUplink(!uplink); 4 -> enableGps(); 5 -> testAlert(); 6 -> remoteKey() }
            }.setNegativeButton("Close", null).show()
    }
    fun close() { runCatching { activity.unregisterReceiver(stop) }; manager.cancel(Notifications.DEMO_UPLINK); manager.cancel(Notifications.DEMO_GPS); web.removeJavascriptInterface("StarPilotApp"); web.stopLoading(); web.destroy() }
    // WebView calls this class through the StarPilotApp JavaScript interface.
    @Suppress("unused")
    inner class Bridge {
        @JavascriptInterface fun demo() = true
        @JavascriptInterface fun distribution() = BuildConfig.DISTRIBUTION
        @JavascriptInterface fun version() = BuildConfig.VERSION_NAME
        @JavascriptInterface fun bluetooth() = false
        @JavascriptInterface fun connectionOptions() { activity.runOnUiThread { options() } }
        @JavascriptInterface fun uplinkState() = JSONObject().put("enabled", uplink).put("autoStart", false).put("bytes", 0).toString()
        @JavascriptInterface fun setUplinkEnabled(on: Boolean): Boolean { activity.runOnUiThread { enableUplink(on) }; return !on || "uplink" in accepted }
        @JavascriptInterface fun sentryPush() = JSONObject().put("available", true).put("enabled", push).put("registered", push).toString()
        @JavascriptInterface fun setSentryPush(on: Boolean): Boolean { push = on; return true }
        @JavascriptInterface fun remoteKeyState() = "{\"present\":false,\"verified\":false}"
        @JavascriptInterface fun addRemoteKey() { activity.runOnUiThread { remoteKey() } }
        @JavascriptInterface fun demoRequest(method: String, path: String, raw: String): String {
            if (path == "/api/sentry/notifications" && method == "POST") {
                val action = runCatching { JSONObject(raw).optString("action") }.getOrDefault("")
                if (action in setOf("test", "simulate")) activity.runOnUiThread { testAlert() }
                val reply = api.request("GET", path)
                return JSONObject().put("status", reply.status).put("body", reply.body).toString()
            }
            val reply = api.request(method, path, raw) { old, next ->
                val value = JSONObject(old); val patch = JSONObject(next)
                patch.keys().forEach { key -> value.put(key, patch.get(key)) }; value.put("ok", true).toString()
            }
            return JSONObject().put("status", reply.status).put("body", reply.body).toString()
        }
    }
}
