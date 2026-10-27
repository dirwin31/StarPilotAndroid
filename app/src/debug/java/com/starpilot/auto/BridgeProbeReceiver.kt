package com.starpilot.auto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Debug-only probe for a two-package Binder handshake on a test device. */
class BridgeProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val completion = goAsync()
        Thread {
            if (intent.action == "com.starpilot.auto.DEBUG_PROBE_REVIEW_MODE") {
                val wasActive = ReviewMode.isActive(context)
                try {
                    ReviewMode.setActive(context, true)
                    val demo = CarReviewDemo(context.applicationContext)
                    val monitor = demo.request("/api/system/monitor", null)
                    val model = demo.request("/api/models/status", null)
                    val drives = demo.request("/api/drives/stats?timezone=UTC", null)
                    val hub = demo.request("/api/settings/pages/hub", null)
                    val controls = demo.request("/api/settings/pages/demo-controls", null)
                    val preview = demo.request("/api/settings/preview", org.json.JSONObject()
                        .put("view", "demo-controls").put("row", 0).put("value", "Off"))
                    val confirmed = demo.request("/api/settings/confirm", org.json.JSONObject()
                        .put("intent", preview.getString("intent")).put("confirmed", true))
                    check(confirmed.getBoolean("saved"))
                    check(demo.request("/api/settings/pages/demo-controls", null)
                        .getJSONArray("rows").getJSONObject(0).getString("value") == "Off")
                    Log.i("GalaxyBridgeProbe", "review_mode=active monitor=${monitor.length()} model=${model.length()} drives=${drives.length()} sections=${hub.getJSONArray("sections").length()} controls=${controls.getJSONArray("rows").length()} saved=true")
                } catch (failure: Exception) {
                    Log.e("GalaxyBridgeProbe", "review mode failed: ${failure.message}", failure)
                } finally {
                    ReviewMode.setActive(context, wasActive)
                    completion.finish()
                }
                return@Thread
            }
            val client = GalaxyBridgeClient(context.applicationContext)
            try {
                if (intent.action == "com.starpilot.auto.DEBUG_PROBE_SETTINGS") {
                    val page = client.request("/api/settings/pages/developer", null)
                    val row = page.getJSONArray("rows").getJSONObject(0)
                    val next = if (row.getString("value") == "On") "Off" else "On"
                    Log.i("GalaxyBridgeProbe", "previewing=${row.getString("label")} view=${page.getString("view")}")
                    val preview = client.request("/api/settings/preview", org.json.JSONObject()
                        .put("view", page.getString("view")).put("row", 0).put("value", next))
                    Log.i("GalaxyBridgeProbe", "preview_ok=${preview.has("intent")}")
                } else {
                    Log.i("GalaxyBridgeProbe", "protocol=${client.handshake()}")
                val result = runCatching { client.request("/api/system/monitor", null) }
                Log.i("GalaxyBridgeProbe", result.fold(
                    onSuccess = { "request_ok=${it.has("status")}" },
                    onFailure = { "request_error=${it.message}" }
                ))
                }
            } catch (failure: Exception) {
                Log.e("GalaxyBridgeProbe", "handshake failed: ${failure.message}", failure)
            } finally {
                client.close()
                completion.finish()
            }
        }.start()
    }
}
