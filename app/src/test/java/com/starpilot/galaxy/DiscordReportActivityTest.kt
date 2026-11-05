package com.starpilot.galaxy

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiscordReportActivityTest {
    @Before fun clear() {
        RuntimeEnvironment.getApplication().getSharedPreferences("discord-reports", 0).edit().clear().commit()
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun texts(activity: DiscordReportActivity) = views(activity.window.decorView).filterIsInstance<TextView>().map { it.text.toString() }

    @Test fun screenRequiresSignInBeforeSending() {
        val controller = Robolectric.buildActivity(DiscordReportActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(texts(activity).contains("Connect with Discord"))
        views(activity.window.decorView).filterIsInstance<Button>().first { it.text == "Send report" }.performClick()
        assertTrue(texts(activity).contains("Connect with Discord first."))
        controller.pause().stop().destroy()
    }

    @Test fun unsolicitedCallbackDoesNotExchangeOrExposeTheCode() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("starpilot-discord://callback?state=forged&code=secret-code&iss=https%3A%2F%2Fdiscord.com"))
        val controller = Robolectric.buildActivity(DiscordReportActivity::class.java, intent).setup()
        val activity = controller.get()
        assertTrue(texts(activity).any { it.contains("could not be verified") })
        assertFalse(texts(activity).any { it.contains("secret-code") })
        assertNull(activity.intent.data)
        controller.pause().stop().destroy()
    }
}
