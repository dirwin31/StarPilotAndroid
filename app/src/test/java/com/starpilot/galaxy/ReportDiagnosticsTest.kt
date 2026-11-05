package com.starpilot.galaxy

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ReportDiagnosticsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val originalZone = TimeZone.getDefault()
    private val now = Instant.parse("2026-11-05T15:30:00Z")
    @Before fun setup() {
        DiagnosticLog.clear()
        listOf("companion", "bluetooth-pairing", "tunnel").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
    }
    @After fun cleanup() { TimeZone.setDefault(originalZone); DiagnosticLog.clear() }

    @Test fun reportIdentifiesBuildReferenceLocalTimeModesAndSetupWithoutLeakingCredentials() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kathmandu"))
        context.getSharedPreferences("companion", 0).edit().putString("transport", "remote")
            .putString("fcmToken", "private-push-token").putString("host", "http://192.168.1.7").commit()
        context.getSharedPreferences("bluetooth-pairing", 0).edit().putString("key", "private-pairing-key").commit()
        PairingStore(context).rememberAudio("AA:BB:CC:DD:EE:FF")
        DiagnosticLog.activeConnection = "HOME_NETWORK"
        DiagnosticLog.connectionStatus = "HOME_NETWORK: connected"
        DiagnosticLog.failure("BLE access denied: GATT status=5")
        DiagnosticLog.musicStatus = "FAILED"
        val reference = "12345678-1234-1234-1234-123456789abc"
        val log = ReportDiagnostics.capture(context, reference, now)
        listOf("Report reference: $reference", "App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "Captured (UTC): $now", "2026-11-05T21:15:00+05:45[Asia/Kathmandu]", "Timezone: Asia/Kathmandu",
            "Selected connection: remote", "Active connection: HOME_NETWORK", "Current connection status: HOME_NETWORK: connected",
            "Last connection error: BLE access denied: GATT status=5", "Music setup saved: No; music setup still needed",
            "Last music setup result: FAILED", "Android security patch:", "Android build:", "Bluetooth radio:", "Tunnel setup:",
            "Bluetooth scan permission:", "BLE authenticated session: Not open").forEach { assertTrue(it, log.contains(it)) }
        listOf("private-push-token", "private-pairing-key", "192.168.1.7", "AA:BB:CC:DD:EE:FF").forEach { assertFalse(it, log.contains(it)) }
        assertEquals(reference, JSONObject(DiscordReportClient.Report(reference, "issue", log).toJson()).getString("reference"))
    }

    private fun software() = JSONObject().put("schemaVersion", 1).put("installed", JSONObject()
        .put("displayVersion", "7.0.0.6").put("version", "7.0").put("branch", "release/7.0.0.6")
        .put("commit", "a".repeat(40)).put("sessionToken", "must-never-appear")).put("password", "also-secret")

    @Test fun cachedSoftwareIsAllowlistedTimestampedAndLateResponsesCannotOverwriteNewConnections() {
        val first = DiagnosticLog.beginConnection()
        DiagnosticLog.observeSoftware(software(), "HOME_NETWORK", first, now)
        var log = ReportDiagnostics.capture(context, "r", now)
        listOf("Comma version: 7.0.0.6", "Comma branch: release/7.0.0.6", "Comma commit: ${"a".repeat(40)}",
            "Comma software observed (UTC): $now", "Observed during this connection attempt",
            "Comma software identity received successfully (HOME_NETWORK)").forEach { assertTrue(it, log.contains(it)) }
        assertFalse(log.contains("must-never-appear")); assertFalse(log.contains("also-secret"))
        val second = DiagnosticLog.beginConnection()
        DiagnosticLog.observeSoftware(software().apply { getJSONObject("installed").put("version", "WRONG").remove("displayVersion") }, "OLD", first, now)
        log = ReportDiagnostics.capture(context, "r", now)
        assertTrue(log.contains("Last observed on an earlier connection; may be a different comma"))
        assertFalse(log.contains("WRONG"))
        DiagnosticLog.observeSoftware(software().apply { getJSONObject("installed").put("displayVersion", "8.0") }, "BLUETOOTH", second, now)
        assertTrue(ReportDiagnostics.capture(context, "r", now).contains("Comma version: 8.0"))
    }

    @Test fun reviewRefreshReadsIdentityEvenWhenInitialObservationWasMissed() {
        DiagnosticLog.beginConnection()
        val api = SentryApi("https://unused.invalid", "", bridge = { path, body ->
            assertEquals("/api/software/status?history=0", path)
            assertNull(body)
            software()
        })
        ReportDiagnostics.refreshSoftware(api)
        val log = ReportDiagnostics.capture(context, "r", now)
        assertTrue(log.contains("Comma version: 7.0.0.6"))
        assertTrue(log.contains("Comma branch: release/7.0.0.6"))
        assertTrue(log.contains("Comma commit: ${"a".repeat(40)}"))
    }

    @Test fun failedRefreshPreservesObservedIdentityAndRejectsLateResponses() {
        val epoch = DiagnosticLog.beginConnection()
        DiagnosticLog.observeSoftware(software(), "HOME_NETWORK", epoch, now)
        ReportDiagnostics.refreshSoftware(SentryApi("https://unused.invalid", "", bridge = { _, _ ->
            throw IllegalStateException("secret response")
        }))
        ReportDiagnostics.refreshSoftware(SentryApi("https://unused.invalid", "", bridge = { _, _ ->
            DiagnosticLog.beginConnection()
            software().apply { getJSONObject("installed").put("displayVersion", "WRONG") }
        }))
        val log = ReportDiagnostics.capture(context, "r", now)
        assertTrue(log.contains("Comma version: 7.0.0.6"))
        assertFalse(log.contains("WRONG"))
        assertFalse(log.contains("secret response"))
    }

    @Test fun permissionInventoryIncludesGrantedDeniedAndFlavorPermissions() {
        val info = android.content.pm.PackageInfo().apply {
            packageName = context.packageName
            requestedPermissions = arrayOf(android.Manifest.permission.CAMERA,
                android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.REQUEST_INSTALL_PACKAGES)
        }
        org.robolectric.Shadows.shadowOf(context.packageManager).installPackage(info)
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.CAMERA)
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val inventory = ReportDiagnostics.permissions(context)
        assertEquals("3 requested by this installed app", inventory["App permissions"])
        assertEquals("Granted", inventory["Permission android.permission.CAMERA"])
        assertEquals("Not granted", inventory["Permission android.permission.ACCESS_FINE_LOCATION"])
        assertEquals("Not allowed (special access)", inventory["Permission android.permission.REQUEST_INSTALL_PACKAGES"])
        assertTrue(inventory.containsKey("App notifications allowed"))
        assertFalse(inventory.containsKey("Permission android.permission.RECORD_AUDIO"))
        assertTrue(ReportDiagnostics.capture(context, "r", now).contains("Permission android.permission.CAMERA: Granted"))
    }

    @Test fun routeTimelineIsRedactedAndAnEndedBleSessionIsNotReportedAsConnected() {
        DiagnosticLog.routeStatus("HOME_NETWORK", "FAILED", "No answer at 192.168.1.8")
        DiagnosticLog.activeConnection = "BLUETOOTH"
        val log = ReportDiagnostics.capture(context, "r", now)
        assertTrue(log.contains("Active connection: Disconnected (last page used Bluetooth)"))
        assertTrue(log.contains("HOME_NETWORK: FAILED: No answer at [IP redacted]"))
        assertFalse(log.contains("192.168.1.8"))
    }
}
