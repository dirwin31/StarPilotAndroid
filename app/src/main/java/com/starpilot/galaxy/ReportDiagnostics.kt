package com.starpilot.galaxy

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.NetworkCapabilities
import android.os.Build
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Read-only status collection. Never starts pairing, changes a connection, or reads credential contents into the report. */
object ReportDiagnostics {
    /** Run off the UI thread before freezing a new report; retries never call this. */
    fun refreshSoftware(api: SentryApi? = SentryPush.api) {
        val epoch = DiagnosticLog.epoch()
        val source = DiagnosticLog.activeConnection
        if (api == null) return
        runCatching { api.request("/api/software/status?history=0") }
            .onSuccess { DiagnosticLog.observeSoftware(it, source, epoch) }
            .onFailure { DiagnosticLog.record("Report software status unavailable: ${it.javaClass.simpleName}") }
    }

    /** Enumerate the installed manifest, including flavor and library permissions. */
    internal fun permissions(context: Context): Map<String, String> {
        val manager = context.packageManager
        val requested = runCatching {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        }.getOrElse { return mapOf("App permissions" to "Unavailable") }
        val result = linkedMapOf("App permissions" to "${requested.distinct().size} requested by this installed app")
        requested.distinct().sorted().forEach { name ->
            result["Permission $name"] = runCatching {
                if (name == Manifest.permission.REQUEST_INSTALL_PACKAGES) {
                    if (manager.canRequestPackageInstalls()) "Allowed (special access)" else "Not allowed (special access)"
                } else if (context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED) "Granted" else "Not granted"
            }.getOrDefault("Unavailable")
        }
        result["App notifications allowed"] = runCatching {
            context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled().toString()
        }.getOrDefault("Unavailable")
        return result
    }

    fun capture(context: Context, reference: String, now: Instant = Instant.now()): String {
        val prefs = context.getSharedPreferences("companion", Context.MODE_PRIVATE)
        val pairing = PairingStore(context)
        fun allowed(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        fun permission(permission: String) = if (allowed(permission)) "Granted" else "Not granted"
        val connectAllowed = Build.VERSION.SDK_INT < 31 || allowed(Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        val radio = if (!connectAllowed) "Unknown (Nearby devices permission missing)" else runCatching {
            when (adapter?.state) {
                null -> "Unavailable"
                BluetoothAdapter.STATE_ON -> "On"
                BluetoothAdapter.STATE_OFF -> "Off"
                BluetoothAdapter.STATE_TURNING_ON -> "Turning on"
                BluetoothAdapter.STATE_TURNING_OFF -> "Turning off"
                else -> "Unknown"
            }
        }.getOrDefault("Unknown")
        val music = pairing.musicAddress()
        val audio = pairing.audioAddress()
        val bond = if (!connectAllowed) "Unknown (Nearby devices permission missing)" else runCatching {
            val address = (music ?: audio)?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }
            if (address == null) "No audio device known" else when (adapter?.getRemoteDevice(address)?.bondState) {
                BluetoothDevice.BOND_BONDED -> "Bonded"
                BluetoothDevice.BOND_BONDING -> "Bonding"
                BluetoothDevice.BOND_NONE -> "Not bonded"
                else -> "Unknown"
            }
        }.getOrDefault("Unknown")
        val manager = Networks.manager(context)
        val internet = runCatching { manager.allNetworks.any {
            manager.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        } }.getOrDefault(false)
        val selected = prefs.getString("transport", "auto").takeIf { it in setOf("auto", "wifi", "remote", "bluetooth") } ?: "Unknown"
        val bleOpen = BleTransport.active?.linkActive == true
        val active = if (DiagnosticLog.activeConnection == "BLUETOOTH" && !bleOpen)
            "Disconnected (last page used Bluetooth)" else DiagnosticLog.activeConnection
        return DiagnosticLog.snapshot(linkedMapOf(
            "Report reference" to reference,
            "App version" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.DISTRIBUTION}",
            "Phone / OS" to "${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Android build" to Build.DISPLAY,
            "Android security patch" to Build.VERSION.SECURITY_PATCH,
            "Android build date (UTC)" to if (Build.TIME > 0) Instant.ofEpochMilli(Build.TIME).toString() else "Unknown",
            "Phone local date/time" to DateTimeFormatter.ISO_ZONED_DATE_TIME.format(now.atZone(ZoneId.systemDefault())),
            "Timezone" to ZoneId.systemDefault().id,
            "Selected connection" to selected,
            "Active connection" to active,
            "Current connection status" to if (active.startsWith("Disconnected")) active else DiagnosticLog.connectionStatus,
            "Last connection error" to DiagnosticLog.lastFailure,
            "Bluetooth radio" to radio,
            "Bluetooth scan permission" to permission(if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION),
            "Bluetooth connect permission" to if (Build.VERSION.SDK_INT >= 31) permission(Manifest.permission.BLUETOOTH_CONNECT) else "Not required on this Android version",
            "BLE authenticated session" to if (bleOpen) "Open" else "Not open",
            "Last Bluetooth phase" to DiagnosticLog.bluetoothPhase,
            "Galaxy pairing" to if (pairing.load() == null) "Not paired" else if (pairing.stale()) "Expired" else "Paired",
            "Tunnel setup" to if (TunnelStore(context).load() == null) "No key saved" else if (TunnelStore(context).verified()) "Key saved and previously verified" else "Key saved; verification pending",
            "Music setup saved" to if (music != null) "Yes" else if (audio == null) "No; app pairing not completed" else if (audio.isEmpty()) "Unavailable on paired comma" else "No; music setup still needed",
            "Android audio bond" to bond,
            "Last music setup result" to DiagnosticLog.musicStatus,
            "Media playback/output" to "Not observed; a Bluetooth bond does not confirm audio output",
            "Validated internet available" to internet.toString(),
            "Cached Galaxy screens available" to (GalaxyUpdates.store(context).readyRoot != null).toString(),
            "Phone GPS sharing enabled" to PhoneGpsService.enabled(prefs).toString(),
            "Location permission" to permission(Manifest.permission.ACCESS_FINE_LOCATION),
            "Sentry alerts enabled" to prefs.getBoolean("sentryPushEnabled", false).toString(),
            "Notifications permission" to if (Build.VERSION.SDK_INT >= 33) permission(Manifest.permission.POST_NOTIFICATIONS) else "Not required on this Android version"
        ).apply { putAll(permissions(context)) }, now)
    }
}
