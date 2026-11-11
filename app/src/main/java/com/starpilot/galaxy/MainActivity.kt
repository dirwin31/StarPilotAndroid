package com.starpilot.galaxy

import com.google.zxing.integration.android.IntentIntegrator
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.content.pm.PackageManager
import java.net.HttpURLConnection
import java.util.Base64
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Inet4Address

class MainActivity : Activity() {
    @Volatile private var web: WebView? = null
    private val filePicker by lazy { GalaxyFilePicker(this, { web === it }, ::message) }
    private var ble: BleTransport? = null
    private val connectionGeneration = AtomicInteger()
    @Volatile private var bluetoothMode = false
    private var bluetoothAttemptAutomatic = false
    private val pairing by lazy { PairingStore(this) }
    private val jobs = ConcurrentHashMap<String, FutureTask<Unit>>()
    private val probes = ConcurrentHashMap<Int, HttpURLConnection>()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16))
    private val cachedOrigin = "https://galaxy-app.invalid"
    private var screen: ConnectScreen? = null
    private val recovery = ConnectionRecovery({ discoverLan(it) }, { connect() }, { stopDiscovery() })
    private val handler = Handler(Looper.getMainLooper())
    private var pageWatchdog: Runnable? = null
    private var resumed = false
    private val knownWifi = mutableSetOf<Network>()
    private var automaticLanNetwork: Network? = null
    private val wifiWatcher = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
            if (network in knownWifi || properties.linkAddresses.none { it.address is Inet4Address && it.address.isSiteLocalAddress }) return
            knownWifi.add(network)
            PendingShare.deliver(this@MainActivity)
            // Open Galaxy as soon as the phone joins a Wi-Fi network, without waiting for "Try again".
            handler.post {
                val mayPreemptBluetooth = automaticWifiAllowed() && bluetoothAttemptAutomatic && screen?.state(Route.BLUETOOTH) == RouteState.TRYING
                if (idle() || mayPreemptBluetooth || automaticLanNetwork != null) connect() else scheduleUpgrade()
            }
        }
        override fun onLost(network: Network) { knownWifi.remove(network); handler.post {
            if (automaticLanNetwork == network) { connect(); return@post }
            upgradeDiscovery?.close(); upgradeDiscovery = null
            if (activeRoute in setOf(Route.GALAXY_WIFI, Route.HOME_NETWORK) && Networks.wifi(this@MainActivity, false) == null) {
                if (!fallbackFrom(activeRoute)) disconnected()
            } else if (activeRoute in setOf(Route.GALAXY_WIFI, Route.HOME_NETWORK)) checkRouteHealth(force = true)
        } }
    }

    private val tunnel by lazy { TunnelStore(this) }
    private var activeRoute: Route? = null
    private var demoSession: DemoSession? = null
    private var routeHash = "/"
    private val routeDetails = mutableMapOf<Route, String>()
    private fun pageHash(): String {
        val requested = intent.getStringExtra("openRoute")?.takeIf { it.startsWith("/") && it.length <= 4096 }
        intent.removeExtra("openRoute")
        if (requested != null) routeHash = requested
        return routeHash
    }
    private var routeError = ""
    private val pollSentry = object : Runnable {
        override fun run() {
            if (resumed && web != null && pairProgress == null) SentryPush.api?.let { current -> Thread { runCatching { SentryPoller.poll(this@MainActivity, current) } }.start() }
            handler.postDelayed(this, 120000)
        }
    }
    private fun connectedSentry(transport: BleTransport? = null) {
        val requestOrigin = origin
        val current = if (transport != null) SentryApi(origin, "", bridge = { path, body ->
            val reply = transport.request(JSONObject().put("method", if (body == null) "GET" else "POST").put("path", path)
                .put("headers", JSONObject().put("Content-Type", "application/json"))
                .put("body", if (body == null) "" else Base64.getEncoder().encodeToString(body.toString().toByteArray())))
            require(reply.optInt("status") == 200)
            JSONObject(String(Base64.getDecoder().decode(reply.optString("body"))))
        }) else SentryApi(requestOrigin, "",
            if (activeRoute == Route.TUNNEL) Networks.manager(this).activeNetwork else Networks.commaLan(this)?.first,
            // Galaxy's async auth/session read can set or rotate cookies after onPageFinished.
            cookieProvider = { CookieManager.getInstance().getCookie(requestOrigin).orEmpty() })
        SentryPush.registerIfNeeded(this, current)
        if (transport == null) {
            val generation = connectionGeneration.get()
            val epoch = DiagnosticLog.epoch()
            val source = activeRoute?.name ?: "Unknown"
            try { worker.execute {
                if (generation != connectionGeneration.get() || isDestroyed) return@execute
                runCatching { current.request("/api/software/status?history=0") }
                    .onSuccess { DiagnosticLog.observeSoftware(it, source, epoch) }
                    .onFailure { DiagnosticLog.record("Comma software status unavailable: ${it.javaClass.simpleName}") }
            } } catch (_: RejectedExecutionException) { DiagnosticLog.record("Comma software status deferred: request queue busy") }
        }
    }
    private fun enableSentry(on: Boolean): Boolean {
        if (on && !SentryPush.permitted(this)) { runOnUiThread { requestFeaturePermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8, "Notifications") }; return false }
        return SentryPush.enable(this, on)
    }
    private var upgradePending = false
    private var upgradeDiscovery: LanDiscovery? = null
    private val upgradeRoutes = object : Runnable {
        override fun run() { if (resumed) { scheduleUpgrade(); importLocalKey() }; handler.postDelayed(this, 15000) }
    }
    private var routeMisses = 0
    private var healthRunning = false
    /**
     * Automatic only: while a page is open, keeps asking the active route whether it still answers and moves to the next
     * route in the saved order after a few misses in a row (or at once when [force]d, e.g. the Wi-Fi network just dropped).
     */
    private fun checkRouteHealth(force: Boolean = false) {
        val route = activeRoute
        if (demoSession != null || healthRunning || route == null || prefs.getString("transport", "auto") != "auto" || pairProgress != null) { if (route == null) routeMisses = 0; return }
        val generation = connectionGeneration.get()
        val wifi = (Networks.wifi(this) ?: Networks.wifi(this, false))?.first
        val credential = tunnel.load()
        val target = origin
        val cookie = CookieManager.getInstance().getCookie(target)
        healthRunning = true
        Thread {
            val ok = when (route) {
                Route.GALAXY_WIFI, Route.HOME_NETWORK -> wifi != null && GalaxyProbe.reachable(wifi, target, cookie) {}
                Route.TUNNEL -> credential == null || TunnelProbe.check(this, credential) != TunnelProbe.Result.UNREACHABLE
                Route.BLUETOOTH -> ble?.open != false
            }
            runOnUiThread {
                healthRunning = false
                if (isFinishing || isDestroyed || generation != connectionGeneration.get()) return@runOnUiThread
                routeMisses = if (ok) 0 else routeMisses + 1
                if (!ok && (force || routeMisses >= 3)) {
                    DiagnosticLog.failure("${route.name} health check failed")
                    routeMisses = 0
                    // Nothing further in the order is usable: stay on this page rather than tear it down, and keep watching.
                    fallbackFrom(route)
                }
            }
        }.start()
    }
    private val routeHealth = object : Runnable {
        override fun run() { if (resumed) checkRouteHealth(); handler.postDelayed(this, 4000) }
    }
    private val defaultWatcher = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) handler.post { if (demoSession == null && idle() && tunnel.load() != null) connect() else scheduleUpgrade() }
        }
    }
    private var origin = Networks.ORIGIN
    private var share: String? = null
    private val prefs by lazy { getSharedPreferences("companion", MODE_PRIVATE) }
    private var backCallback: android.window.OnBackInvokedCallback? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        DiagnosticLog.record("Galaxy opened ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        if (Build.VERSION.SDK_INT >= 33) backCallback = android.window.OnBackInvokedCallback { navigateBack() }.also { onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, it) }
        if (intent.getBooleanExtra("demo", false)) { showDemo(); return }
        share = intent.getStringExtra("share")?.take(8192)
        Distribution.initialize(this)
        ShareShortcut.publish(this)
        handler.postDelayed(pollSentry, 120000)
        handler.postDelayed(upgradeRoutes, 15000)
        handler.postDelayed(routeHealth, 4000)
        if (prefs.getBoolean("sentryPushEnabled", false)) { FirebaseRegistration.start(this); SentryPollJob.schedule(this) }
        Distribution.opened(this)
        val cm = Networks.manager(this)
        knownWifi.addAll(cm.allNetworks.filter { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true })
        // Galaxy Wi-Fi may have no internet, so do not require that capability.
        runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), wifiWatcher, handler) }
        val bondFilter = android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bondWatcher, bondFilter, RECEIVER_EXPORTED) else registerReceiver(bondWatcher, bondFilter)
        runCatching { cm.registerDefaultNetworkCallback(defaultWatcher, handler) }
        checkMusicBond()
        if (intent.data?.scheme == "starpilot-remote") { disconnected(); acceptRemoteKey(intent.data.toString()); return }
        connect(Networks.localOrigin(intent.getStringExtra(AppDistribution.ORIGIN_EXTRA) ?: ""))
        if (PhoneGpsService.enabled(prefs) && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, PhoneGpsService::class.java))
        }
    }
    private fun idle() = resumed && !isFinishing && web == null && ble == null && discovery == null && lanPicker == null && screen?.busy() == false
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("demo", false)) { showDemo(); return }
        if (intent.data?.scheme == "starpilot-remote") { acceptRemoteKey(intent.data.toString()); return }
        Distribution.opened(this)
        share = intent.getStringExtra("share")?.take(8192)
        connect(Networks.localOrigin(intent.getStringExtra(AppDistribution.ORIGIN_EXTRA) ?: ""))
    }
    /** Invalidates every older async result before a new route is allowed to win. */
    private fun supersedeConnection(): Int {
        DiagnosticLog.beginConnection()
        remotePairingFlow?.cancel(); remotePairingFlow = null
        web?.url?.substringAfter('#', "")?.takeIf { it.startsWith("/") && it.length <= 4096 }?.let { routeHash = it }
        prefs.edit().putString("lastRouteHash", routeHash).apply()
        activeRoute = null
        automaticLanNetwork = null
        SentryPush.api = null
        stopDiscovery()
        val generation = connectionGeneration.incrementAndGet()
        jobs.values.forEach { it.cancel(false) }; jobs.clear(); worker.purge()
        ble?.close(); ble = null
        bluetoothMode = false
        bluetoothAttemptAutomatic = false
        clearWatchdog()
        filePicker.cancel()
        val previous = web; web = null
        previous?.removeJavascriptInterface("StarPilotApp"); previous?.stopLoading(); previous?.destroy()
        Networks.manager(this).bindProcessToNetwork(null)
        return generation
    }
    private fun routeOrder() = RoutePlanner.parse(prefs.getString("routeOrder", null))
    private fun connect(host: String? = null, skipOrder: Boolean = false) {
        if (demoSession != null) return
        DiagnosticLog.record("Connection attempt started")
        val generation = supersedeConnection()
        val mode = prefs.getString("transport", "auto")
        if (host == null && mode == "remote") { connectTunnel(promptForKey = true); return }
        if (host == null && mode == "bluetooth") { connectBluetooth(automatic = true); return }
        if (host == null && mode == "auto" && !automaticWifiAllowed()) {
            if (!fallbackFrom(null)) disconnected()
            return
        }
        if (host == null && mode == "auto" && !skipOrder && routeOrder().first() != "wifi" && fallbackFrom(null)) return
        val hotspot = Networks.wifi(this)
        val wifi = hotspot ?: Networks.wifi(this, false)
        if (wifi == null) {
            if (!fallbackFrom(Route.HOME_NETWORK)) disconnected()
            return
        }
        val saved = Networks.localOrigin(prefs.getString("host", "") ?: "")
        if (host == null) {
            val candidate = if (hotspot != null) Networks.ORIGIN else saved?.takeIf { it != Networks.ORIGIN }
            if (candidate != null) probeWifi(wifi.first, candidate, generation)
            else {
                disconnected()
                // Give LAN discovery a short, bounded head start before Automatic falls back to Bluetooth.
                discoverLan(automatic = true)
            }
            return
        }
        val localOrigin = Networks.localOrigin(host)
        if (localOrigin == null) { disconnected(); return }
        origin = localOrigin
        Networks.manager(this).bindProcessToNetwork(wifi.first)
        val route = if (origin == Networks.ORIGIN) Route.GALAXY_WIFI else Route.HOME_NETWORK
        connectScreen().apply {
            refreshRoutes(this)
            headline("Opening Galaxy")
            if (route == Route.GALAXY_WIFI) { route(route, RouteState.TRYING, "Opening…"); detail("Joined StarPilot's Wi-Fi.") }
            else { route(route, RouteState.TRYING, "Opening ${address(origin)}…"); detail("Reaching StarPilot on this Wi-Fi network.") }
        }
        val view = WebView(this)
        web = view
        view.setBackgroundColor(Color.rgb(7, 7, 18))
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.addJavascriptInterface(Bridge(), "StarPilotApp")
        configureFiles(view)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                // No external document (including frames) ever receives the bridge.
                if (Networks.localOrigin(request.url.toString()) == origin) return false
                openExternalLink(v, request)
                return true
            }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (Networks.localOrigin(request.url.toString()) == origin) return null
                return WebResourceResponse("text/plain", "UTF-8", 403, "Local Galaxy only", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (web === v && request.isForMainFrame) {
                    DiagnosticLog.failure("Local Galaxy page error ${error.errorCode}")
                    runOnUiThread { pageFailed(v) }
                }
            }
            // Reveal as soon as Galaxy starts drawing, so a slow asset never trips the watchdog.
            override fun onPageCommitVisible(v: WebView, url: String) { if (web === v && Networks.localOrigin(url) == origin) { installVibrationBridge(v); revealWeb() } }
            override fun onPageFinished(v: WebView, url: String) {
                if (web === v && Networks.localOrigin(url) == origin) {
                    prefs.edit().putString("host", origin).apply(); share = null; installVibrationBridge(v); revealWeb()
                    Distribution.checkAsync(this@MainActivity)
                    GalaxyUpdates.checkAsync(this@MainActivity) {}
                    connectedSentry()
                    importLocalKey()
                    autoPairIfNeeded()
                }
            }
        }
        showWeb(view, route, if (route == Route.GALAXY_WIFI) "Galaxy Wi-Fi" else "Home network, ${address(origin)}", waitForPage = true)
        // A comma that vanished mid-load can leave the page pending for a long time.
        val watchdogMs = if (route == Route.HOME_NETWORK && mode == "auto" && pairing.load() != null) 4000L else 10000L
        pageWatchdog = Runnable { if (web === view && screen != null) pageFailed(view) }.also { handler.postDelayed(it, watchdogMs) }
        view.loadUrl(if (share != null) "$origin/?share=${Uri.encode(share)}#/navigation" else "$origin/#${pageHash()}")
        if (prefs.getBoolean("enabled", false)) startForegroundService(Intent(this, UplinkService::class.java))
    }
    private fun probeWifi(network: Network, candidate: String, generation: Int, automatic: Boolean = true) {
        if (automatic && prefs.getString("transport", "auto") == "auto") automaticLanNetwork = network
        val route = if (candidate == Networks.ORIGIN) Route.GALAXY_WIFI else Route.HOME_NETWORK
        connectScreen().apply {
            refreshRoutes(this)
            headline("Checking Wi-Fi")
            route(route, RouteState.TRYING, "Checking…")
            detail(if (route == Route.GALAXY_WIFI) "Waiting for Galaxy to answer on StarPilot Wi-Fi."
                else "Checking the last StarPilot address before searching this network.")
        }
        val cookie = CookieManager.getInstance().getCookie(candidate)
        Thread({
            var connection: HttpURLConnection? = null
            val reachable = GalaxyProbe.reachable(network, candidate, cookie) {
                connection = it
                probes[generation] = it
                if (generation != connectionGeneration.get()) it.disconnect()
            }
            connection?.let { probes.remove(generation, it) }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != connectionGeneration.get()) return@runOnUiThread
                routeDetails[route] = if (reachable) "Reachable" else "Galaxy did not answer"
                if (automatic && prefs.getString("transport", "auto") == "auto" && (Networks.wifi(this, false)?.first != network || !automaticWifiAllowed())) {
                    connect(); return@runOnUiThread
                }
                if (reachable) connect(candidate)
                else if (route == Route.HOME_NETWORK) discoverLan(automatic = automatic)
                else if (!automatic || !fallbackFrom(Route.HOME_NETWORK)) disconnected().apply {
                    route(Route.GALAXY_WIFI, RouteState.FAILED, "Galaxy didn't answer")
                    detail("This phone is on StarPilot's Wi-Fi, but Galaxy didn't answer.")
                }
            }
        }, "GalaxyProbe").start()
    }
    private fun address(origin: String) = origin.removePrefix("http://").removeSuffix(":8082")
    private fun configureFiles(view: WebView) {
        view.webChromeClient = filePicker
        // target="_blank" follows the same navigation policy as ordinary links.
        view.settings.setSupportMultipleWindows(false)
        view.setDownloadListener { url, _, _, _, _ ->
            if (web === view) {
                val local = Networks.localOrigin(url) == origin
                if (local && Uri.parse(url).path in setOf("/app.apk", "/api/companion/app.apk")) requestAppUpdate()
                else if (local || GalaxyLinks.external(url)) openBrowser(Uri.parse(url))
            }
        }
    }
    private fun openExternalLink(view: WebView, request: WebResourceRequest) {
        if (web === view && request.isForMainFrame && request.hasGesture() && request.method == "GET" && GalaxyLinks.external(request.url.toString())) {
            openBrowser(request.url)
        }
    }
    private fun openBrowser(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            message("No browser is available. Enable a browser to download the file, then return to Galaxy to choose it.")
        } catch (_: SecurityException) {
            message("The browser couldn't open this link. Download the file in your browser, then return to Galaxy to choose it.")
        }
    }
    @Deprecated("Activity result API for the platform file picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == QR_SCAN) {
            scanningRemoteKey = false
            if (resultCode == RESULT_OK && data != null) {
                IntentIntegrator.parseActivityResult(resultCode, data).contents?.let { acceptRemoteKey(it) }
            }
            return
        }
        if (!filePicker.result(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }
    private fun clearWatchdog() { pageWatchdog?.let { handler.removeCallbacks(it) }; pageWatchdog = null }
    private fun pageFailed(v: WebView) {
        if (web !== v) return
        DiagnosticLog.failure("Local Galaxy page did not open")
        val failed = origin
        disconnected().apply {
            if (failed == Networks.ORIGIN) {
                route(Route.GALAXY_WIFI, RouteState.FAILED, "Galaxy didn't open")
                detail("This phone is on StarPilot's Wi-Fi, but Galaxy didn't answer. Check that Galaxy is turned on in StarPilot settings.")
            } else {
                route(Route.HOME_NETWORK, RouteState.FAILED, "No answer at ${address(failed)}")
                detail("StarPilot didn't answer at ${address(failed)}. Its address may have changed, or StarPilot is off this network.")
            }
        }
        fallbackFrom(Route.HOME_NETWORK)
    }
    private fun disconnected(): ConnectScreen {
        supersedeConnection()
        DiagnosticLog.connectionStatus = "Disconnected; waiting for a connection"
        screen = null
        return connectScreen().apply {
            headline("Connect to StarPilot")
            detail("Join StarPilot's Galaxy Wi-Fi, or put this phone on the same Wi-Fi as StarPilot. Galaxy opens as soon as you join.")
        }
    }
    /** The current connect screen, shown on its own; a new one if a page is showing. */
    private fun connectScreen(): ConnectScreen {
        val existing = screen
        if (existing != null) {
            val parent = existing.view.parent as? ViewGroup
            if (parent == null || parent.id != android.R.id.content) { parent?.removeView(existing.view); setContentView(existing.view) }
            return existing
        }
        val created = ConnectScreen(this, ::tryRoute, { connect() }, { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }, ::openTypedAddress, ::connections, ::showDemo, ::scanRemoteKey)
        screen = created
        refreshRoutes(created)
        setContentView(created.view)
        return created
    }
    private fun refreshRoutes(s: ConnectScreen) {
        val hotspot = Networks.wifi(this) != null
        val lan = !hotspot && Networks.wifi(this, false) != null
        val saved = Networks.localOrigin(prefs.getString("host", "") ?: "")?.takeIf { it != Networks.ORIGIN }
        val paired = pairing.load() != null
        s.mode(prefs.getString("transport", "auto"))
        s.route(Route.GALAXY_WIFI, if (hotspot) RouteState.IDLE else RouteState.UNAVAILABLE, if (hotspot) "Joined" else "Not joined")
        s.route(Route.HOME_NETWORK, if (lan) RouteState.IDLE else RouteState.UNAVAILABLE, when {
            hotspot -> "Not needed on Galaxy Wi-Fi"
            !lan -> "Not on Wi-Fi"
            prefs.getString("transport", "auto") == "auto" && !automaticWifiAllowed() ->
                if (PreferredWifi.currentName(this) == null) "LAN skipped — Wi-Fi name unavailable" else "LAN skipped — not on preferred Wi-Fi"
            saved != null -> "Last found at ${address(saved)}"
            else -> "Tap to search"
        })
        s.route(Route.TUNNEL, if (tunnel.load() != null) RouteState.IDLE else RouteState.UNAVAILABLE, if (tunnel.load() != null) "Key saved" else "Add remote access")
        val cached = GalaxyUpdates.store(this).readyRoot != null
        s.route(Route.BLUETOOTH, if (paired && !pairing.stale()) RouteState.IDLE else RouteState.UNAVAILABLE,
            if (!paired) "Not paired" else if (pairing.stale()) "Pairing expired, pair again" else if (cached) "Paired, controls only" else "Paired, needs one Wi-Fi visit first")
    }
    private fun tryRoute(route: Route) {
        when (route) {
            Route.GALAXY_WIFI -> if (Networks.wifi(this) != null) connect(Networks.ORIGIN) else {
                supersedeConnection(); refreshRoutes(connectScreen()); joinWifi("Join StarPilot's Galaxy Wi-Fi, then come back")
            }
            Route.HOME_NETWORK -> {
                val generation = supersedeConnection()
                refreshRoutes(connectScreen())
                val wifi = Networks.wifi(this, false)
                val saved = Networks.localOrigin(prefs.getString("host", "") ?: "")?.takeIf { it != Networks.ORIGIN }
                if (wifi == null) joinWifi("Join the same Wi-Fi as StarPilot, then come back")
                else if (saved != null) probeWifi(wifi.first, saved, generation, automatic = false)
                else discoverLan()
            }
            Route.TUNNEL -> connectTunnel(promptForKey = true)
            Route.BLUETOOTH -> if (pairing.load() != null && !pairing.stale()) connectBluetooth() else {
                supersedeConnection(); refreshRoutes(connectScreen()); pairPhone()
            }
        }
    }
    private fun joinWifi(hint: String) {
        showNotice(hint)
        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
    }
    private fun openTypedAddress(value: String): String? {
        val host = Networks.localOrigin(Networks.typedAddress(value)) ?: return "Use StarPilot's private IPv4 address, like 192.168.1.10"
        connect(host); return null
    }
    private fun showWeb(view: WebView, route: Route, label: String, waitForPage: Boolean) {
        activeRoute = route
        DiagnosticLog.activeConnection = route.name
        DiagnosticLog.connectionStatus = if (waitForPage) "${route.name}: connected; loading Galaxy" else "${route.name}: connected"
        DiagnosticLog.record("${route.name} connection opened")
        routeDetails[route] = "Connected"
        // A WebView must own focus or taps on its text fields never raise the keyboard.
        view.isFocusable = true; view.isFocusableInTouchMode = true
        view.setOnTouchListener { v, e -> if (e.action == MotionEvent.ACTION_DOWN && !v.hasFocus()) v.requestFocus(); false }
        view.setOnApplyWindowInsetsListener { v, insets ->
            val bars = systemBarInsets(insets)
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            insets
        }
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Palette.SPACE) }
        panel.addView(ConnectScreen.webBar(this, route, label) { connections() })
        panel.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
        val overlay = screen
        if (!waitForPage || overlay == null) { screen = null; setContentView(panel); return }
        // Keep the connect screen over the page until it has loaded, instead of a blank WebView.
        (overlay.view.parent as? ViewGroup)?.removeView(overlay.view)
        setContentView(FrameLayout(this).apply { addView(panel, -1, -1); addView(overlay.view, -1, -1) })
    }
    private fun installVibrationBridge(view: WebView) {
        if (web !== view) return
        view.evaluateJavascript("(function(){try{navigator.vibrate=function(pattern){try{StarPilotApp.vibrate(JSON.stringify(pattern));return true}catch(e){return false}}}catch(e){}})();", null)
    }
    private fun vibrateFromWeb(pattern: String) {
        if (pattern.length > 128) return
        val durations = runCatching {
            if (pattern.startsWith("[")) {
                val array = org.json.JSONArray(pattern)
                if (array.length() !in 1..16) return
                LongArray(array.length()) { array.optLong(it).coerceIn(0L, 1000L) }
            } else longArrayOf(pattern.toLong().coerceIn(0L, 1000L))
        }.getOrNull() ?: return
        if (durations.sum() == 0L) return
        val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
            else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        if (durations.size == 1) vibrator.vibrate(VibrationEffect.createOneShot(durations[0], VibrationEffect.DEFAULT_AMPLITUDE))
        else vibrator.vibrate(VibrationEffect.createWaveform(durations, -1))
    }
    private fun revealWeb() {
        clearWatchdog()
        DiagnosticLog.connectionStatus = "${activeRoute?.name ?: "Unknown"}: Galaxy page loaded"
        val overlay = screen ?: return
        screen = null
        overlay.dismiss()
    }
    private fun message(text: String) { if (isFinishing || isDestroyed) return; AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show() }
    private fun completionCheck(label: String) = CheckBox(this).apply {
        text = label
        isChecked = true
        isClickable = false
        isFocusable = false
        setTextColor(Palette.ON)
        buttonTintList = android.content.res.ColorStateList.valueOf(Palette.CYAN)
        val inset = (20 * resources.displayMetrics.density).toInt()
        setPadding(inset, 0, inset, 0)
    }
    private fun showNotice(text: String) {
        if (isFinishing || isDestroyed) return
        val root = findViewById<android.widget.FrameLayout>(android.R.id.content)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val notice = TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(Palette.ON)
            typeface = Palette.medium
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Palette.SURFACE)
                setStroke(dp(1), Palette.VIOLET)
            }
            elevation = dp(12).toFloat()
            alpha = 0f
        }
        root.addView(notice, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM).apply {
            marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(32)
        })
        notice.animate().alpha(1f).setDuration(180).start()
        notice.postDelayed({
            notice.animate().alpha(0f).setDuration(180)
                .withEndAction { root.removeView(notice) }.start()
        }, 3500)
    }
    private fun showDemo() {
        demoSession?.close(); demoSession = null
        SentryPush.suspended = true
        supersedeConnection()
        stopService(Intent(this, UplinkService::class.java)); stopService(Intent(this, PhoneGpsService::class.java))
        SentryPollJob.cancel(this)
        demoSession = DemoSession(this) { demoSession?.close(); demoSession = null; SentryPush.suspended = false; intent.removeExtra("demo"); disconnected() }
        demoSession!!.show(intent.getStringExtra("openRoute") ?: "/")
    }
    private fun connections() {
        demoSession?.let { it.options(); return }
        ConnectionSheet(this, Transport.of(prefs.getString("transport", "auto")), routeOrder().map { Transport.of(it) })
            .onOrder { order -> prefs.edit().putString("routeOrder", order.joinToString(",") { it.key }).apply() }
            .onTransport { prefs.edit().putString("transport", it.key).apply(); connect() }
            .group("Automatic LAN search") {
                action("Preferred Wi-Fi", if (prefs.getBoolean(PreferredWifi.ENABLED, false)) "Search saved networks only" else "Search any Wi-Fi network") { preferredWifiSettings() }
            }
            .group("Remote access") {
                if (tunnel.load() != null) {
                    action("Forget remote key", danger = true) {
                        AlertDialog.Builder(this@MainActivity).setTitle("Forget remote key?")
                            .setMessage("This removes the saved remote key from this phone. You'll need to set up remote access again to use the tunnel.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Forget") { _, _ ->
                                remotePairingRevision++; prefs.edit().putBoolean("autoImportRemoteKey", false).apply(); tunnel.clear(); if (activeRoute == Route.TUNNEL) connect()
                            }.show()
                    }
                } else {
                    action("Scan Galaxy QR code", "Scan the App key on StarPilot → Galaxy → Access") { scanRemoteKey() }
                    action("Paste Galaxy App key") { addRemoteKey() }
                    action("Import remote key from LAN", "Save the App key from Galaxy on your local network") { importRemoteKey() }
                    if (!prefs.getBoolean("askRemoteImport", true)) action("Show automatic import prompt", "Ask about LAN import when setting up Remote") {
                        prefs.edit().putBoolean("askRemoteImport", true).apply()
                        message("Automatic import prompt enabled.")
                    }
                    action("Sign in with Galaxy ID", "Use your Galaxy ID and password") { signInRemote() }
                    action("Remote key", "No key saved · set up from the Remote route on the connection screen") {
                        AlertDialog.Builder(this@MainActivity).setTitle("Remote access")
                            .setMessage("Remote connects through firestar.link when local Wi-Fi is unavailable. The Remote route can import your key over LAN. You can also scan its QR code, paste the App key, or sign in here.")
                            .setPositiveButton("Done", null).show()
                    }
                }
            }
            .group("Connection status") {
                action("View route details", "See the latest result for each connection route") {
                    val details = Route.entries.joinToString("\n") { route ->
                        "${route.title}: ${routeDetails[route] ?: "Not checked"}"
                    }
                    AlertDialog.Builder(this@MainActivity).setTitle("Connection status")
                        .setMessage(details + if (routeError.isBlank()) "" else "\n\nRemote: $routeError")
                        .setPositiveButton("Done", null).show()
                }
            }
            .group("Bluetooth pairing") {
                action("Add Bluetooth Phone Pairing", "First open Pair phone on StarPilot (Settings > Bluetooth), then tap here") { pairPhone() }
                if (pairing.load() != null && pairing.musicAddress() == null && pairing.audioAddress() != null)
                    action("Set up music only", "Keeps the app pairing. Join StarPilot's Wi-Fi (or open Pair phone on StarPilot), then tap here") { startMusicOnly() }
                action("Forget pairing on this phone") {
                    pairing.forget(); ble?.close(); disconnected()
                    AlertDialog.Builder(this@MainActivity).setTitle("Pairing removed")
                        .setView(completionCheck("Removed from this phone"))
                        .setMessage(ALSO_FORGET_ON_COMMA)
                        .setPositiveButton("Done", null).show()
                }
            }
            .group("Phone sharing") {
                if (PhoneGpsService.enabled(prefs)) action("Stop sharing phone location") { setPhoneGps(false) }
                else action("Share phone location with StarPilot", "Uses location permission and your StarPilot GPS source preference") { setPhoneGps(true) }
                val forwarding = NavigationNotificationListener.enabled(this@MainActivity)
                val access = NavigationNotificationListener.hasAccess(this@MainActivity)
                action(if (forwarding) "Stop sharing navigation" else "Share navigation with StarPilot",
                    if (forwarding && !access) "Waiting for notification access · tap Notification access below"
                    else "Uses notification access · Google Maps and explicit Waze text guidance · active Mapbox navigation takes priority") {
                    setPhoneNavigation(!forwarding)
                }
                val notifications = PhoneNotificationForwarder.enabled(this@MainActivity)
                action(if (notifications) "Stop sharing notifications" else "Share notifications with StarPilot",
                    if (notifications && !access) "Waiting for notification access · tap Notification access below"
                    else "Shows message notifications briefly on the car screen · choose apps in Galaxy → Toggles → Phone") {
                    setPhoneNotifications(!notifications)
                }
                if (notifications) action("Send test notification", "Posts a test here; it should show at the top of the comma and car screens") {
                    sendTestNotification(access)
                }
                action("Notification access", if (access) "Granted · manage access for navigation and notification sharing" else "Required to read navigation and message notifications") {
                    openNavigationNotificationAccess()
                }
                action("Battery optimization", "Some phones stop background sharing · set Galaxy to Unrestricted or Don't optimize") {
                    runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        .onFailure { appSettings() }
                }
            }
            .group("Permissions") {
                val permissions = listOf(
                    Triple("Camera", arrayOf(Manifest.permission.CAMERA), 24),
                    Triple("Nearby devices", if (Build.VERSION.SDK_INT >= 31) bluetoothPermissions() else emptyArray(), 21),
                    Triple("Precise location", arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 22),
                    Triple("Notifications", if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(), 23),
                )
                permissions.forEach { (name, required, code) ->
                    if (required.isNotEmpty()) {
                        val allowed = required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
                        action(name, if (allowed) "✓ Allowed · tap to manage in Android settings" else "Not allowed · tap to allow") {
                            if (allowed) permissionSettings(name) else requestFeaturePermissions(required, code, name)
                        }
                    }
                }
            }
            .group("Debugging options") {
                action("Report a problem", "Connect Discord and send a support report") {
                    startActivity(Intent(this@MainActivity, DiscordReportActivity::class.java))
                }
                action("Notification details", "View push registration and delivery status") {
                    val token = prefs.getString("fcmToken", "").orEmpty()
                    val details = "Registered: ${prefs.getString("fcmRegisteredOrigin", "")}\n${prefs.getString("fcmAcknowledgement", "")}\nLast push: ${prefs.getLong("sentryLastPushAt", 0)}\nLast poll: ${prefs.getLong("sentryLastPollAt", 0)}"
                    AlertDialog.Builder(this@MainActivity).setTitle("Notification details").setMessage(details)
                        .setPositiveButton("Close", null)
                        .apply {
                            if (token.isNotEmpty()) setNeutralButton("Copy push token") { _, _ ->
                                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Push token", token))
                            }
                        }.show()
                }
                action("Notification details", "View detected navigation, forwarding and push status") { showNotificationDetails() }
                action("Refresh Galaxy screens", GalaxyUpdates.store(this@MainActivity).status) {
                    GalaxyUpdates.checkAsync(this@MainActivity) { status -> runOnUiThread { message(status) } }
                }
            }
            .show()
    }
    private fun showNotificationDetails() {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        fun details(): String {
            val access = NavigationNotificationListener.hasAccess(this)
            val navigation = NavigationNotificationListener.diagnostics.details(NavigationNotificationListener.enabled(this), access)
            val phone = PhoneNotificationForwarder.diagnostics.details(PhoneNotificationForwarder.enabled(this), access)
            return navigation + "\n\n" + phone + "\nPush notifications\nRegistered: ${prefs.getString("fcmRegisteredOrigin", "")}\n${prefs.getString("fcmAcknowledgement", "")}\nLast push: ${prefs.getLong("sentryLastPushAt", 0)}\nLast poll: ${prefs.getLong("sentryLastPollAt", 0)}"
        }
        val token = prefs.getString("fcmToken", "").orEmpty()
        val body = TextView(this).apply { text = details(); setTextIsSelectable(true); setPadding(dp(24), dp(12), dp(24), dp(12)) }
        val dialog = AlertDialog.Builder(this).setTitle("Notification details")
            .setView(ScrollView(this).apply { addView(body) }).setPositiveButton("Close", null)
            .setNegativeButton("Copy details") { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Notification details", details()))
            }.apply {
                if (token.isNotEmpty()) setNeutralButton("Copy push token") { _, _ ->
                    getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Push token", token))
                }
            }.create()
        val refresh = object : Runnable {
            override fun run() { if (dialog.isShowing) { body.text = details(); handler.postDelayed(this, 1000) } }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(refresh) }
        dialog.show()
        handler.post(refresh)
    }

    private fun appSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }
    private fun permissionSettings(name: String) {
        AlertDialog.Builder(this).setTitle("Manage $name")
            .setMessage("Open Galaxy in App settings, choose Permissions, then allow or turn off $name. Android may stop showing permission prompts after repeated denials.")
            .setPositiveButton("Open App settings") { _, _ -> appSettings() }
            .setNegativeButton("Cancel", null).show()
    }
    internal fun requestFeaturePermissions(permissions: Array<String>, code: Int, name: String) {
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        if (missing.any { prefs.getBoolean("asked:$it", false) && !shouldShowRequestPermissionRationale(it) }) {
            permissionSettings(name); return
        }
        fun request() {
            prefs.edit().apply { missing.forEach { putBoolean("asked:$it", true) } }.apply()
            requestPermissions(missing.toTypedArray(), code)
        }
        if (missing.any { shouldShowRequestPermissionRationale(it) }) {
            AlertDialog.Builder(this).setTitle("$name permission")
                .setMessage("Galaxy needs $name for this feature. You can continue using other features without it.")
                .setPositiveButton("Continue") { _, _ -> request() }.setNegativeButton("Cancel", null).show()
        } else request()
    }
    private fun permissionDenied(permissions: Array<out String>, name: String) {
        val missing = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        if (missing.any { !shouldShowRequestPermissionRationale(it) }) permissionSettings(name)
        else AlertDialog.Builder(this).setTitle("$name wasn't allowed")
            .setMessage("This feature needs $name. You can try again from Permissions in Connections.")
            .setPositiveButton("OK", null).show()
    }
    private fun requestAppUpdate(requestedOrigin: String = origin) = Distribution.requestUpdate(this, requestedOrigin)
    private var discovery: LanDiscovery? = null
    private var discoveryAutomatic = false
    private var lanPicker: AlertDialog? = null
    private fun stopDiscovery() {
        upgradeDiscovery?.close(); upgradeDiscovery = null
        probes.values.forEach { it.disconnect() }; probes.clear()
        discovery?.close(); discovery = null
        lanPicker?.dismiss(); lanPicker = null
    }
    private fun preferredWifiSettings() {
        fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
        val current = PreferredWifi.currentName(this)
        val hasWifi = Networks.wifi(this, false) != null
        val hasPermission = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        fun label(value: String) = TextView(this).apply {
            text = value; textSize = 14f; setTextColor(Palette.MUTED); setPadding(0, dp(8), 0, dp(8))
        }
        var dialog: AlertDialog? = null
        val requiredNetwork = label("Add a network to continue, or turn off preferred Wi-Fi.").apply {
            setTextColor(Palette.ERROR)
            typeface = Palette.medium
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Palette.alpha(Palette.ERROR, 24))
                setStroke(dp(1), Palette.alpha(Palette.ERROR, 100))
            }
        }
        fun updateDone() {
            val needsNetwork = prefs.getBoolean(PreferredWifi.ENABLED, false) &&
                prefs.getStringSet(PreferredWifi.NETWORKS, emptySet()).orEmpty().isEmpty()
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !needsNetwork
            requiredNetwork.visibility = if (needsNetwork) android.view.View.VISIBLE else android.view.View.INVISIBLE
        }
        content.addView(label("Automatically search for StarPilot only on these Wi-Fi networks. Elsewhere, use Remote or Bluetooth."))
        content.addView(Switch(this).apply {
            text = "Only search on preferred Wi-Fi"
            minHeight = dp(48)
            isChecked = prefs.getBoolean(PreferredWifi.ENABLED, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(PreferredWifi.ENABLED, checked).apply()
                preferredWifiChanged(); updateDone()
                if (checked && !hasPermission) {
                    dialog?.dismiss(); requestWifiNamePermission()
                }
            }
        })
        val add = TextView(this).apply {
            text = "Add current Wi-Fi"; textSize = 16f; typeface = Palette.medium
            setTextColor(Palette.VIOLET_SOFT)
            gravity = android.view.Gravity.CENTER; minHeight = dp(48)
            isClickable = true; isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Palette.alpha(Palette.VIOLET, 60)),
                android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat(); setColor(Palette.alpha(Palette.VIOLET, 35))
                    setStroke(dp(1), Palette.VIOLET)
                }, null)
        }
        content.addView(add, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val currentLabel = label(current?.let { "Connected: $it" } ?: if (hasWifi && !hasPermission) "Turn on preferred Wi-Fi to allow Wi-Fi name access." else if (hasWifi) "Wi-Fi name unavailable" else "Connect to Wi-Fi to add a network.")
        content.addView(currentLabel)
        content.addView(label("Saved networks").apply { setTextColor(Palette.VIOLET_SOFT); typeface = Palette.medium })
        val saved = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(saved) }
        content.addView(scroll, LinearLayout.LayoutParams(-1, dp(200)))
        content.addView(requiredNetwork, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        fun refreshSaved() {
            updateDone()
            val names = prefs.getStringSet(PreferredWifi.NETWORKS, emptySet()).orEmpty().sorted()
            add.isEnabled = hasWifi && hasPermission && (current == null || current !in names)
            val alreadySaved = current != null && current in names
            add.visibility = if (alreadySaved) android.view.View.GONE else android.view.View.VISIBLE
            currentLabel.visibility = if (alreadySaved) android.view.View.GONE else android.view.View.VISIBLE
            add.alpha = if (add.isEnabled) 1f else 0.5f
            saved.removeAllViews()
            if (names.isEmpty()) saved.addView(label("Add the Wi-Fi networks where your StarPilot is usually connected."))
            names.forEach { name ->
                saved.addView(LinearLayout(this).apply {
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(4), 0)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat(); setColor(Palette.SPACE); setStroke(dp(1), Palette.LINE)
                    }
                    addView(label(name).apply { setTextColor(Palette.ON); textSize = 16f }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(ImageButton(this@MainActivity).apply {
                        setImageResource(android.R.drawable.ic_menu_delete)
                        imageTintList = android.content.res.ColorStateList.valueOf(Palette.ERROR)
                        contentDescription = "Remove $name"
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener {
                            val remaining = prefs.getStringSet(PreferredWifi.NETWORKS, emptySet()).orEmpty() - name
                            prefs.edit().putStringSet(PreferredWifi.NETWORKS, remaining).apply()
                            preferredWifiChanged(); refreshSaved()
                        }
                    }, LinearLayout.LayoutParams(dp(48), dp(48)))
                })
            }
        }
        dialog = AlertDialog.Builder(this).setTitle("Preferred Wi-Fi")
            .setView(content).setPositiveButton("Done", null).create()
        add.setOnClickListener {
            if (current == null) {
                AlertDialog.Builder(this).setTitle("Wi-Fi name unavailable")
                    .setMessage("Enable Location in Android settings so Galaxy can read your Wi-Fi name.")
                    .setPositiveButton("Location settings") { _, _ ->
                        dialog?.dismiss(); startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }.setNegativeButton("Cancel", null).show()
            } else {
                val names = prefs.getStringSet(PreferredWifi.NETWORKS, emptySet()).orEmpty() + current
                prefs.edit().putStringSet(PreferredWifi.NETWORKS, names).apply()
                preferredWifiChanged(); refreshSaved()
            }
        }
        refreshSaved()
        dialog.show()
        updateDone()
    }

    private fun requestWifiNamePermission() {
        AlertDialog.Builder(this).setTitle("Read Wi-Fi names")
            .setMessage("Android requires precise location access to read the connected Wi-Fi name. Galaxy uses the name to decide whether to search LAN. Names stay on this phone.")
            .setPositiveButton("Continue") { _, _ -> requestFeaturePermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 25, "Precise location") }
            .setNegativeButton("Cancel", null).show()
    }

    private fun preferredWifiChanged() {
        if (prefs.getString("transport", "auto") != "auto") return
        upgradeDiscovery?.close(); upgradeDiscovery = null
        if (idle() || discovery != null || probes.isNotEmpty()) connect() else scheduleUpgrade()
    }

    private fun automaticWifiAllowed() = PreferredWifi.allowed(this, prefs)

    private fun fallbackFrom(after: Route?): Boolean {
        val cm = Networks.manager(this)
        val internet = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val next = RoutePlanner.next(after, prefs.getString("transport", "auto") ?: "auto",
            if (prefs.getString("transport", "auto") == "auto") automaticWifiAllowed() else Networks.wifi(this, false) != null,
            internet, tunnel.load() != null, pairing.load() != null && !pairing.stale(), routeOrder())
        when (next) {
            Route.HOME_NETWORK -> if (after == null) return false else connect(skipOrder = true)
            Route.TUNNEL -> connectTunnel(promptForKey = true)
            Route.BLUETOOTH -> connectBluetooth(automatic = true)
            else -> return false
        }
        return true
    }
    private fun scheduleUpgrade() {
        if (demoSession != null || pairProgress != null || upgradeDiscovery != null || upgradePending || prefs.getString("transport", "auto") != "auto" || activeRoute !in setOf(Route.TUNNEL, Route.BLUETOOTH)) return
        upgradePending = true
        handler.postDelayed({
            upgradePending = false
            val generation = connectionGeneration.get()
            val current = activeRoute ?: return@postDelayed
            if (prefs.getString("transport", "auto") != "auto") return@postDelayed
            val order = routeOrder()
            val lan = Networks.commaLan(this)?.takeIf { automaticWifiAllowed() && RoutePlanner.better(Route.HOME_NETWORK, current, order) }
            if (lan == null && automaticWifiAllowed() && RoutePlanner.better(Route.HOME_NETWORK, current, order)) Networks.wifi(this, false)?.let { wifi ->
                val search = LanDiscovery(this, wifi.first, 3000L) { hosts, _ ->
                    upgradeDiscovery = null
                    if (generation == connectionGeneration.get() && prefs.getString("transport", "auto") == "auto" && hosts.isNotEmpty() && automaticWifiAllowed() && Networks.wifi(this, false)?.first == wifi.first) connect(hosts.first())
                }
                upgradeDiscovery = search; search.start()
            }
            val credential = tunnel.load()
            val cookie = lan?.let { CookieManager.getInstance().getCookie(it.second) }
            Thread {
                val lanOk = lan != null && GalaxyProbe.reachable(lan.first, lan.second, cookie) {}
                val remote = if (!lanOk && current == Route.BLUETOOTH && credential != null && RoutePlanner.better(Route.TUNNEL, current, order)) TunnelProbe.check(this, credential) else null
                runOnUiThread {
                    if (generation != connectionGeneration.get() || prefs.getString("transport", "auto") != "auto") return@runOnUiThread
                    if (lanOk && automaticWifiAllowed() && Networks.wifi(this, false)?.first == lan?.first) connect(lan!!.second)
                    else if (remote == TunnelProbe.Result.REACHABLE) { supersedeConnection(); showTunnel(credential!!) }
                    else if (remote == TunnelProbe.Result.BAD_KEY) tunnel.clear()
                }
            }.start()
        }, 2000)
    }
    private fun connectTunnel(promptForKey: Boolean = false) {
        val credential = tunnel.load()
        if (credential == null) {
            disconnected().apply {
                route(Route.TUNNEL, RouteState.UNAVAILABLE, "Remote access not set up")
                detail("Requires StarPilot v7 and above")
            }
            if (promptForKey) offerRemotePairing()
            return
        }
        val generation = supersedeConnection()
        connectScreen().apply { route(Route.TUNNEL, RouteState.TRYING, "Checking firestar.link…"); headline("Opening Remote") }
        Thread {
            val result = TunnelProbe.check(this, credential)
            runOnUiThread {
                if (generation != connectionGeneration.get() || isDestroyed) return@runOnUiThread
                if (result == TunnelProbe.Result.REACHABLE) { runCatching { tunnel.save(credential) }; showTunnel(credential) }
                else {
                    routeError = if (result == TunnelProbe.Result.BAD_KEY) "App key didn't work. Copy it again from StarPilot → Galaxy → Access." else "Remote is unreachable"
                    routeDetails[Route.TUNNEL] = routeError
                    DiagnosticLog.failure("Tunnel probe: ${result.name}")
                    if (result == TunnelProbe.Result.BAD_KEY) tunnel.clear()
                    if (!fallbackFrom(Route.TUNNEL)) disconnected().apply { route(Route.TUNNEL, RouteState.FAILED, routeError) }
                }
            }
        }.start()
    }
    private var remotePairingFlow: RemotePairingFlow? = null
    private fun offerRemotePairing() {
        if (!prefs.getBoolean("askRemoteImport", true)) { addRemoteKey(); return }
        val builder = AlertDialog.Builder(this, R.style.AppDialog)
        val options = arrayOf("Try automatic import on LAN", "Scan QR code", "Paste App key", getString(R.string.dont_ask_again))
        val adapter = object : ArrayAdapter<String>(builder.context, android.R.layout.simple_list_item_1, options) {
            override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    setTextColor(Palette.VIOLET_SOFT)
                    typeface = Palette.medium
                }
            }
        }
        builder.setTitle("Automatically import Galaxy remote key?")
            .setAdapter(adapter) { _, choice ->
                when (choice) {
                    0 -> importRemoteKey()
                    1 -> scanRemoteKey()
                    2 -> addRemoteKey()
                    3 -> {
                        prefs.edit().putBoolean("askRemoteImport", false).apply()
                        Toast.makeText(this, "Automatic import prompts turned off.", Toast.LENGTH_SHORT).show()
                    }
                }
            }.show()
    }

    private fun importRemoteKey() {
        supersedeConnection()
        remotePairingRevision++
        connectScreen().route(Route.TUNNEL, RouteState.TRYING, "Importing key…")
        remotePairingFlow = RemotePairingFlow(this, save = { credential, host ->
            tunnel.save(credential, verified = false)
            prefs.edit().putString("host", host).putString("transport", "auto").putBoolean("autoImportRemoteKey", true).apply()
        }, connect = { connectTunnel() }, scan = ::scanRemoteKey, paste = ::addRemoteKey,
            finished = { screen?.let(::refreshRoutes); remotePairingFlow = null }).also { it.start() }
    }

    private var remotePairingRevision = 0
    private val QR_SCAN = 3001
    private val QR_CAMERA = 25
    private var scanningRemoteKey = false

    private fun importLocalKey() {
        if (demoSession != null || activeRoute !in setOf(Route.GALAXY_WIFI, Route.HOME_NETWORK) ||
            !prefs.getBoolean("autoImportRemoteKey", true)) return
        val view = web ?: return
        val local = Networks.localOrigin(origin) ?: return
        val network = Networks.commaLan(this)?.takeIf { it.second == local }?.first ?: return
        val generation = connectionGeneration.get()
        val revision = remotePairingRevision
        TunnelImport.importIfNeeded(this, network, local, CookieManager.getInstance().getCookie(local).orEmpty()) {
            !isDestroyed && !isFinishing && web === view && generation == connectionGeneration.get() && revision == remotePairingRevision
        }
    }

    private fun scanRemoteKey() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            message("No camera is available. Paste the Galaxy App key instead."); return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestFeaturePermissions(arrayOf(Manifest.permission.CAMERA), QR_CAMERA, "Camera")
            return
        }
        scanningRemoteKey = true
        IntentIntegrator(this).setCaptureActivity(QrCaptureActivity::class.java).setRequestCode(QR_SCAN).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Scan the App key QR code in StarPilot → Galaxy → Access")
            .setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false).initiateScan()
    }

    private fun addRemoteKey() {
        val field = EditText(this).apply {
            setSingleLine(); hint = "Paste Galaxy App key"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply { setPadding(padding, 0, padding, 0); addView(field, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this).setTitle("Connect with Galaxy App key")
            .setMessage("On StarPilot, open Galaxy → Access, copy the App key, and paste it here. Or choose Sign in with Galaxy ID under Remote access in Connections.")
            .setView(container).setPositiveButton("Connect") { _, _ -> acceptRemoteKey(field.text.toString()) }
            .setNeutralButton("Scan QR code") { _, _ -> scanRemoteKey() }
            .setNegativeButton("Cancel", null).show()
    }
    private fun acceptRemoteKey(raw: String) {
        val credential = TunnelCredential.parse(raw)
        if (credential == null) { message("App key didn't work. Copy it again from StarPilot → Galaxy → Access."); return }
        val revision = ++remotePairingRevision
        val generation = connectionGeneration.get()
        Thread {
            val result = TunnelProbe.check(this, credential)
            runOnUiThread {
                if (isDestroyed || isFinishing || revision != remotePairingRevision || generation != connectionGeneration.get()) return@runOnUiThread
                if (result == TunnelProbe.Result.BAD_KEY) {
                    message("App key didn't work. Scan or copy it again from StarPilot → Galaxy → Access.")
                } else {
                    if (runCatching { tunnel.save(credential, verified = result == TunnelProbe.Result.REACHABLE) }.isFailure) {
                        message("Couldn't save Galaxy pairing on this phone. Please try again."); return@runOnUiThread
                    }
                    prefs.edit().putBoolean("autoImportRemoteKey", true).putString("transport", "auto").apply()
                    if (result == TunnelProbe.Result.REACHABLE) connectTunnel()
                    else {
                        screen?.let(::refreshRoutes)
                        message("Galaxy pairing saved on this phone. Remote is currently unreachable; it will reconnect when both devices have internet.")
                    }
                }
            }
        }.start()
    }
    private fun signInRemote() {
        val field = EditText(this).apply { setSingleLine(); hint = "Galaxy ID or remote link" }
        val padding = (24 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply { setPadding(padding, 0, padding, 0); addView(field, LinearLayout.LayoutParams(-1, -2)) }
        AlertDialog.Builder(this).setTitle("Sign in to Galaxy")
            .setMessage("Enter the Galaxy ID or paste the remote link shown on StarPilot → Galaxy → Access. You'll sign in at firestar.link.")
            .setView(container).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val slug = TunnelCredential.galaxyId(field.text.toString())
                if (slug == null) message("Enter the 16-character Galaxy ID or paste your Galaxy remote link.") else remoteLogin(slug)
            }.show()
    }
    private fun remoteLogin(slug: String) {
        Networks.manager(this).bindProcessToNetwork(null)
        val view = WebView(this)
        view.settings.javaScriptEnabled = true; view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false; view.settings.allowContentAccess = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        val dialog = AlertDialog.Builder(this).setTitle("Sign in at galaxy.firestar.link").setView(view).setNegativeButton("Cancel", null).create()
        var checking = false
        fun allowed(url: Uri) = url.scheme == "https" && url.host == "galaxy.firestar.link" && url.port == -1 && url.userInfo == null
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest) = !allowed(request.url)
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (allowed(request.url)) null else WebResourceResponse("text/plain", "UTF-8", 403, "Denied", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            override fun onPageFinished(v: WebView, url: String) {
                if (checking || !dialog.isShowing) return
                val raw = CookieManager.getInstance().getCookie("https://galaxy.firestar.link").orEmpty().split(';')
                    .map { it.trim() }.firstOrNull { it.startsWith("galaxy_session=") }?.substringAfter('=') ?: return
                val credential = TunnelCredential.parse(raw) ?: runCatching {
                    val decoded = java.net.URLDecoder.decode(raw, "UTF-8")
                    val map = JSONObject(String(Base64.getUrlDecoder().decode(decoded)))
                    TunnelCredential.parse("$slug:${map.optString(slug)}")
                }.getOrNull() ?: return
                if (credential.slug != slug) return
                checking = true
                Thread {
                    val result = TunnelProbe.check(this@MainActivity, credential)
                    runOnUiThread {
                        checking = false
                        if (dialog.isShowing && result == TunnelProbe.Result.REACHABLE) { tunnel.save(credential); dialog.dismiss(); connectTunnel() }
                    }
                }.start()
            }
        }
        val timeout = Runnable { if (dialog.isShowing) { dialog.dismiss(); message("Sign-in timed out. Please try again.") } }
        dialog.setOnDismissListener { handler.removeCallbacks(timeout); view.stopLoading(); view.destroy() }
        dialog.show(); handler.postDelayed(timeout, 300000); view.loadUrl("https://galaxy.firestar.link/$slug/")
    }
    private fun showTunnel(credential: TunnelCredential) {
        origin = credential.webUrl.trimEnd('/')
        val view = WebView(this); web = view
        view.setBackgroundColor(Color.rgb(7, 7, 18))
        view.settings.javaScriptEnabled = true; view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false; view.settings.allowContentAccess = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.addJavascriptInterface(RemoteBridge(), "StarPilotApp")
        configureFiles(view)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                if (Networks.tunnelOrigin(request.url.toString(), credential.slug)) return false
                openExternalLink(v, request)
                return true
            }
            override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (Networks.tunnelOrigin(request.url.toString(), credential.slug)) null else WebResourceResponse("text/plain", "UTF-8", 403, "Denied", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            override fun onPageFinished(v: WebView, url: String) { if (web === v && Networks.tunnelOrigin(url, credential.slug)) { installVibrationBridge(v); revealWeb(); connectedSentry() } }
            override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode in setOf(401, 403)) runOnUiThread {
                    if (web === v) {
                        DiagnosticLog.failure("Tunnel page HTTP ${response.statusCode}")
                        tunnel.clear(); if (!fallbackFrom(Route.TUNNEL)) disconnected()
                    }
                }
            }
            override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) runOnUiThread { if (web === v) {
                    DiagnosticLog.failure("Tunnel page error ${error.errorCode}")
                    if (!fallbackFrom(Route.TUNNEL)) disconnected()
                } }
            }
        }
        showWeb(view, Route.TUNNEL, "Remote · firestar.link", waitForPage = true)
        CookieManager.getInstance().setCookie("https://galaxy.firestar.link", "galaxy_session=${credential.sessionToken}; Path=/; Secure; HttpOnly") {
            if (web === view) view.loadUrl(credential.webUrl + "#" + pageHash())
        }
    }
    // WebView invokes these methods through the StarPilotApp JavaScript interface.
    @Suppress("unused")
    inner class RemoteBridge {
        @JavascriptInterface fun bluetooth() = bluetoothMode
        @JavascriptInterface fun vibrate(pattern: String) = vibrateFromWeb(pattern)
        @JavascriptInterface fun sentryPush() = SentryPush.state(this@MainActivity)
        @JavascriptInterface fun setSentryPush(on: Boolean) = enableSentry(on)
        @JavascriptInterface fun connectionOptions() { runOnUiThread { connections() } }
        @JavascriptInterface fun distribution() = BuildConfig.DISTRIBUTION
        @JavascriptInterface fun version() = BuildConfig.VERSION_NAME
        @JavascriptInterface fun androidAutoCompanionStatus() = link.firestar.companion.GalaxyBridgeService.status(this@MainActivity)
        @JavascriptInterface fun remoteKeyState() = JSONObject().put("present", tunnel.load() != null).put("verified", tunnel.verified()).toString()
        @JavascriptInterface fun addRemoteKey() { runOnUiThread { this@MainActivity.addRemoteKey() } }
        @JavascriptInterface fun demo() = false
    }
    /** [fromMenu] keeps the old menu behavior: always confirm the address, then stay on Wi-Fi/LAN. */
    private fun discoverLan(automatic: Boolean = false, fromMenu: Boolean = false) {
        if (automatic && prefs.getString("transport", "auto") == "auto" && !automaticWifiAllowed()) {
            if (!fallbackFrom(Route.HOME_NETWORK)) disconnected()
            return
        }
        val wifi = Networks.wifi(this, false)
        if (wifi == null) {
            val text = "Join the same Wi-Fi network as StarPilot first."
            screen?.route(Route.HOME_NETWORK, RouteState.UNAVAILABLE, "Not on Wi-Fi")
            if (automatic && fallbackFrom(Route.HOME_NETWORK)) return
            screen?.detail(text) ?: message(text)
            return
        }
        stopDiscovery()
        discoveryAutomatic = automatic
        if (automatic && prefs.getString("transport", "auto") == "auto") automaticLanNetwork = wifi.first
        val generation = connectionGeneration.get()
        val quickBluetoothFallback = automatic && prefs.getString("transport", "auto") == "auto" && pairing.load() != null
        val search = LanDiscovery(this, wifi.first, if (quickBluetoothFallback) GalaxyProbe.AUTOMATIC_DISCOVERY_MS else 12000L) { hosts, error ->
            discovery = null
            if (!isFinishing && !isDestroyed && generation == connectionGeneration.get()) {
                if (Networks.wifi(this, false)?.first != wifi.first ||
                    automatic && prefs.getString("transport", "auto") == "auto" && !automaticWifiAllowed()) {
                    if (automatic) connect()
                    return@LanDiscovery
                }
                fun select(host: String) {
                    if (Networks.wifi(this, false)?.first != wifi.first ||
                        automatic && prefs.getString("transport", "auto") == "auto" && !automaticWifiAllowed()) {
                        if (automatic) connect()
                        return
                    }
                    val edit = prefs.edit().putString("host", host)
                    if (fromMenu) edit.putString("transport", "wifi")
                    edit.apply(); connect(host)
                }
                fun cancel() {
                    if (generation != connectionGeneration.get() || web != null || ble != null) return
                    screen?.route(Route.HOME_NETWORK, RouteState.IDLE, "Tap to search again")
                    if (automatic) fallbackFrom(Route.HOME_NETWORK)
                }
                val s = screen
                if (hosts.isEmpty()) {
                    val text = error ?: "No StarPilot answered on this Wi-Fi. Check that Galaxy is on and StarPilot is on the same network. " +
                        "A new install needs a few minutes parked with internet before StarPilot can be found. " +
                        "If your router blocks discovery, type StarPilot's address instead."
                    s?.route(Route.HOME_NETWORK, RouteState.FAILED, "No StarPilot found")
                    if (!(automatic && fallbackFrom(Route.HOME_NETWORK))) {
                        if (s != null) { s.headline("Connect to StarPilot"); s.detail(text) } else message(text)
                    }
                } else if (hosts.size == 1 && !fromMenu) select(hosts.single())
                else {
                    s?.route(Route.HOME_NETWORK, RouteState.LIVE, "${hosts.size} found")
                    s?.headline("Choose your StarPilot")
                    val picker = AlertDialog.Builder(this).setTitle("Choose your StarPilot on this trusted LAN")
                        .setItems(hosts.map(::address).toTypedArray()) { _, which -> if (generation == connectionGeneration.get()) select(hosts[which]) }
                        .setNegativeButton("Cancel") { _, _ -> cancel() }.setOnCancelListener { cancel() }.create()
                    picker.setOnDismissListener { if (lanPicker === picker) lanPicker = null }
                    lanPicker = picker; picker.show()
                }
            }
        }
        discovery = search
        val s = screen
        if (s != null) {
            s.headline("Finding your StarPilot")
            s.route(Route.HOME_NETWORK, RouteState.TRYING, "Searching…")
            s.detail(if (quickBluetoothFallback) "Checking this Wi-Fi for StarPilot before trying Bluetooth."
                else "Looking for StarPilot on this Wi-Fi network. This takes up to 12 seconds.")
        } else showNotice("Looking for StarPilot on LAN…")
        search.start()
    }
    /** How comma lists this phone under Settings → Bluetooth. */
    private fun phoneName(): String =
        (Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}").take(40)
    private fun phoneHello(): JSONObject = JSONObject().put("name", phoneName()).apply { pairing.previousId()?.let { put("replaces", it) } }
    /** One step for the app and music: comma's open pairing window gives this phone its key, then bonds it for music. */
    private val ALSO_FORGET_ON_COMMA = "Also forget this phone on StarPilot (Settings > Bluetooth, or Galaxy > Bluetooth), or StarPilot keeps the old pairing and may fail to pair this phone again."
    private val ALSO_FORGET_ON_PHONE = "Also forget StarPilot in this phone's Bluetooth settings before pairing again, so the old Bluetooth bond doesn't get in the way."
    private val PAIR_STEPS = "On StarPilot open Settings > Bluetooth > Pair phone (or Galaxy > Bluetooth > Pair phone) and keep that screen open."
    /** Without a Wi-Fi/LAN route the app cannot open comma's window itself, so say so before waiting for it. */
    private fun askToOpenWindow() {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this).setTitle("Open Pair phone on StarPilot first")
            .setMessage("$PAIR_STEPS\n\nComma's window stays open for 2 minutes. Tap Continue once it shows.")
            .setPositiveButton("Continue") { _, _ -> pairPhone(confirmed = true) }
            .setNegativeButton("Cancel", null).show()
    }
    private fun pairPhone(confirmed: Boolean = false) {
        DiagnosticLog.record("Bluetooth app pairing requested; manualWindowConfirmed=$confirmed")
        if (pairProgress != null || autoPairing.get()) return
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) { requestFeaturePermissions(permissions, 5, "Nearby devices"); return }
        if (!confirmed && Networks.commaLan(this) == null) { askToOpenWindow(); return }
        if (!autoPairing.compareAndSet(false, true)) return
        val generation = supersedeConnection()
        pairCancelled = false
        showPairProgress("Checking that StarPilot is ready to pair…", 0)
        connectScreen().apply {
            refreshRoutes(this)
            headline("Pairing with StarPilot")
            route(Route.BLUETOOTH, RouteState.TRYING, "Pairing…")
            detail("$PAIR_STEPS\nKeep Bluetooth on for StarPilot and this phone.")
        }
        Thread {
            try {
                // Already on comma's Wi-Fi/LAN: open its pairing window ourselves so nobody has to touch comma.
                var windowNote = ""
                var notReady: CommaReadiness? = null
                Networks.commaLan(this)?.let { (network, origin) ->
                    // Don't start a Bluetooth pairing comma can't take yet; wait out a boot or bridge restart, or say what to fix.
                    if (pairCancelled) return@Thread
                    notReady = LanPairing.waitUntilReady(network, origin) { text ->
                        showPairProgress(text, 0)
                        runOnUiThread { if (generation == connectionGeneration.get()) screen?.detail("Waiting for StarPilot's Bluetooth to be ready…\n$text") }
                    }
                    if (notReady != null) return@let
                    if (pairCancelled) return@Thread
                    showPairProgress("Opening StarPilot’s pairing window over Wi-Fi…", 1)
                    val why = LanPairing.openWindow(network, origin)
                    if (why != null) {
                        DiagnosticLog.failure("Bluetooth pairing window could not be opened over LAN")
                        android.util.Log.w("GalaxyPair", "Could not open StarPilot's window over Wi-Fi: $why")
                        if (!pairCancelled) runOnUiThread { message("Couldn't open StarPilot's pairing window over Wi-Fi ($why).\n\n$PAIR_STEPS Then try again.") }
                        return@Thread
                    }
                    windowNote = "Opened StarPilot's pairing window over Wi-Fi.\n"
                    runOnUiThread { if (generation == connectionGeneration.get()) screen?.detail(windowNote.trim()) }
                }
                notReady?.let { blocked ->
                    DiagnosticLog.failure("Comma Bluetooth readiness: ${blocked.code}")
                    hidePairProgress()
                    runOnUiThread {
                        if (generation == connectionGeneration.get()) {
                            screen?.headline("StarPilot isn't ready to pair")
                            pairingNotice("StarPilot isn't ready to pair yet: ${blocked.message}\nTry Add Bluetooth Phone Pairing again in a moment.", pairing.load() != null)
                        }
                    }
                    return@Thread
                }
                if (pairCancelled) return@Thread
                val transport = BleTransport(this, null)
                pairTransport = transport
                transport.onPhase = { text ->
                    showPairProgress(text, if (text.startsWith("Pairing:")) 3 else 2)
                    runOnUiThread { if (generation == connectionGeneration.get()) screen?.detail(windowNote + text) }
                }
                ble = transport
                try {
                    val claim = ClaimPairing.claim(transport, phoneName())
                    if (pairCancelled || generation != connectionGeneration.get()) return@Thread
                    pairing.save(claim.key, claim.id.ifEmpty { null })
                    pairing.rememberAudio(claim.adapterAddress)
                    DiagnosticLog.record("Bluetooth app pairing verified and saved; musicSetupAvailable=${claim.adapterAddress.isNotEmpty()}")
                    transport.close()
                    hidePairProgress()
                    if (pairCancelled || generation != connectionGeneration.get()) return@Thread
                    runOnUiThread {
                        if (generation != connectionGeneration.get()) return@runOnUiThread
                        connectBluetooth()
                        offerMusic()
                    }
                } catch (e: Exception) {
                    transport.close()
                    if (pairCancelled) return@Thread
                    android.util.Log.w("GalaxyPair", "Pairing failed", e)
                    DiagnosticLog.failure("Bluetooth app pairing failed: ${e.javaClass.simpleName}")
                    runOnUiThread {
                        if (generation == connectionGeneration.get()) {
                            screen?.headline("Pairing didn't finish")
                            pairingNotice("Couldn't pair: ${e.message ?: "StarPilot didn't answer"}", pairing.load() != null)
                        } else android.util.Log.w("GalaxyPair", "Pairing result dropped: a newer connection attempt replaced it")
                    }
                } finally {
                    transport.close()
                }
            } finally {
                autoPairing.set(false)
                pairTransport = null
                hidePairProgress()
            }
        }.start()
    }
    /** Forgetting comma in the phone's Bluetooth settings also ends the app pairing, like forgetting a phone on comma does. */
    private val bondWatcher = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
            val state = intent.getIntExtra(android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE, -1)
            if (device?.address != null && device.address in listOfNotNull(pairing.audioAddress(), pairing.musicAddress()))
                DiagnosticLog.record("Music Android bond state=$state")
            if (CommaBond.forgotten(pairing.musicAddress(), device?.address, state)) commaBondEnded("You forgot StarPilot in this phone's Bluetooth settings")
        }
    }
    private fun commaBondEnded(why: String) {
        if (pairing.load() == null) return
        android.util.Log.i("GalaxyPair", "Dropping the app pairing: $why")
        DiagnosticLog.record("Bluetooth app pairing removed because the Android audio bond was forgotten")
        pairing.forget(); ble?.close()
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            message("$why, so the app pairing was removed too.\n\n$ALSO_FORGET_ON_COMMA\n\nTo set it up again: $PAIR_STEPS Then tap Add Bluetooth Phone Pairing.")
            if (bluetoothMode) disconnected()
        }
    }
    /** Catches a forget done while the app was closed. */
    private fun checkMusicBond() {
        val address = pairing.musicAddress() ?: return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val bonded = runCatching { getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.map { it.address }?.toSet() }.getOrNull() ?: return
        if (CommaBond.missing(address, bonded)) commaBondEnded("StarPilot is no longer in this phone's Bluetooth devices")
    }
    private val autoPairing = java.util.concurrent.atomic.AtomicBoolean()
    private var autoPairAsked = false
    private fun bluetoothPermissions() = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    private var pairProgress: PairingProgressDialog? = null
    @Volatile private var pairCancelled = false
    @Volatile private var pairTransport: BleTransport? = null
    private fun showPairProgress(text: String, stage: Int? = null) = runOnUiThread {
        if (isFinishing || isDestroyed || pairCancelled) return@runOnUiThread
        DiagnosticLog.record("Bluetooth setup stage=${stage ?: -1}: $text")
        val dialog = pairProgress ?: PairingProgressDialog(this) {
            DiagnosticLog.record("Bluetooth setup cancelled by user")
            pairCancelled = true
            pairTransport?.close()
            pairProgress = null
        }.also { pairProgress = it }
        dialog.update(text, stage)
    }
    private fun hidePairProgress() = runOnUiThread { pairProgress?.dismiss(); pairProgress = null }

    /** Galaxy just opened over Wi-Fi/LAN and this phone has no working comma pairing: offer to pair right away, once per launch. */
    private fun autoPairIfNeeded() {
        if (bluetoothMode || (pairing.load() != null && !pairing.stale()) || autoPairAsked || !prefs.getBoolean("autoPair", true)) return
        autoPairAsked = true
        if (isFinishing || isDestroyed) return
        val again = pairing.load() != null
        val dialog = AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle(if (again) "Add Bluetooth phone pairing again?" else "Add Bluetooth phone pairing?")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Add pairing") { _, _ -> startAutoPair() }
            .create()
        val themed = dialog.context
        val padding = (24 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding / 2)
        }
        content.addView(TextView(themed).apply {
            textSize = 16f
            setTextColor(Palette.ON)
            text = getString(R.string.pairing_instructions, getString(if (again) R.string.pairing_intro_again else R.string.pairing_intro_new))
        })
        content.addView(CheckBox(themed).apply {
            setText(R.string.dont_ask_again)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("autoPair", !checked).apply() }
        })
        dialog.setView(ScrollView(themed).apply { addView(content) })
        dialog.show()
    }
    private fun startAutoPair() {
        val permissions = bluetoothPermissions()
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestFeaturePermissions(permissions, 6, "Nearby devices") else runAutoPair()
    }
    private fun runAutoPair() {
        DiagnosticLog.record("Bluetooth automatic app pairing started")
        val lan = Networks.commaLan(this) ?: return message("This phone isn't connected to StarPilot over Wi-Fi right now. Join StarPilot's Wi-Fi, then tap Add Bluetooth Phone Pairing in the menu.")
        if (!autoPairing.compareAndSet(false, true)) return
        pairCancelled = false
        showPairProgress("Checking that StarPilot is ready to pair…", 0)
        Thread {
            try {
                LanPairing.waitUntilReady(lan.first, lan.second) { text -> showPairProgress("Waiting for StarPilot's Bluetooth to be ready…\n$text") }?.let { blocked ->
                    android.util.Log.w("GalaxyPair", "StarPilot not ready to pair: ${blocked.code}")
                    DiagnosticLog.failure("Comma Bluetooth readiness: ${blocked.code}")
                    hidePairProgress()
                    if (!pairCancelled) runOnUiThread { message("StarPilot isn't ready to pair yet: ${blocked.message}\n\nTry Add Bluetooth Phone Pairing in the menu in a moment.") }
                    return@Thread
                }
                if (pairCancelled) return@Thread
                showPairProgress("Opening StarPilot's pairing window over Wi-Fi…", 1)
                LanPairing.openWindow(lan.first, lan.second)?.let { why ->
                    android.util.Log.w("GalaxyPair", "Could not open StarPilot's window over Wi-Fi: $why")
                    DiagnosticLog.failure("Bluetooth pairing window could not be opened over LAN")
                    hidePairProgress()
                    if (!pairCancelled) runOnUiThread { message("Couldn't open StarPilot's pairing window over Wi-Fi ($why).\n\n$PAIR_STEPS Then tap Add Bluetooth Phone Pairing in the menu.") }
                    return@Thread
                }
                if (pairCancelled) return@Thread
                val transport = BleTransport(this, null)
                pairTransport = transport
                transport.onPhase = { text -> showPairProgress(text, if (text.startsWith("Pairing:")) 3 else 2) }
                try {
                    val claim = ClaimPairing.claim(transport, phoneName())
                    if (pairCancelled) return@Thread
                    pairing.save(claim.key, claim.id.ifEmpty { null })
                    pairing.rememberAudio(claim.adapterAddress)
                    DiagnosticLog.record("Bluetooth app pairing verified and saved; musicSetupAvailable=${claim.adapterAddress.isNotEmpty()}")
                    transport.close()
                    hidePairProgress()
                    if (!pairCancelled) runOnUiThread { offerMusic() }
                } catch (e: Exception) {
                    transport.close()
                    if (pairCancelled) return@Thread
                    android.util.Log.w("GalaxyPair", "Pairing failed", e)
                    DiagnosticLog.failure("Bluetooth automatic pairing failed: ${e.javaClass.simpleName}")
                    runOnUiThread { message("Couldn't pair: ${e.message ?: "StarPilot didn't answer"}\n\n$PAIR_STEPS Then tap Add Bluetooth Phone Pairing in the menu.") }
                }
            } finally {
                // Whatever happened, never leave the progress dialog up.
                autoPairing.set(false); pairTransport = null; hidePairProgress()
            }
        }.start()
    }
    /** Music is its own step: the app pairing is already saved, so "Later" loses nothing. */
    private fun offerMusic() {
        if (isFinishing || isDestroyed || pairing.audioAddress() == null || pairing.musicAddress() != null) return
        AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle("Pair for music too?")
            .setMessage("Pair StarPilot as this phone's Bluetooth audio device to play music. You can do this later from Connections (Set up music only).")
            .setPositiveButton("Pair music now") { _, _ -> startMusicOnly() }
            .setNegativeButton("Later", null)
            .show()
    }
    /** Retries only the music bond; the app key from the earlier pairing stays untouched. */
    private fun startMusicOnly() {
        val permissions = bluetoothPermissions()
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) requestFeaturePermissions(permissions, 7, "Nearby devices") else runMusicOnly()
    }
    private fun runMusicOnly() {
        DiagnosticLog.musicStatus = "Music setup requested"
        DiagnosticLog.record("Music setup requested")
        val address = pairing.audioAddress() ?: return message("Pair the app with StarPilot first.")
        val lan = Networks.commaLan(this)
        if (!autoPairing.compareAndSet(false, true)) return
        pairCancelled = false
        showPairProgress("Opening StarPilot's pairing window over Wi-Fi…", 1)
        Thread {
            try {
                // Without Wi-Fi, rely on Pair phone already being open on StarPilot.
                if (lan != null) LanPairing.openWindow(lan.first, lan.second)?.let { why ->
                    DiagnosticLog.musicStatus = "Pairing window could not be opened over LAN"
                    DiagnosticLog.failure("Music pairing window could not be opened over LAN")
                    hidePairProgress()
                    if (!pairCancelled) runOnUiThread { message("Couldn't open StarPilot's pairing window over Wi-Fi ($why).") }
                    return@Thread
                }
                if (pairCancelled) return@Thread
                showPairProgress("Approve the Bluetooth music prompt on this phone if it asks.", 4)
                val outcome = bondMusic(address)
                DiagnosticLog.musicStatus = outcome.name
                DiagnosticLog.record("Music setup outcome=${outcome.name}")
                if (outcome == MusicBond.Outcome.FAILED) DiagnosticLog.failure("Music bonding failed or approval timed out")
                if (outcome == MusicBond.Outcome.PAIRED || outcome == MusicBond.Outcome.ALREADY_PAIRED) pairing.rememberMusic(address)
                hidePairProgress()
                if (!pairCancelled) runOnUiThread { message(MusicBond.message(outcome) ?: "Music isn't supported by this StarPilot version.") }
            } finally { autoPairing.set(false); hidePairProgress() }
        }.start()
    }
    private fun bondMusic(address: String): MusicBond.Outcome {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { DiagnosticLog.failure("Music setup: Bluetooth adapter unavailable or off"); return MusicBond.Outcome.FAILED }
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            DiagnosticLog.failure("Music setup: Nearby devices permission missing"); return MusicBond.Outcome.FAILED
        }
        return try {
            // Comma presents as a speaker while its window is open. Seeing it in a normal Bluetooth scan first makes
            // Android bond over classic audio Bluetooth, the way tapping comma in Bluetooth settings does.
            runCatching { discoverComma(adapter, address) }.onFailure { android.util.Log.w("GalaxyPair", "Bluetooth scan before music bond failed", it) }
            MusicBond({ adapter.bondedDevices.map { it.address.uppercase() }.toSet() }, {
                adapter.getRemoteDevice(it).createBond().also { started ->
                    DiagnosticLog.record("Music Android bond request accepted=$started")
                    android.util.Log.i("GalaxyPair", "createBond($it) started=$started")
                }
            }).run(address)
        } catch (e: Exception) {
            DiagnosticLog.failure("Music bond failed: ${e.javaClass.simpleName}")
            android.util.Log.w("GalaxyPair", "Music bond failed", e)
            MusicBond.Outcome.FAILED
        }
    }
    /** Runs one classic Bluetooth scan until [address] shows up or [millis] passes. Blocks; call from a worker thread. */
    private fun discoverComma(adapter: android.bluetooth.BluetoothAdapter, address: String, millis: Long = 10000) {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) return
        val seen = java.util.concurrent.CountDownLatch(1)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                if (device?.address.equals(address, ignoreCase = true)) seen.countDown()
            }
        }
        val filter = android.content.IntentFilter(android.bluetooth.BluetoothDevice.ACTION_FOUND)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED) else registerReceiver(receiver, filter)
        try {
            val started = adapter.startDiscovery()
            DiagnosticLog.record("Music classic Bluetooth discovery started=$started")
            if (started) DiagnosticLog.record("Music classic Bluetooth device found=${seen.await(millis, java.util.concurrent.TimeUnit.MILLISECONDS)}")
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { unregisterReceiver(receiver) }
        }
    }
    /** Pairing results appear on the connect screen when it is showing, otherwise in a dialog over Galaxy. */
    private fun phoneGpsPermissions(): Array<String> = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private fun openNavigationNotificationAccess() {
        runCatching { startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure { message("Could not open notification access settings. Open Android Settings and allow Galaxy notification access to share navigation.") }
    }
    private fun setPhoneNavigation(enabled: Boolean) {
        val navigationPrefs = NavigationNotificationListener.preferences(this)
        if (enabled && !navigationPrefs.getBoolean("disclosureAccepted", false)) {
            AlertDialog.Builder(this).setTitle("Share navigation with StarPilot")
                .setMessage("Galaxy reads notifications from supported navigation apps and sends available instructions, turns and distances to your paired StarPilot over local Wi-Fi or Bluetooth. Supported: Google Maps and explicit English Waze text notifications. Arrow-only turns are recognized after Galaxy has seen the same arrow with written directions; only small arrow shape fingerprints are kept on the phone. Unsupported formats are ignored. Android grants notification access broadly; Galaxy processes only registered navigation apps while sharing is enabled. Active Mapbox navigation takes priority. Available notification guidance appears on the turn card without a Mapbox route or key. Missing information stays unknown; phone notifications do not control the car. Enable Use phone navigation in StarPilot's Navigation setup too.")
                .setPositiveButton("Continue") { _, _ -> navigationPrefs.edit().putBoolean("disclosureAccepted", true).apply(); setPhoneNavigation(true) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        navigationPrefs.edit().putBoolean("enabled", enabled).apply()
        runCatching { NavigationNotificationListener.refresh(this) }.onFailure {
            message("Could not reconnect the navigation listener. Check Galaxy notification access in Android settings.")
        }
        if (enabled && !NavigationNotificationListener.hasAccess(this)) {
            message("Navigation sharing is waiting for notification access. Allow Galaxy in Android settings; if access is denied, use Notification access under Phone sharing to retry.")
            openNavigationNotificationAccess()
        } else message(if (enabled) "Navigation sharing enabled. Connect to StarPilot and enable Use phone navigation there. Notification details shows detection and delivery status."
            else "Navigation sharing stopped.")
    }
    private fun setPhoneNotifications(enabled: Boolean) {
        val navigationPrefs = NavigationNotificationListener.preferences(this)
        if (enabled && !navigationPrefs.getBoolean(PhoneNotificationForwarder.DISCLOSURE, false)) {
            AlertDialog.Builder(this).setTitle("Share notifications with StarPilot")
                .setMessage("While this is on, Galaxy reads the notifications your other apps post on this phone. For each new notification it sends the app name, the sender and the message text to your paired StarPilot over local Wi-Fi or Bluetooth, so StarPilot can show it briefly on the car screen.\n\n" +
                    "Message content is not saved on this phone or on StarPilot, and nothing is sent anywhere else: no servers, no remote connection. StarPilot remembers only which apps have sent notifications, so you can choose them.\n\n" +
                    "Choose which apps appear in Galaxy → Toggles → Phone on StarPilot. Only messaging apps appear until you allow others.\n\n" +
                    "To turn it off, tap Stop sharing notifications under Phone sharing, or remove Galaxy's notification access in Android settings.")
                .setPositiveButton("Continue") { _, _ -> navigationPrefs.edit().putBoolean(PhoneNotificationForwarder.DISCLOSURE, true).apply(); setPhoneNotifications(true) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        navigationPrefs.edit().putBoolean(PhoneNotificationForwarder.ENABLED, enabled).apply()
        runCatching { NavigationNotificationListener.refresh(this) }.onFailure {
            message("Could not reconnect the notification listener. Check Galaxy notification access in Android settings.")
        }
        if (enabled && !NavigationNotificationListener.hasAccess(this)) {
            message("Notification sharing is waiting for notification access. Allow Galaxy in Android settings; if access is denied, use Notification access under Phone sharing to retry.")
            openNavigationNotificationAccess()
        } else message(if (enabled) "Notification sharing enabled. Choose apps in Galaxy → Toggles → Phone on StarPilot. Notification details shows delivery status."
            else "Notification sharing stopped.")
    }
    private fun sendTestNotification(access: Boolean) {
        if (!access) { message("Allow Galaxy notification access first, so it can share the test."); openNavigationNotificationAccess(); return }
        if (!PhoneNotificationForwarder.postTest(this)) {
            if (Build.VERSION.SDK_INT >= 33) requestFeaturePermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8, "Notifications")
            else message("Allow notifications for Galaxy in Android settings so it can post a test.")
            return
        }
        message("Test sent. Look at the top of the comma screen or car screen. If nothing shows within a few seconds, open Notification details for the delivery status, and check that Show phone notifications is on in Galaxy → Toggles → Phone.")
    }
    private fun setPhoneGps(enabled: Boolean) {
        if (enabled && !prefs.getBoolean("gpsDisclosureAccepted", false)) {
            AlertDialog.Builder(this).setTitle("Share phone location").setMessage("Galaxy sends this phone's location to your StarPilot device to improve navigation while driving. Location is only used while the phone location sharing notification is showing.")
                .setPositiveButton("Continue") { _, _ -> prefs.edit().putBoolean("gpsDisclosureAccepted", true).apply(); setPhoneGps(true) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        if (!enabled) { prefs.edit().putBoolean("phoneGps", false).apply(); stopService(Intent(this, PhoneGpsService::class.java)); return }
        val missing = phoneGpsPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { requestFeaturePermissions(missing.toTypedArray(), 4, "Precise location"); return }
        prefs.edit().putBoolean("phoneGps", true).apply()
        startForegroundService(Intent(this, PhoneGpsService::class.java))
        message("Sharing phone location. Keep this app's notification while driving; StarPilot uses your GPS source preference.")
    }
    private fun pairingNotice(text: String, paired: Boolean) {
        val s = screen ?: return message(text)
        s.detail(text)
        s.route(Route.BLUETOOTH, if (paired) RouteState.IDLE else RouteState.UNAVAILABLE, if (paired) "Paired, controls only" else "Not paired")
    }
    private fun connectBluetooth(automatic: Boolean = false) {
        val generation = supersedeConnection()
        bluetoothAttemptAutomatic = automatic
        val key = pairing.load()
        if (key == null) {
            disconnected().apply {
                route(Route.BLUETOOTH, RouteState.UNAVAILABLE, "Not paired")
                detail("Pair first. $PAIR_STEPS Then tap Add Bluetooth Phone Pairing here.")
            }
            return
        }
        if (pairing.stale()) {
            // Comma stopped recognizing this phone; retrying cannot fix that, so wait for a new pairing instead.
            disconnected().apply {
                route(Route.BLUETOOTH, RouteState.FAILED, "Pairing expired")
                detail("StarPilot no longer recognizes this phone (it was forgotten there). $ALSO_FORGET_ON_PHONE $PAIR_STEPS Then tap Add Bluetooth Phone Pairing here.")
            }
            return
        }
        if (GalaxyUpdates.store(this).readyRoot == null) {
            if (automatic && fallbackFrom(Route.BLUETOOTH)) return
            // Bluetooth Galaxy serves pages saved from comma; the first download needs Wi-Fi/LAN.
            disconnected().apply {
                route(Route.BLUETOOTH, RouteState.FAILED, "Needs one Wi-Fi visit first")
                detail(GalaxyAssetStore.NOT_DOWNLOADED + " After that, Bluetooth Galaxy works anywhere.")
            }
            return
        }
        val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            if (automatic) if (!fallbackFrom(Route.BLUETOOTH)) disconnected().apply {
                route(Route.BLUETOOTH, RouteState.UNAVAILABLE, "Needs permission")
                detail("Tap Bluetooth on this screen to allow Nearby devices and connect.")
            } else requestFeaturePermissions(permissions, 2, "Nearby devices")
            return
        }
        if (getSystemService(BluetoothManager::class.java).adapter?.isEnabled != true) {
            if (automatic && fallbackFrom(Route.BLUETOOTH)) return
            disconnected().apply {
                route(Route.BLUETOOTH, RouteState.UNAVAILABLE, "Bluetooth is off")
                detail("Turn on Bluetooth in phone settings, then try again.")
            }
            if (!automatic) AlertDialog.Builder(this).setTitle("Bluetooth is off")
                .setMessage("Turn on Bluetooth in phone settings to connect to StarPilot.")
                .setPositiveButton("Bluetooth settings") { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                .setNegativeButton("Cancel", null).show()
            return
        }
        connectScreen().apply {
            refreshRoutes(this)
            headline("Connecting over Bluetooth")
            route(Route.BLUETOOTH, RouteState.TRYING, "Connecting…")
            detail("Keep Bluetooth on for StarPilot and this phone. Bluetooth opens Galaxy controls; maps and video need Wi-Fi.")
        }
        Thread {
            var transport = BleTransport(this, key, phoneHello())
            if (generation != connectionGeneration.get()) { transport.close(); return@Thread }
            ble = transport
            try {
                val remembered = pairing.deviceAddress()
                val device = try { transport.connect(remembered) } catch (first: Exception) {
                    if (remembered == null || generation != connectionGeneration.get()) throw first
                    transport.close()
                    transport = BleTransport(this, key, phoneHello())
                    ble = transport
                    transport.connect()
                }
                pairing.rememberDevice(device)
                runOnUiThread { if (!isFinishing && generation == connectionGeneration.get()) showBluetooth(transport) else transport.close() }
            } catch (e: Exception) {
                transport.close()
                DiagnosticLog.failure("Bluetooth connection: ${e.javaClass.simpleName}")
                if (e is PairingNotRecognized) { pairing.markStale(); android.util.Log.w("GalaxyPair", "Pairing not recognized by StarPilot: ${e.message}") }
                runOnUiThread {
                    if (automatic && !isFinishing && generation == connectionGeneration.get() && fallbackFrom(Route.BLUETOOTH)) return@runOnUiThread
                    if (e is PairingNotRecognized && !isFinishing && generation == connectionGeneration.get()) disconnected().apply {
                        route(Route.BLUETOOTH, RouteState.FAILED, "Pairing expired")
                        detail("StarPilot no longer recognizes this phone (${e.message}). $ALSO_FORGET_ON_PHONE $PAIR_STEPS Then tap Add Bluetooth Phone Pairing here.")
                    } else if (!isFinishing && generation == connectionGeneration.get()) disconnected().apply {
                        route(Route.BLUETOOTH, RouteState.FAILED, "Didn't connect")
                        detail("Bluetooth didn't connect: ${e.message ?: "StarPilot didn't answer"}. Check that Bluetooth is on in StarPilot settings and StarPilot is nearby.")
                    }
                }
            }
        }.start()
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (demoSession != null) {
            if (code == 9) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) demoSession?.gpsGranted()
                else permissionDenied(permissions, "Precise location")
            }
            if (code == 10 && results.firstOrNull() != PackageManager.PERMISSION_GRANTED) permissionDenied(permissions, "Notifications")
            return
        }
        if (code == 25) {
            preferredWifiChanged(); preferredWifiSettings(); return
        }
        if (code == 1 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) Distribution.checkAsync(this)
        if (code == 5) {
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) pairPhone()
            else permissionDenied(permissions, "Nearby devices")
        }
        if (code == 6) {
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) runAutoPair()
            else permissionDenied(permissions, "Nearby devices")
        }
        if (code == 7) {
            if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) runMusicOnly()
            else permissionDenied(permissions, "Nearby devices")
        }
        if (code == 4) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("phoneGps", true).apply()
                startForegroundService(Intent(this, PhoneGpsService::class.java))
                if (screen != null) screen?.detail("Sharing phone GPS. Keep the notification active while driving.")
                else message("Sharing phone GPS. Keep the notification active while driving.")
            } else permissionDenied(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), "Precise location")
        }
        if (code == 2) { if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) connectBluetooth(bluetoothAttemptAutomatic)
            else {
                disconnected().apply {
                    route(Route.BLUETOOTH, RouteState.FAILED, "Needs permission")
                    detail("Allow Nearby devices to connect over Bluetooth. Wi-Fi and LAN still work without it.")
                }
                permissionDenied(permissions, "Nearby devices")
            } }
        if (code == 1 || code == 8) {
            if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED && code == 8) enableSentry(true)
            else if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) permissionDenied(permissions, "Notifications")
        }
        if (code == QR_CAMERA) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanRemoteKey()
            else permissionDenied(permissions, "Camera")
        }
        if (code in 21..24 && results.any { it != PackageManager.PERMISSION_GRANTED }) {
            permissionDenied(permissions, when (code) { 21 -> "Nearby devices"; 22 -> "Precise location"; 24 -> "Camera"; else -> "Notifications" })
        }
    }
    private fun showBluetooth(transport: BleTransport) {
        bluetoothMode = true
        origin = cachedOrigin
        val galaxy = GalaxyUpdates.store(this)
        val galaxyRoot = galaxy.readyRoot.also { galaxy.inUseRoot = it } ?: return
        val view = WebView(this); web = view
        view.setBackgroundColor(Color.rgb(7, 7, 18))
        view.settings.javaScriptEnabled = true; view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false; view.settings.allowContentAccess = false
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        view.addJavascriptInterface(RemoteBridge(), "StarPilotApp")
        view.addJavascriptInterface(object {
            @JavascriptInterface fun cancel(id: String) { jobs.remove(id)?.cancel(false); worker.purge() }
            @JavascriptInterface fun request(id: String, raw: String) {
                if (id.length > 20 || raw.length > 1500000 || web !== view) return
                fun reply(value: JSONObject) { runOnUiThread {
                    if (web === view) view.evaluateJavascript("window.__galaxyBleReply(${JSONObject.quote(id)},${value});", null)
                } }
                val task = FutureTask<Unit> {
                    try {
                        if (web !== view) return@FutureTask
                        val input = JSONObject(raw)
                        val path = input.getString("path")
                        require(path.startsWith("/api/") && !path.contains('\\') && !path.contains("..") && !path.contains("//"))
                        reply(transport.request(input))
                    } catch (e: Exception) { reply(JSONObject().put("error", e.message ?: "Bluetooth failed; check the result before retrying changes.")) }
                    finally { jobs.remove(id) }
                }
                jobs[id] = task
                try { worker.execute(task) } catch (_: RejectedExecutionException) { jobs.remove(id); reply(JSONObject().put("error", "Bluetooth is busy. Wait for the current request.")) }
            }
        }, "StarPilotBLE")
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = r.url.scheme != "https" || r.url.host != "galaxy-app.invalid"
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse {
                fun response(type: String, data: ByteArray, status: Int = 200) = WebResourceResponse(type, "UTF-8", status, if (status == 200) "OK" else "Unavailable", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(data))
                if (r.url.scheme != "https" || r.url.host != "galaxy-app.invalid" || r.url.port != -1) return response("text/plain", byteArrayOf(), 403)
                val path = r.url.path ?: "/"
                if (path.startsWith("/api/")) return response("application/json", "{\"error\":\"Media and direct downloads are not available over Bluetooth. Connect using Galaxy Wi-Fi or LAN.\",\"code\":\"bluetooth_unavailable\"}".toByteArray(), 501)
                if (path.contains("..") || path.contains('\\') || path.contains("//")) return response("text/plain", byteArrayOf(), 403)
                return try {
                    var data = when (path) {
                        "/data/runtime.json" -> "{\"schemaVersion\":1,\"monitor\":\"local\"}".toByteArray()
                        "/bluetooth-fetch.js" -> assets.open("bluetooth-fetch.js").use { it.readBytes() }
                        else -> (if (path == "/") "index.html" else path.removePrefix("/")).let { file ->
                            File(galaxyRoot, file).readBytes()
                        }
                    }
                    if (path == "/" || path == "/index.html") data = String(data).replace("<head>", "<head><script src=\"/bluetooth-fetch.js\"></script>").toByteArray()
                    val type = when {
                        path == "/" || path.endsWith(".html") -> "text/html"
                        path.endsWith(".js") -> "text/javascript"
                        path.endsWith(".css") -> "text/css"
                        path.endsWith(".json") -> "application/json"
                        path.endsWith(".svg") -> "image/svg+xml"
                        else -> java.net.URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
                    }
                    response(type, data)
                } catch (_: Exception) { response("text/plain", byteArrayOf(), 404) }
            }
            override fun onPageFinished(v: WebView, url: String) {
                if (web !== v) return
                share = null
                installVibrationBridge(v)
                connectedSentry(transport)
                val revision = remotePairingRevision
                if (tunnel.load() == null && prefs.getBoolean("autoImportRemoteKey", true)) try { worker.execute { runCatching {
                    val reply = transport.request(JSONObject().put("method", "GET").put("path", "/api/galaxy/session").put("headers", JSONObject()).put("body", ""))
                    if (reply.optInt("status") == 200) TunnelImport.accept(this@MainActivity, JSONObject(String(Base64.getDecoder().decode(reply.optString("body"))))) {
                        !isDestroyed && web === v && revision == remotePairingRevision && prefs.getBoolean("autoImportRemoteKey", true)
                    }
                } } } catch (_: RejectedExecutionException) {}
            }
        }
        showWeb(view, Route.BLUETOOTH, "Bluetooth, controls only", waitForPage = false)
        try { worker.execute { runCatching {
            val reply = transport.request(JSONObject().put("method", "GET").put("path", "/api/software/status?history=0").put("headers", JSONObject()).put("body", ""))
            if (reply.optInt("status") == 200 && GalaxyUpdates.olderThanComma(this, JSONObject(String(Base64.getDecoder().decode(reply.optString("body")))))) {
                runOnUiThread { if (web === view) Toast.makeText(this, "Galaxy screens are from an older StarPilot build. Connect over Wi-Fi to refresh.", Toast.LENGTH_LONG).show() }
            }
        } } } catch (_: RejectedExecutionException) {}
        view.loadUrl(if (share != null) "$origin/?share=${Uri.encode(share)}#/navigation" else "$origin/#${pageHash()}")
    }
    // WebView invokes these methods through the StarPilotApp JavaScript interface.
    @Suppress("unused")
    inner class Bridge {
        @JavascriptInterface fun sentryPush() = SentryPush.state(this@MainActivity)
        @JavascriptInterface fun setSentryPush(on: Boolean) = enableSentry(on)
        @JavascriptInterface fun remoteKeyState() = RemoteBridge().remoteKeyState()
        @JavascriptInterface fun addRemoteKey() = RemoteBridge().addRemoteKey()
        @JavascriptInterface fun demo() = false
        @JavascriptInterface fun bluetooth() = bluetoothMode
        @JavascriptInterface fun vibrate(pattern: String) = vibrateFromWeb(pattern)
        @JavascriptInterface fun connectionOptions() { runOnUiThread { connections() } }
        @JavascriptInterface fun distribution() = BuildConfig.DISTRIBUTION
        @JavascriptInterface fun version() = BuildConfig.VERSION_NAME
        @JavascriptInterface fun androidAutoCompanionStatus() = link.firestar.companion.GalaxyBridgeService.status(this@MainActivity)
        @JavascriptInterface fun installUpdate() { runOnUiThread { requestAppUpdate() } }
        @JavascriptInterface fun uplinkState() = JSONObject().put("enabled", prefs.getBoolean("enabled", false)).put("autoStart", prefs.getBoolean("autoStart", false))
            .put("bytes", if (prefs.getString("day", "") == java.time.LocalDate.now().toString()) prefs.getLong("bytes", 0) else 0).toString()
        @JavascriptInterface fun setUplinkEnabled(enabled: Boolean): Boolean {
            if (enabled && !prefs.getBoolean("uplinkDisclosureAccepted", false)) {
                val answer = java.util.concurrent.CountDownLatch(1)
                val accepted = AtomicBoolean(false)
                var dialog: AlertDialog? = null
                runOnUiThread {
                    if (isFinishing || isDestroyed) { answer.countDown(); return@runOnUiThread }
                    dialog = AlertDialog.Builder(this@MainActivity).setTitle("Phone internet").setMessage("Shares this phone's mobile data with your StarPilot device over its Wi-Fi hotspot. Only your StarPilot can connect, and a notification with Stop shows while it's active. Your carrier may count this as hotspot usage.")
                        .setPositiveButton("Continue") { _, _ -> prefs.edit().putBoolean("uplinkDisclosureAccepted", true).apply(); accepted.set(true) }
                        .setNegativeButton("Cancel", null).setOnDismissListener { answer.countDown() }.show()
                }
                if (!answer.await(60, TimeUnit.SECONDS)) { runOnUiThread { dialog?.dismiss() }; return false }
                if (!accepted.get()) return false
            }
            runOnUiThread {
                prefs.edit().putBoolean("enabled", enabled).apply()
                if (enabled) startForegroundService(Intent(this@MainActivity, UplinkService::class.java))
                else stopService(Intent(this@MainActivity, UplinkService::class.java))
            }
            return true
        }
        @JavascriptInterface fun setAutoStart(enabled: Boolean) { prefs.edit().putBoolean("autoStart", enabled).apply() }
        @JavascriptInterface fun openWifiSettings() { runOnUiThread { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
    }
    private fun navigateBack() {
        if (demoSession != null) { if (demoSession!!.web.canGoBack()) demoSession!!.web.goBack() else { demoSession?.close(); demoSession = null; SentryPush.suspended = false; intent.removeExtra("demo"); disconnected() }; return }
        if (web?.canGoBack() == true) web?.goBack() else {
            if (Build.VERSION.SDK_INT >= 33) backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            finish()
        }
    }
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Legacy back handling for API 26–32") override fun onBackPressed() = navigateBack()
    override fun onResume() {
        super.onResume(); resumed = true; if (demoSession != null) return; PendingShare.deliver(this); recovery.resumed(); GalaxyUpdates.checkAsync(this) {}; Distribution.checkAsync(this); checkMusicBond()
        Distribution.resume(this)
    }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onStop() {
        remotePairingFlow?.cancel()
        if (!scanningRemoteKey) recovery.stopped(if (discovery != null || lanPicker != null) discoveryAutomatic else null, web == null && ble == null)
        super.onStop()
    }
    override fun onDestroy() {
        remotePairingFlow?.cancel(); remotePairingFlow = null
        demoSession?.close(); demoSession = null; SentryPush.suspended = false
        SentryPush.api = null
        pairCancelled = true; pairTransport?.close(); hidePairProgress()
        stopDiscovery(); clearWatchdog(); handler.removeCallbacksAndMessages(null)
        runCatching { Networks.manager(this).unregisterNetworkCallback(wifiWatcher) }
        runCatching { Networks.manager(this).unregisterNetworkCallback(defaultWatcher) }
        runCatching { unregisterReceiver(bondWatcher) }
        connectionGeneration.incrementAndGet(); ble?.close(); worker.shutdownNow()
        filePicker.cancel(); web?.destroy(); Networks.manager(this).bindProcessToNetwork(null); super.onDestroy()
    }
}
