package com.starpilot.galaxy

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class OfflineChangesTest {
    @get:Rule val folder = TemporaryFolder()
    private var clock = 1_000_000L
    private fun store() = OfflineChanges(folder.root) { clock }
    private val page = "{\"page\":\"slc\",\"title\":\"Speed\",\"rows\":[]}"
    private fun op(label: String, value: Any = "On", page: String = "slc", nth: Int = 0, kind: String = "value") =
        JSONObject().put("page", page).put("title", "Speed").put("label", label).put("nth", nth).put("kind", kind).put("value", value).put("shown", value.toString()).toString()
    /** Connect to a comma and save one page, as a completed live sync does. */
    private fun connect(store: OfflineChanges, device: String = "https://x/a", name: String = "Car"): JSONObject {
        val plan = JSONObject(store.begin(device, name, false))
        store.snapshot(device, "slc", page)
        return plan
    }
    private fun ids(store: OfflineChanges) = JSONArray(store.pending()).let { list -> List(list.length()) { list.getJSONObject(it) } }

    @Test fun nothingIsOfferedUntilACommaHasBeenSaved() {
        val store = store()
        assertNull(store.target())
        assertFalse(store.enqueue(op("A")))
        connect(store); store.finish("https://x/a", true)
        assertEquals(OfflineChanges.Target("Car", clock, 0), store.target())
        assertEquals(page, store.page("slc"))
        assertEquals("", store.page("../device"))
    }

    @Test fun aNewerChangeToTheSameSettingReplacesTheOlderOne() {
        val store = store(); connect(store); store.finish("https://x/a", true)
        assertTrue(store.enqueue(op("A", "On")))
        assertTrue(store.enqueue(op("B", 3)))
        assertTrue(store.enqueue(op("A", "Off")))
        assertTrue(store.enqueue(op("A", "On", nth = 1)))
        assertEquals(listOf("B=3", "A=Off", "A=On"), ids(store).map { it.getString("label") + "=" + it.get("value") })
        store.drop("slc", "B", 0)
        assertEquals(2, store.target()!!.pending)
        assertEquals(2, store().target()!!.pending) // survives a restart
        store.clear()
        assertEquals(0, store.target()!!.pending)
    }

    @Test fun malformedChangesAreRefused() {
        val store = store(); connect(store); store.finish("https://x/a", true)
        assertFalse(store.enqueue("nope"))
        assertFalse(store.enqueue(op("A", page = "../x")))
        assertFalse(store.enqueue(op("A", kind = "reboot")))
        assertFalse(store.enqueue(op("", "On")))
        assertFalse(store.enqueue(op("A", "x".repeat(200))))
        assertFalse(store.enqueue(JSONObject(op("A")).put("value", JSONObject()).toString()))
    }

    @Test fun changesStayWithTheCommaTheyWereMadeFor() {
        val store = store(); connect(store, "https://x/a", "A"); store.finish("https://x/a", true)
        store.enqueue(op("Mine"))
        // A different comma connects: it gets no changes and becomes the offline target.
        val other = connect(store, "https://x/b", "B")
        assertEquals(0, other.getJSONArray("ops").length())
        store.finish("https://x/b", true)
        assertEquals("B", store.target()!!.name)
        assertEquals(0, store.target()!!.pending)
        // The first comma returns and still has its change.
        val back = JSONObject(store.begin("https://x/a", "A", false))
        assertEquals("Mine", back.getJSONArray("ops").getJSONObject(0).getString("label"))
    }

    @Test fun outcomesDecideWhatStaysQueued() {
        val store = store(); connect(store); store.finish("https://x/a", true)
        listOf("Applied", "Rejected", "Waiting", "Retry").forEach { store.enqueue(op(it)) }
        val plan = JSONObject(store.begin("https://x/a", "Car", false)).getJSONArray("ops")
        val id = (0 until 4).associate { plan.getJSONObject(it).getString("label") to plan.getJSONObject(it).getString("id") }
        store.result("https://x/a", id["Applied"]!!, "applied", "")
        store.result("https://x/a", id["Rejected"]!!, "rejected", "no longer offered")
        store.result("https://x/a", id["Waiting"]!!, "waiting", "")
        store.result("https://x/a", id["Retry"]!!, "retry", "busy")
        store.result("https://x/a", "unknown", "applied", "")
        store.result("https://x/b", id["Waiting"]!!, "applied", "") // another comma cannot clear it
        val summary = store.finish("https://x/a", true)
        assertEquals(1, summary.applied)
        assertEquals(listOf("Rejected (no longer offered)"), summary.failed)
        assertEquals(2, summary.waiting)
        assertEquals(listOf("Waiting", "Retry"), ids(store).map { it.getString("label") })
    }

    @Test fun inconclusiveChangesAreGivenUpAfterTheAttemptLimit() {
        val store = store(); connect(store); store.finish("https://x/a", true)
        store.enqueue(op("Stuck"))
        repeat(OfflineChanges.MAX_ATTEMPTS - 1) {
            val id = JSONObject(store.begin("https://x/a", "Car", false)).getJSONArray("ops").getJSONObject(0).getString("id")
            store.result("https://x/a", id, "retry", "busy")
            assertTrue(store.finish("https://x/a", true).failed.isEmpty())
        }
        val id = JSONObject(store.begin("https://x/a", "Car", false)).getJSONArray("ops").getJSONObject(0).getString("id")
        store.result("https://x/a", id, "retry", "busy")
        assertEquals(listOf("Stuck (busy)"), store.finish("https://x/a", true).failed)
        assertEquals(0, store.target()!!.pending)
    }

    @Test fun pagesAreRefreshedRarelyAndStaleOnesRemoved() {
        val store = store()
        assertTrue(connect(store).getBoolean("crawl"))
        store.snapshot("https://x/a", "gone", page)
        store.snapshot("https://x/a", "bad", "not json")
        store.saved("https://x/a", "/api/auth/session", "{\"authenticated\":true}")
        store.saved("https://x/a", "/api/galaxy/session", "{\"sessionToken\":\"secret\"}") // never kept
        store.finish("https://x/a", true)
        assertEquals("{\"authenticated\":true}", store.saved("/api/auth/session"))
        assertEquals("", store.saved("/api/galaxy/session"))
        assertEquals("", store.page("bad"))
        clock += 60_000
        assertFalse(JSONObject(store.begin("https://x/a", "Car", false)).getBoolean("crawl"))
        store.finish("https://x/a", true)
        clock += 3600_000
        assertFalse(JSONObject(store.begin("https://x/a", "Car", true)).getBoolean("crawl")) // Bluetooth waits a day
        assertTrue(JSONObject(store.begin("https://x/a", "Car", false)).getBoolean("crawl"))
        store.snapshot("https://x/a", "slc", page)
        // A dropped connection keeps the older pages.
        store.finish("https://x/a", false)
        assertEquals(page, store.page("gone"))
        store.begin("https://x/a", "Car", false); store.snapshot("https://x/a", "slc", page)
        store.finish("https://x/a", true)
        assertEquals("", store.page("gone"))
        assertEquals(clock, store.target()!!.savedAt)
    }
}
