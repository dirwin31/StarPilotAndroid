package com.starpilot.galaxy

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.Manifest
import android.content.pm.PackageManager

/** A routing preference, not a security boundary: different routers can share an SSID. */
object PreferredWifi {
    const val ENABLED = "preferredWifiOnly"
    const val NETWORKS = "preferredWifiNames"

    fun normalize(ssid: String?): String? = ssid?.removeSurrounding("\"")
        ?.takeUnless { it.isEmpty() || it == WifiManager.UNKNOWN_SSID }

    fun allows(hasWifi: Boolean, hotspot: Boolean, enabled: Boolean, ssid: String?, preferred: Set<String>): Boolean =
        hasWifi && (hotspot || !enabled || ssid != null && ssid in preferred)

    /** Legacy API also works on API 26–30. Match its address to the selected LAN so a
     * secondary Wi-Fi network cannot accidentally inherit the primary network's name. */
    @Suppress("DEPRECATION")
    fun currentName(context: Context): String? {
        val wifi = Networks.wifi(context, false) ?: return null
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val info = context.applicationContext.getSystemService(WifiManager::class.java).connectionInfo ?: return null
            val bytes = wifi.second.address
            val address = bytes.indices.fold(0) { value, i -> value or ((bytes[i].toInt() and 255) shl (8 * i)) }
            if (info.ipAddress != address) null else normalize(info.ssid)
        }.getOrNull()
    }

    fun allowed(context: Context, prefs: SharedPreferences): Boolean = allows(
        Networks.wifi(context, false) != null, Networks.wifi(context) != null,
        prefs.getBoolean(ENABLED, false),
        if (prefs.getBoolean(ENABLED, false)) currentName(context) else null,
        prefs.getStringSet(NETWORKS, emptySet()).orEmpty())
}
