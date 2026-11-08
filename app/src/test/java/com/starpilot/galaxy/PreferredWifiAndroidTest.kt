package com.starpilot.galaxy

import android.Manifest
import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowWifiInfo
import java.net.InetAddress
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferredWifiAndroidTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("companion", Context.MODE_PRIVATE)
    private val cm get() = context.getSystemService(ConnectivityManager::class.java)
    private val wifi get() = context.getSystemService(WifiManager::class.java)

    @Before fun setup() {
        shadowOf(cm).clearAllNetworks()
        prefs.edit().clear().putBoolean(PreferredWifi.ENABLED, true)
            .putStringSet(PreferredWifi.NETWORKS, setOf("Home")).commit()
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun properties(ip: String) = LinkProperties().apply {
        val address = ReflectionHelpers.callConstructor(LinkAddress::class.java, from(String::class.java, "$ip/24"))
        ReflectionHelpers.callInstanceMethod<Boolean>(this, "addLinkAddress", from(LinkAddress::class.java, address))
    }

    private fun join(name: String, ip: String = "192.168.1.25"): Network {
        shadowOf(cm).clearAllNetworks()
        val network = ShadowNetwork.newInstance(42)
        shadowOf(cm).addNetwork(network, ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true))
        shadowOf(cm).setNetworkCapabilities(network, NetworkCapabilities().apply { shadowOf(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) })
        shadowOf(cm).setLinkProperties(network, properties(ip))
        val info = ShadowWifiInfo.newInstance()
        shadowOf(info).setSSID(name)
        shadowOf(info).setInetAddress(InetAddress.getByName(ip))
        shadowOf(wifi).setConnectionInfo(info)
        return network
    }

    @Test fun networkChangesReevaluatePreferenceWithoutUsingCachedName() {
        join("Home")
        assertEquals("Home", PreferredWifi.currentName(context))
        assertTrue(PreferredWifi.allowed(context, prefs))
        join("Cafe")
        assertFalse(PreferredWifi.allowed(context, prefs))
        shadowOf(cm).clearAllNetworks()
        assertNull(PreferredWifi.currentName(context))
        assertFalse(PreferredWifi.allowed(context, prefs))
        join("Home")
        assertTrue(PreferredWifi.allowed(context, prefs))
    }

    @Test fun deniedPermissionAndMismatchedNetworkFailClosed() {
        join("Home")
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(PreferredWifi.allowed(context, prefs))
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val network = join("Home")
        shadowOf(cm).setLinkProperties(network, properties("192.168.2.25"))
        assertNull(PreferredWifi.currentName(context))
        assertFalse(PreferredWifi.allowed(context, prefs))
    }

    @Test fun hotspotDoesNotRequireNamePermissionAndOptOutRestoresAnyWifi() {
        join("TheGalaxy-abcd", "172.31.254.2")
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(PreferredWifi.allowed(context, prefs))
        join("Cafe")
        assertFalse(PreferredWifi.allowed(context, prefs))
        prefs.edit().putBoolean(PreferredWifi.ENABLED, false).commit()
        assertTrue(PreferredWifi.allowed(context, prefs))
        shadowOf(cm).clearAllNetworks()
        assertFalse(PreferredWifi.allowed(context, prefs))
    }
}
