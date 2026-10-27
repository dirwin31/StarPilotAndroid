package com.starpilot.galaxy

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Base64
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Toggle
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Android Auto shows a small, template-only view of the connected Galaxy device.
 * Galaxy remains the authority for availability and vehicle state at both preview and save.
 */
class GalaxyCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(this).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
        }
    override fun onCreateSession(): Session = object : Session() {
        private val api by lazy { CarGalaxyApi(carContext) }
        override fun onCreateScreen(intent: Intent): Screen {
            lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY) api.close()
            })
            return GalaxyCarScreen(carContext, "home", api)
        }
    }
}

private class GalaxyCarScreen(context: CarContext, private val page: String, private val api: CarGalaxyApi) : Screen(context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var snapshot: JSONObject? = null
    private var error: String? = null
    private var driving: JSONObject? = null
    private var drivingError: String? = null
    private var model: JSONObject? = null
    private var loading = false
    private var generation = 0
    private var settingsPage = 0
    private var activeTab = "home"
    private var navigation: JSONObject? = null
    private var navigationLoading = false
    private var navigationError: String? = null
    private var savingToggle: Int? = null
    private val liveTelemetry = page == "home"
    private val poll = object : Runnable {
        override fun run() {
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            if (page == "home" && activeTab == "toggles") {
                main.postDelayed(this, 30_000L)
                return
            }
            if (!loading) refresh()
            main.postDelayed(this, 30_000L)
        }
    }

    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    if (liveTelemetry) main.postDelayed(poll, 30_000L)
                    else if (page != "home" && snapshot != null) refresh()
                }
                Lifecycle.Event.ON_STOP -> main.removeCallbacks(poll)
                Lifecycle.Event.ON_DESTROY -> {
                    main.removeCallbacks(poll)
                    generation++
                    worker.shutdownNow()
                }
                else -> Unit
            }
        })
        refresh()
    }

    override fun onGetTemplate(): Template {
        if (page == "home") return homeTemplate()
        val data = snapshot
        if (data == null && error != null) {
            val issue = ItemList.Builder().addItem(connectionRow()).build()
            return ListTemplate.Builder().setTitle("Galaxy").setHeaderAction(Action.BACK)
                .setSingleList(issue).build()
        }
        val list = ItemList.Builder()
        if (page == "sections") {
            addSections(list, data)
        } else if (page.startsWith("section:")) {
            val pages = findSection(data, page.removePrefix("section:"))?.optJSONArray("pages")
            if (pages != null) for (index in 0 until pages.length()) {
                val destination = pages.optJSONObject(index) ?: continue
                val id = destination.optString("id")
                if (id.isNotBlank()) list.addItem(row(destination.optString("title", id), "Open controls") { open(id) })
            }
        } else if (page == "monitor") {
            addStatistics(list, data)
        } else {
            val rows = data?.optJSONArray("rows")
            val visible = (0 until (rows?.length() ?: 0)).filter { index ->
                val item = rows?.optJSONObject(index)
                val choices = item?.optJSONArray("choices")
                item?.optString("page")?.isNotBlank() == true && item.optBoolean("carVisible", true) ||
                    (choices?.length() == 2 && setOf(choices.optString(0), choices.optString(1)) == setOf("On", "Off"))
            }
            val maxPage = if (visible.isEmpty()) 0 else (visible.size - 1) / 4
            settingsPage = settingsPage.coerceIn(0, maxPage)
            if (settingsPage > 0) list.addItem(row("Previous", "Earlier controls") { settingsPage--; invalidate() })
            if (rows != null) for (position in (settingsPage * 4) until minOf(visible.size, (settingsPage + 1) * 4)) {
                val i = visible[position]
                val item = rows.optJSONObject(i) ?: continue
                val label = item.optString("label", "Setting")
                val child = item.optString("page")
                val value = item.optString("value")
                if (child.isNotEmpty()) {
                    val available = item.optBoolean("available", true)
                    val description = item.optString("reason").take(300)
                    val childRow = Row.Builder().setTitle(label)
                        .addText(if (available) description.ifBlank { "Open settings" } else "Unavailable")
                    if (!available && description.isNotBlank()) childRow.addText(description)
                    if (available) childRow.setOnClickListener { open(child) }
                    else if (carContext.carAppApiLevel >= 5) childRow.setEnabled(false)
                    list.addItem(childRow.build())
                } else {
                    val choices = item.optJSONArray("choices")
                    val options = if (choices == null) emptyList() else (0 until choices.length()).map { choices.optString(it) }
                    val available = item.optBoolean("available")
                    val action = item.optBoolean("action")
                    val binary = value in setOf("On", "Off") && options.size == 2 && options.toSet() == setOf("On", "Off")
                    val enabled = carSwitchAllowed(options, available, action, value)
                    val description = item.optString("reason").take(300)
                    val status = when {
                        savingToggle == i -> "Saving…"
                        !available -> "Disabled · $value"
                        !action -> "Read only · $value"
                        !binary -> "Unavailable"
                        else -> ""
                    }
                    val settingRow = Row.Builder().setTitle(label)
                    val detail = listOf(status, description).filter(String::isNotBlank).joinToString(" · ")
                    if (detail.isNotBlank()) settingRow.addText(detail)
                    if (binary && (enabled || carContext.carAppApiLevel >= 5)) {
                        val toggle = Toggle.Builder { checked ->
                            if (!enabled || savingToggle != null) return@Builder
                            val view = data.optString("view")
                            if (view.isBlank()) return@Builder
                            if (item.optBoolean("confirm")) {
                                screenManager.push(GalaxyCarConfirmationScreen(
                                    carContext, api, view, i, label, if (checked) "On" else "Off"
                                ) { refresh() })
                            } else saveToggle(view, i, checked)
                        }.setChecked(value == "On")
                        if (carContext.carAppApiLevel >= 5) toggle.setEnabled(enabled && savingToggle == null)
                        settingRow.setToggle(toggle.build())
                    } else if (carContext.carAppApiLevel >= 5) settingRow.setEnabled(false)
                    list.addItem(settingRow.build())
                }
            }
            if (settingsPage < maxPage) list.addItem(row("Next", "More controls") { settingsPage++; invalidate() })
        }
        if (list.build().items.isEmpty()) {
            list.addItem(Row.Builder().setTitle(if (loading) "Loading…" else "No toggles on this page").build())
        }
        return ListTemplate.Builder().setTitle(if (page == "sections") "Toggles" else if (page.startsWith("section:")) findSection(data, page.removePrefix("section:"))?.optString("title") ?: "Toggles" else if (page == "monitor") "Device Health" else data?.optString("title", "Controls") ?: "Controls")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun homeTemplate(): Template {
        val list = ItemList.Builder()
        when (activeTab) {
            "home" -> addOverview(list)
            "monitor" -> if (snapshot == null) list.addItem(connectionRow()) else addStatistics(list, snapshot)
            else -> when {
                snapshot == null -> list.addItem(connectionRow())
                navigationError != null -> list.addItem(connectionRow("Controls unavailable") { loadNavigation() })
                else -> addSections(list, navigation)
            }
        }
        if (carContext.carAppApiLevel < 6) {
            list.addItem(row("Device Health", "Model and device status") { open("monitor") })
            list.addItem(row("Controls", "Galaxy settings by category") { open("sections") })
            return ListTemplate.Builder().setTitle("Galaxy").setHeaderAction(Action.APP_ICON)
                .setSingleList(list.build()).build()
        }
        val tabs = listOf(
            Triple("home", "Overview", R.drawable.ic_car_overview),
            Triple("monitor", "Device Health", R.drawable.ic_car_temperatures),
            Triple("toggles", "Controls", R.drawable.ic_car_toggles)
        )
        val builder = TabTemplate.Builder(object : TabTemplate.TabCallback {
            override fun onTabSelected(tabContentId: String) {
                if (activeTab == tabContentId) return
                activeTab = tabContentId
                invalidate()
                if (activeTab == "toggles" && snapshot != null) loadNavigation()
            }
        }).setHeaderAction(Action.APP_ICON).setActiveTabContentId(activeTab)
            .setTabContents(TabContents.Builder(ListTemplate.Builder().setSingleList(list.build()).build()).build())
        tabs.forEach { (id, title, icon) ->
            builder.addTab(Tab.Builder().setContentId(id).setTitle(title)
                .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, icon)).build()).build())
        }
        return builder.build()
    }

    private fun addOverview(list: ItemList.Builder) {
        list.addItem(connectionRow())
        if (snapshot == null) return
        val stats = driving
        if (stats == null) {
            list.addItem(Row.Builder().setTitle("Recent device activity")
                .addText(if (drivingError == null) "Loading…" else "Trip data unavailable").build())
            return
        }
        val metric = stats.optBoolean("isMetric", true)
        val last = stats.optJSONObject("lastDrive")
        list.addItem(Row.Builder().setTitle("Last trip summary")
            .addText(if (last == null) "No saved drives yet" else
                "${distance(last.optDouble("distanceMeters", Double.NaN), metric)} · " +
                    "${duration(last.optDouble("durationSeconds", Double.NaN))} · " +
                    "${displayedReading(last.optDouble("engagedPercent", Double.NaN), "%")} engaged").build())
        val totals = stats.optJSONObject("totals")
        list.addItem(Row.Builder().setTitle("Synced logs")
            .addText("${distance(totals?.optDouble("distanceMeters", Double.NaN), metric)} · " +
                "${totals?.optInt("drives") ?: 0} drives · ${duration(totals?.optDouble("engagedSeconds", Double.NaN))} engaged").build())
        val week = stats.optJSONObject("week")
        list.addItem(Row.Builder().setTitle("This week")
            .addText("${distance(week?.optDouble("distanceMeters", Double.NaN), metric)} · " +
                "${duration(week?.optDouble("durationSeconds", Double.NaN))} · " +
                "${displayedReading(week?.optDouble("engagedPercent", Double.NaN), "%")} engaged").build())
    }

    private fun distance(meters: Double?, metric: Boolean) =
        displayedReading(meters?.div(if (metric) 1000.0 else 1609.344), if (metric) "km" else "mi")

    private fun duration(seconds: Double?): String {
        if (seconds == null || !seconds.isFinite()) return "Unavailable"
        val minutes = (seconds / 60).toInt()
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }

    private fun addStatistics(list: ItemList.Builder, data: JSONObject?) {
        val memory = data?.optJSONObject("memory")
        val storage = data?.optJSONObject("storage")
        val vitals = data?.optJSONObject("vitals")
        list.addItem(Row.Builder().setTitle("Device load")
            .addText("CPU ${displayedReading(data?.optDouble("cpuPercent", Double.NaN), "%")} · RAM ${displayedReading(memory?.optDouble("percent", Double.NaN), "%")}").build())
        list.addItem(Row.Builder().setTitle("Current model").addText(modelName()).build())
        list.addItem(Row.Builder().setTitle("Operating temperature")
            .addText(displayedReading(vitals?.optDouble("cpuTempC", Double.NaN), "°C")).build())
        list.addItem(Row.Builder().setTitle("Device capacity")
            .addText("RAM ${displayedReading(memory?.optDouble("usedMiB", Double.NaN), "MiB")} · Storage ${displayedReading(storage?.optDouble("usedGiB", Double.NaN), "GiB")}").build())
    }

    private fun modelName(): String {
        val data = model ?: return "Unavailable"
        val id = data.optString("loadedId")
        val health = data.optString("health")
        if (id.isBlank() || id == "null") return when (health) {
            "loading" -> "Loading…"
            "unavailable" -> "No running model"
            else -> "Identity unavailable"
        }
        val catalog = data.optJSONArray("catalog")
        val name = (0 until (catalog?.length() ?: 0)).firstNotNullOfOrNull { i ->
            catalog?.optJSONObject(i)?.takeIf { it.optString("id") == id }?.optString("name")
        } ?: id
        return if (health == "active") name else "$name · $health"
    }

    private fun findSection(data: JSONObject?, id: String): JSONObject? {
        val sections = data?.optJSONArray("sections") ?: return null
        return (0 until sections.length()).firstNotNullOfOrNull { index ->
            sections.optJSONObject(index)?.takeIf { it.optString("id") == id }
        }
    }

    private fun addSections(list: ItemList.Builder, data: JSONObject?) {
        val sections = data?.optJSONArray("sections")
        if (sections != null) {
            for (index in 0 until sections.length()) {
                val section = sections.optJSONObject(index) ?: continue
                val pages = section.optJSONArray("pages") ?: continue
                val id = section.optString("id")
                if (id.isBlank() || pages.length() == 0) continue
                val destination = if (pages.length() == 1) pages.optJSONObject(0)?.optString("id").orEmpty() else "section:$id"
                if (destination.isNotBlank()) list.addItem(row(section.optString("title", id), "${pages.length()} control groups") { open(destination) })
            }
            return
        }
        val rows = data?.optJSONArray("rows")
        if (rows != null) for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val id = item.optString("page")
            if (id.isNotBlank() && item.optBoolean("carVisible", true))
                list.addItem(row(item.optString("label", id), "Open controls") { open(id) })
        }
        if (data == null) list.addItem(Row.Builder().setTitle("Loading controls…").build())
    }

    private fun loadNavigation() {
        if (snapshot == null || navigation != null || navigationLoading) return
        navigationLoading = true
        navigationError = null
        if (activeTab == "toggles") invalidate()
        worker.execute {
            val result = runCatching { api.get("/api/settings/pages/hub") }
            main.post {
                if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@post
                navigationLoading = false
                result.onSuccess { navigation = it; navigationError = null }
                    .onFailure { navigationError = it.message ?: "Settings are unavailable" }
                if (activeTab == "toggles") invalidate()
            }
        }
    }

    private fun connectionRow(
        message: String? = null,
        retry: () -> Unit = { refresh(true) }
    ): Row {
        val detail = when {
            loading -> "Connecting…"
            message != null -> "$message · Tap to retry"
            error?.contains("Set up this Galaxy app", ignoreCase = true) == true ->
                "Set up this Galaxy app on phone when parked · Retry"
            error?.contains("Sign in", ignoreCase = true) == true -> "Sign in on your phone when parked"
            error != null -> "Unable to connect · Tap to retry"
            else -> "${api.lastTransport} · Tap to refresh"
        }
        return Row.Builder().setTitle("Galaxy connection")
            .addText(detail).setOnClickListener(retry).build()
    }

    private fun row(title: String, subtitle: String, click: () -> Unit) =
        Row.Builder().setTitle(title).addText(subtitle).setOnClickListener(click).build()

    private fun displayedReading(number: Double?, unit: String): String =
        if (number == null || !number.isFinite()) "Unavailable" else
            if (unit.isEmpty()) number.toInt().toString() else
                (if (unit == "%" || unit == "°C") "%.1f%s" else "%.1f %s").format(number, unit)

    private fun open(name: String) {
        // Preserve the section for Back; older hosts also have a separate Controls screen.
        val maxDepth = if (carContext.carAppApiLevel < 6) 4 else 3
        if (screenManager.stackSize >= maxDepth) screenManager.pop()
        screenManager.push(GalaxyCarScreen(carContext, name, api))
    }

    private fun visibleHomeReadings(): String = when (activeTab) {
        "home" -> "$error|$drivingError|${api.lastTransport}|${driving?.optJSONObject("lastDrive")}|${driving?.optJSONObject("totals")}|${driving?.optJSONObject("week")}" 
        "monitor" -> "$error|${model?.optString("loadedId")}|${model?.optString("health")}|${snapshot?.optDouble("cpuPercent")}|${snapshot?.optJSONObject("memory")}|${snapshot?.optJSONObject("storage")}|${snapshot?.optJSONObject("vitals")}|${snapshot?.optJSONArray("cores")}" 
        else -> "toggles"
    }

    private fun saveToggle(view: String, index: Int, checked: Boolean) {
        if (savingToggle != null) return
        savingToggle = index
        invalidate()
        worker.execute {
            val result = runCatching {
                val preview = api.preview(view, index, if (checked) "On" else "Off")
                api.confirm(preview.getString("intent"))
            }
            main.post {
                if (lifecycle.currentState == Lifecycle.State.DESTROYED) return@post
                savingToggle = null
                result.exceptionOrNull()?.let { failure ->
                    CarToast.makeText(carContext, failure.message ?: "Galaxy could not save the change", CarToast.LENGTH_LONG).show()
                }
                refresh()
            }
        }
    }

    private fun refresh(showProgress: Boolean = false) {
        if (loading) return
        loading = true
        if (showProgress) invalidate()
        val request = ++generation
        val before = if (page == "home") visibleHomeReadings() else ""
        worker.execute {
            val result = runCatching {
                api.get(if (page == "home" || page == "monitor") "/api/system/monitor" else "/api/settings/pages/" +
                    URLEncoder.encode(if (page == "sections" || page.startsWith("section:")) "hub" else page, "UTF-8"))
            }
            main.post {
                if (request != generation) return@post
                loading = false
                result.onSuccess { snapshot = it; error = null }
                    .onFailure {
                        snapshot = null
                        error = it.message ?: "Galaxy is unavailable"
                        model = null
                        driving = null
                        navigation = null
                        navigationError = null
                    }
                if (result.isSuccess && page == "home" && activeTab == "toggles") loadNavigation()
                if (showProgress || page != "home" || activeTab == "toggles" || before != visibleHomeReadings()) invalidate()
                if (result.isSuccess && (page == "home" || page == "monitor")) worker.execute {
                    val modelResult = runCatching { api.get("/api/models/status") }
                    main.post {
                        if (request != generation) return@post
                        model = modelResult.getOrNull()
                        if (page == "monitor" || activeTab == "monitor") invalidate()
                    }
                    if (page == "home") {
                        val drives = runCatching {
                            api.get("/api/drives/stats?timezone=" + URLEncoder.encode(java.util.TimeZone.getDefault().id, "UTF-8"))
                        }
                        main.post {
                            if (request != generation) return@post
                            driving = drives.getOrNull()
                            drivingError = drives.exceptionOrNull()?.message
                            if (activeTab == "home") invalidate()
                        }
                    }
                }
            }
        }
    }

}

internal fun carSwitchAllowed(choices: List<String>, available: Boolean, action: Boolean, value: String): Boolean =
    available && action && value in setOf("On", "Off") && choices.size == 2 && choices.toSet() == setOf("On", "Off")

private class GalaxyCarConfirmationScreen(
    context: CarContext,
    private val api: CarGalaxyApi,
    private val view: String,
    private val row: Int,
    private val label: String,
    private val selection: String,
    private val onSaved: () -> Unit
) : Screen(context) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var intent: String? = null
    private var question = "Checking this change with Galaxy…"
    private var failure: String? = null
    private var saving = false
    private var active = true

    init {
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                active = false
                worker.shutdownNow()
            }
        })
        loadPreview()
    }

    override fun onGetTemplate(): Template {
        val builder = MessageTemplate.Builder(failure ?: if (saving) "Saving…" else question)
            .setTitle(label).setHeaderAction(Action.BACK)
        if (failure != null) {
            builder.addAction(Action.Builder().setTitle("Retry").setOnClickListener { loadPreview() }.build())
        } else if (intent != null && !saving) {
            builder.addAction(Action.Builder().setTitle("Cancel").setOnClickListener { screenManager.pop() }.build())
            builder.addAction(Action.Builder().setTitle("Save").setOnClickListener { save() }.build())
        }
        return builder.build()
    }

    private fun loadPreview() {
        if (saving) return
        intent = null
        failure = null
        question = "Checking this change with Galaxy…"
        worker.execute {
            val result = runCatching { api.preview(view, row, selection) }
            main.post {
                if (!active) return@post
                result.onSuccess {
                    intent = it.getString("intent")
                    question = it.optString("question", "Save this preference?")
                }.onFailure { failure = it.message ?: "Galaxy could not check this change." }
                invalidate()
            }
        }
    }

    private fun save() {
        val pending = intent ?: return
        if (saving) return
        saving = true
        invalidate()
        worker.execute {
            val result = runCatching { api.confirm(pending) }
            main.post {
                if (!active) return@post
                saving = false
                result.onSuccess {
                    onSaved()
                    screenManager.pop()
                }.onFailure {
                    failure = it.message ?: "Galaxy could not save this change."
                    intent = null
                    invalidate()
                }
            }
        }
    }
}

private class CarGalaxyApi(private val context: CarContext) {
    private var ownBluetooth: BleTransport? = null
    private val demo by lazy { CarReviewDemo(context) }
    @Volatile var lastTransport: String = "Not connected yet"
        private set
    fun get(path: String) = request(path, null)
    fun post(path: String, payload: JSONObject) = request(path, payload)
    fun preview(view: String, row: Int, selection: String): JSONObject =
        post("/api/settings/preview", JSONObject().put("view", view).put("row", row).put("value", selection))

    fun confirm(intent: String) {
        check(post("/api/settings/confirm", JSONObject().put("intent", intent).put("confirmed", true))
            .optBoolean("saved")) { "Galaxy did not confirm the change." }
    }

    private fun internetNetwork(): android.net.Network? = Networks.manager(context).run {
        listOfNotNull(activeNetwork).plus(allNetworks).firstOrNull { network ->
            getNetworkCapabilities(network)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        }
    }

    @Synchronized private fun request(path: String, payload: JSONObject?): JSONObject {
        if (context.getSharedPreferences("review_demo", android.content.Context.MODE_PRIVATE).getBoolean("active", false)) {
            lastTransport = "Offline demo"
            return demo.request(path, payload)
        }
        val mode = "auto"
        val lan = Networks.commaLan(context)
        val remote = TunnelStore(context).load()
        val pairing = PairingStore(context)
        val internet = internetNetwork() != null
        val bluetooth = ownBluetooth?.open == true || BleTransport.active?.open == true ||
            pairing.load() != null && !pairing.stale()
        var previous: Route? = null
        var lastFailure: Exception? = null
        while (true) {
            val route = RoutePlanner.next(previous, mode, lan != null, internet, remote != null, bluetooth) ?: break
            try {
                return when (route) {
                    Route.GALAXY_WIFI, Route.HOME_NETWORK -> requestLan(lan!!, path, payload)
                    Route.TUNNEL -> requestRemote(remote!!, path, payload)
                    Route.BLUETOOTH -> requestBluetooth(path, payload)
                }
            } catch (failure: Exception) {
                lastFailure = failure
                // A write may already have applied. Only retry reads on another route.
                if (payload != null) throw failure
                previous = route
            }
        }
        if (remote == null && !bluetooth)
            throw IllegalStateException("Set up this Galaxy app on your phone when parked.", lastFailure)
        throw lastFailure ?: IllegalStateException("No saved Galaxy connection. Set up Wi-Fi, HTTPS or Bluetooth on the phone when parked.")
    }

    private fun requestLan(lan: Pair<android.net.Network, String>, path: String, payload: JSONObject?): JSONObject {
        val (network, origin) = lan
        val connection = network.openConnection(URL(origin + path)) as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept", "application/json")
            CookieManager.getInstance().getCookie(origin)?.let { connection.setRequestProperty("Cookie", it) }
            if (payload != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Origin", origin)
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            return parseReply(connection.responseCode, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
                .also { lastTransport = "Wi-Fi" }
        } finally { connection.disconnect() }
    }

    private fun requestRemote(credential: TunnelCredential, path: String, payload: JSONObject?): JSONObject {
        val network = internetNetwork()
            ?: throw java.io.IOException("Phone internet is unavailable.")
        val connection = network.openConnection(URL(credential.webUrl + path.removePrefix("/"))) as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cookie", "galaxy_session=${credential.sessionToken}")
            if (payload != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Origin", "https://galaxy.firestar.link")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            return parseReply(connection.responseCode, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
                .also { lastTransport = "HTTPS" }
        } finally { connection.disconnect() }
    }

    private fun requestBluetooth(path: String, payload: JSONObject?): JSONObject {
        if (Build.VERSION.SDK_INT >= 31 &&
            (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
             context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED))
            throw IllegalStateException("Allow Nearby devices in Galaxy on your phone.")
        val transport = ownBluetooth?.takeIf { it.open } ?: BleTransport.active?.takeIf { it.open } ?: run {
            val pairing = PairingStore(context)
            val key = pairing.load()?.takeUnless { pairing.stale() }
                ?: throw IllegalStateException("Connect Galaxy on your phone or pair Bluetooth in the phone app.")
            val hello = JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}".take(40))
            pairing.previousId()?.let { hello.put("replaces", it) }
            BleTransport(context, key, hello).also { created ->
                try {
                    pairing.rememberDevice(created.connect(pairing.deviceAddress()))
                    ownBluetooth = created
                } catch (failure: Exception) {
                    created.close()
                    throw failure
                }
            }
        }
        val body = payload?.toString()?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        val command = JSONObject().put("method", if (payload == null) "GET" else "POST")
            .put("path", path)
            .put("headers", JSONObject().put("Content-Type", "application/json"))
            .put("body", if (body.isEmpty()) "" else Base64.encodeToString(body, Base64.NO_WRAP))
        val reply = transport.request(command)
        val response = Base64.decode(reply.optString("body"), Base64.DEFAULT).toString(Charsets.UTF_8)
        return parseReply(reply.optInt("status"), response).also { lastTransport = "Bluetooth" }
    }

    private fun parseReply(status: Int, response: String): JSONObject {
        val json = runCatching { JSONObject(response) }.getOrElse { JSONObject() }
        if (status != 200) {
            val message = json.optString("error", "Galaxy request failed (HTTP $status)")
            if (status == 403 && message in setOf("Local access only", "Remote Galaxy request denied"))
                throw java.io.IOException(message)
            throw IllegalStateException(message)
        }
        return json
    }

    @Synchronized fun close() {
        ownBluetooth?.close()
        ownBluetooth = null
    }
}
