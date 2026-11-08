package com.starpilot.galaxy

import android.Manifest
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SentryPushStateTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("companion", Context.MODE_PRIVATE)
    private fun state() = JSONObject(SentryPush.state(context))
    @Before fun setup() {
        prefs.edit().clear().commit()
        SentryPush.api = null
        SentryPush.suspended = false
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun cleanup() { SentryPush.api = null; prefs.edit().clear().commit() }

    @Test fun disabledStatusTracksOutstandingOriginsInsteadOfTokenPresence() {
        prefs.edit().putString("fcmToken", "token").putString("fcmAcknowledgement", "Registered").commit()
        assertEquals("Alerts off", state().getString("status"))
        prefs.edit().putString("fcmSubscription:https://comma.test", "old-token").commit()
        assertTrue(state().getString("status").contains("Reconnect"))
        assertFalse(state().getBoolean("registered"))
    }

    @Test fun permissionAndRegistrationFailuresDoNotShowStaleSuccess() {
        prefs.edit().putBoolean("sentryPushEnabled", true).putString("fcmRegisteredOrigin", "https://comma.test")
            .putString("fcmAcknowledgement", "Registered").commit()
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(state().getString("status").startsWith("Allow notifications"))
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        if (FirebaseRegistration.available) {
            for (failure in listOf("Registration failed", "Registration failed; reconnect to retry")) {
                prefs.edit().putString("fcmAcknowledgement", failure).commit()
                assertTrue(state().getString("status").startsWith("Couldn't register"))
            }
            prefs.edit().putString("fcmAcknowledgement", "Phone registered; waiting for relay enrollment").commit()
            assertEquals("Phone registered; waiting for relay enrollment", state().getString("status"))
        } else assertTrue(state().getString("status").startsWith("Polling only"))
    }

    @Test fun disablingOfflineMigratesOlderRegistrationForRetry() {
        prefs.edit().putBoolean("sentryPushEnabled", true).putString("fcmToken", "token")
            .putString("fcmRegisteredOrigin", "https://comma.test").commit()
        assertTrue(SentryPush.enable(context, false))
        assertEquals("token", prefs.getString("fcmSubscription:https://comma.test", null))
        assertFalse(prefs.contains("fcmRegisteredOrigin"))
        assertTrue(state().getString("status").contains("Reconnect"))
    }

    @Test fun failedRemovalRetriesOnlyTheConnectedOrigin() {
        val key = "fcmSubscription:https://comma.test"
        val other = "fcmSubscription:https://other.test"
        prefs.edit().putString(key, "old-token").putString(other, "other-token").commit()
        val failed = CountDownLatch(1)
        val failure = SentryApi("https://comma.test", "", bridge = { _, _ ->
            failed.countDown(); throw IllegalStateException("offline")
        })
        SentryPush.registerIfNeeded(context, failure)
        assertTrue(failed.await(3, TimeUnit.SECONDS))
        synchronized(SentryPush) { assertEquals("old-token", prefs.getString(key, null)) }
        val removed = CountDownLatch(1)
        val success = SentryApi("https://comma.test", "", bridge = { path, body ->
            assertEquals("/api/sentry/notifications", path)
            assertEquals("appUnsubscribe", body!!.getString("action"))
            assertEquals("old-token", body.getString("token"))
            removed.countDown(); JSONObject()
        })
        SentryPush.registerIfNeeded(context, success)
        assertTrue(removed.await(3, TimeUnit.SECONDS))
        synchronized(SentryPush) {
            assertFalse(prefs.contains(key))
            assertEquals("other-token", prefs.getString(other, null))
        }
        assertTrue(state().getString("status").contains("Reconnect"))
    }
}
