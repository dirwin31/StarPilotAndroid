package com.starpilot.galaxy

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Settings changes made away from the comma, kept until the app next reaches it.
 *
 * While connected, the app saves each settings page comma serves. Offline, those saved pages are shown
 * and every change is queued here instead of sent. On the next connection the queue is applied through
 * Galaxy's own preview/confirm requests (see assets/offline-changes.js), so comma still validates each one.
 *
 * Everything is kept per comma: a change queued for one comma is never applied to another.
 */
class OfflineChanges(private val root: File, private val now: () -> Long = System::currentTimeMillis) {
    data class Target(val name: String, val savedAt: Long, val pending: Int)
    data class Summary(val applied: Int, val failed: List<String>, val waiting: Int)

    private var applied = 0
    private var waiting = 0
    private val failed = mutableListOf<String>()
    private val written = mutableSetOf<String>()

    private fun key(device: String) = MessageDigest.getInstance("SHA-256").digest(device.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    private fun home(key: String) = File(root, key)
    private fun current(): File? = runCatching { File(root, "current").readText() }.getOrNull()
        ?.takeIf { KEY.matches(it) }?.let(::home)?.takeIf { File(it, "device.json").isFile }
    private fun read(file: File): String? = runCatching { file.takeIf(File::isFile)?.readText() }.getOrNull()
    /** Replace the file in one step so a crash never leaves half a queue behind. */
    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val staged = File(file.parentFile, file.name + ".new")
        staged.writeText(text)
        if (!staged.renameTo(file)) { file.delete(); check(staged.renameTo(file)) { "Could not save offline changes" } }
    }
    private fun queue(home: File): MutableList<JSONObject> = runCatching {
        val items = JSONArray(read(File(home, "queue.json")) ?: "[]")
        MutableList(items.length()) { items.getJSONObject(it) }
    }.getOrDefault(mutableListOf())
    private fun save(home: File, ops: List<JSONObject>) = write(File(home, "queue.json"), JSONArray(ops).toString())
    private fun pageFile(home: File, page: String) = File(home, "pages/" + page.replace('/', '+') + ".json")
    private fun sameRow(op: JSONObject, page: String, label: String, nth: Int) =
        op.optString("page") == page && op.optString("label") == label && op.optInt("nth") == nth

    // ---- Offline session: read the saved pages, queue changes for the comma last connected. ----

    /** The comma offline changes are for, or null until one has been connected and its settings saved. */
    @Synchronized fun target(): Target? {
        val home = current() ?: return null
        val device = runCatching { JSONObject(read(File(home, "device.json")) ?: return null) }.getOrNull() ?: return null
        if (device.optLong("savedAt") <= 0 || File(home, "pages").list().isNullOrEmpty()) return null
        return Target(device.optString("name"), device.optLong("savedAt"), queue(home).size)
    }
    @Synchronized fun page(name: String): String = current()?.takeIf { PAGE.matches(name) }?.let { read(pageFile(it, name)) } ?: ""
    @Synchronized fun saved(path: String): String = current()?.takeIf { path in BOOT }?.let { read(File(it, "boot/" + path.removePrefix("/api/").replace('/', '+') + ".json")) } ?: ""
    @Synchronized fun pending(): String = JSONArray(current()?.let(::queue) ?: emptyList<JSONObject>()).toString()

    /** Queue a change. A newer change to the same setting replaces the older one. */
    @Synchronized fun enqueue(raw: String): Boolean {
        val home = current() ?: return false
        val input = runCatching { JSONObject(raw) }.getOrNull() ?: return false
        val page = input.optString("page"); val label = input.optString("label"); val nth = input.optInt("nth", -1)
        val kind = input.optString("kind"); val value = input.opt("value"); val shown = input.optString("shown")
        if (!PAGE.matches(page) || label.isEmpty() || label.length > 200 || nth !in 0..50 || kind !in setOf("value", "reset") ||
            !(value is String || value is Number) || value.toString().length > 128 || shown.isEmpty() || shown.length > 128) return false
        val ops = queue(home)
        ops.removeAll { sameRow(it, page, label, nth) }
        if (ops.size >= MAX_QUEUE) return false
        ops.add(JSONObject().put("id", UUID.randomUUID().toString()).put("page", page).put("title", input.optString("title").take(120))
            .put("label", label).put("nth", nth).put("kind", kind).put("value", value).put("shown", shown).put("at", now()).put("attempts", 0))
        save(home, ops)
        return true
    }
    @Synchronized fun drop(page: String, label: String, nth: Int) {
        val home = current() ?: return
        val ops = queue(home)
        if (ops.removeAll { sameRow(it, page, label, nth) }) save(home, ops)
    }
    /** Throw away every change waiting for the current comma. */
    @Synchronized fun clear() { current()?.let { save(it, emptyList()) } }
    /** One line per waiting change, for the person to review. */
    @Synchronized fun describe(): List<String> = (current()?.let(::queue) ?: emptyList()).map {
        "${it.optString("title")} · ${it.optString("label")}: ${if (it.optString("kind") == "reset") "default (${it.optString("shown")})" else it.optString("shown")}"
    }

    // ---- Live session: hand the queue to the page to apply, then save fresh pages. ----

    /** Start a sync with the connected comma, which becomes the one offline changes are for. */
    @Synchronized fun begin(device: String, name: String, slow: Boolean): String {
        if (device.isEmpty() || device.length > 300) return "{\"ops\":[],\"crawl\":false,\"gap\":0}"
        val id = key(device); val home = home(id)
        val file = File(home, "device.json")
        val saved = runCatching { JSONObject(read(file) ?: "{}") }.getOrDefault(JSONObject())
        write(file, saved.put("name", name.take(60)).toString())
        write(File(root, "current"), id)
        applied = 0; waiting = 0; failed.clear(); written.clear()
        // Saving pages costs comma a request each, so do it rarely; over Bluetooth, rarer and gently.
        val age = now() - saved.optLong("savedAt")
        val crawl = File(home, "pages").list().isNullOrEmpty() || age < 0 || age > (if (slow) 24 * 3600_000L else 600_000L)
        return JSONObject().put("ops", JSONArray(queue(home))).put("crawl", crawl).put("gap", if (slow) 300 else 0).toString()
    }
    @Synchronized fun result(device: String, id: String, state: String, message: String) {
        val home = home(key(device)); val ops = queue(home)
        val op = ops.firstOrNull { it.optString("id") == id } ?: return
        val what = "${op.optString("label")} (${message.take(120).ifEmpty { "could not be applied" }})"
        when (state) {
            "applied" -> { ops.remove(op); applied++ }
            "rejected" -> { ops.remove(op); failed.add(what) }
            "waiting" -> waiting++
            // Inconclusive answers may clear up later, but not forever.
            "retry" -> if (op.optInt("attempts") + 1 >= MAX_ATTEMPTS) { ops.remove(op); failed.add(what) } else { op.put("attempts", op.optInt("attempts") + 1); waiting++ }
            else -> return
        }
        save(home, ops)
    }
    @Synchronized fun snapshot(device: String, page: String, text: String) {
        if (!PAGE.matches(page) || text.length > MAX_PAGE || runCatching { JSONObject(text).getJSONArray("rows") }.isFailure) return
        write(pageFile(home(key(device)), page), text); written.add(page)
    }
    @Synchronized fun saved(device: String, path: String, text: String) {
        if (path !in BOOT || text.length > 20_000 || runCatching { JSONObject(text) }.isFailure) return
        write(File(home(key(device)), "boot/" + path.removePrefix("/api/").replace('/', '+') + ".json"), text)
    }
    /** End a sync. [complete] is false when the connection dropped partway; nothing is discarded then. */
    @Synchronized fun finish(device: String, complete: Boolean): Summary {
        val home = home(key(device))
        if (complete && written.isNotEmpty()) {
            // Pages comma no longer serves must not linger as editable.
            File(home, "pages").listFiles()?.filter { it.name.removeSuffix(".json").replace('+', '/') !in written }?.forEach { it.delete() }
            val file = File(home, "device.json")
            write(file, runCatching { JSONObject(read(file) ?: "{}") }.getOrDefault(JSONObject()).put("savedAt", now()).toString())
        }
        return Summary(applied, failed.toList(), waiting).also { applied = 0; waiting = 0; failed.clear(); written.clear() }
    }

    companion object {
        const val MAX_QUEUE = 200
        const val MAX_ATTEMPTS = 5
        private const val MAX_PAGE = 600_000
        private val KEY = Regex("[0-9a-f]{32}")
        private val PAGE = Regex("[a-z_]{1,40}(/[a-z_]{1,40})?")
        private val BOOT = setOf("/api/auth/session", "/api/local-access", "/api/galaxy/device-name")

        @Volatile private var shared: OfflineChanges? = null
        fun of(context: android.content.Context): OfflineChanges = shared ?: synchronized(this) {
            shared ?: OfflineChanges(File(context.applicationContext.filesDir, "offline-changes")).also { shared = it }
        }
    }
}
