package com.starpilot.auto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Offline, opt-in data for reviewers using Android Auto without the primary app. */
internal class CarReviewDemo(private val context: Context) {
    private val pending = mutableMapOf<String, Triple<String, Int, String>>()
    private val pages = mutableMapOf<String, JSONObject>()

    @Synchronized fun request(path: String, payload: JSONObject?): JSONObject {
        if (path == "/api/settings/preview") {
            val body = requireNotNull(payload)
            val view = body.getString("view")
            val row = body.getInt("row")
            val value = body.getString("value")
            val item = page(view).getJSONArray("rows").getJSONObject(row)
            require(item.optBoolean("available") && item.optBoolean("action") &&
                value in listOf("On", "Off")) { "This demo control is unavailable" }
            val token = UUID.randomUUID().toString()
            pending[token] = Triple(view, row, value)
            return JSONObject().put("intent", token)
                .put("question", "Set ${item.getString("label")} to $value in the demo?")
        }
        if (path == "/api/settings/confirm") {
            val change = pending.remove(requireNotNull(payload).getString("intent"))
                ?: error("This demo change expired")
            require(payload.optBoolean("confirmed"))
            page(change.first).getJSONArray("rows").getJSONObject(change.second)
                .put("value", change.third)
            return JSONObject().put("saved", true)
        }
        if (payload != null) error("This demo action is unavailable")
        if (path == "/api/settings/pages/hub") return JSONObject()
            .put("sections", JSONArray().put(JSONObject().put("id", "demo")
                .put("title", "Galaxy demo")
                .put("pages", JSONArray().put(JSONObject().put("id", "demo-controls")
                    .put("title", "Sample controls")))))
        if (path == "/api/settings/pages/demo-controls") return page("demo-controls")
        val asset = path.substringBefore('?').removePrefix("/api/")
        require(path.startsWith("/api/") && !asset.contains("..") && !asset.contains('%') &&
            !asset.contains('\\')) { "Invalid demo request" }
        return JSONObject(context.assets.open("demo/api/$asset.json").bufferedReader().use { it.readText() })
    }

    private fun page(view: String): JSONObject {
        require(view == "demo-controls") { "Unknown demo page" }
        return pages.getOrPut(view) {
            JSONObject().put("view", view).put("title", "Sample controls")
                .put("rows", JSONArray()
                    .put(control("Cabin display", "On"))
                    .put(control("Parked alerts", "Off")))
        }
    }

    private fun control(label: String, value: String) = JSONObject()
        .put("label", label).put("value", value).put("available", true)
        .put("action", true)
        .put("choices", JSONArray().put("On").put("Off"))
        .put("reason", "Simulated setting")
}
