package com.starpilot.galaxy

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class SentryEnrollmentTest {
    @Test fun registrationDoesNotClaimDeliveryWhenRelayNeedsEnrollment() {
        val result = JSONObject().put("appPushAvailable", false)
            .put("relayEnrollment", JSONObject().put("state", "unenrolled").put("automaticConfigured", true))
        assertTrue(SentryPush.relayStatus(result).contains("enroll this comma"))
        result.getJSONObject("relayEnrollment").put("state", "actionRequired")
        assertTrue(SentryPush.relayStatus(result).contains("authorization needs attention"))
        result.getJSONObject("relayEnrollment").put("state", "revoking")
        assertTrue(SentryPush.relayStatus(result).contains("paused"))
    }
    @Test fun olderCommaStatusRemainsSupported() {
        assertTrue(SentryPush.relayStatus(JSONObject().put("appPushAvailable", true)).contains("send a test"))
        assertTrue(SentryPush.relayStatus(JSONObject()).contains("relay not configured"))
    }
    @Test fun explicitEnableEnrollsWithoutCodesAndDoesNotRepeatOnReconnect() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("companion", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("sentryPushEnabled", true).putBoolean("sentryEnrollRequested", true).commit()
        var requests = 0
        val api = SentryApi("https://comma.test", "", bridge = { path, body ->
            assertEquals("/api/sentry/notifications", path)
            assertEquals("relayEnroll", body!!.getString("action"))
            assertTrue(body.getBoolean("confirmed"))
            assertEquals(2, body.length())
            requests++
            JSONObject().put("relayEnrollment", JSONObject().put("state", "enrolling"))
        })
        val status = JSONObject().put("relayEnrollment", JSONObject().put("state", "unenrolled").put("automaticConfigured", true))
        SentryPush.enrollIfRequested(context, api, status)
        SentryPush.enrollIfRequested(context, api, status)
        assertEquals(1, requests)
        assertFalse(prefs.getBoolean("sentryEnrollRequested", false))
        prefs.edit().clear().commit()
    }
}
