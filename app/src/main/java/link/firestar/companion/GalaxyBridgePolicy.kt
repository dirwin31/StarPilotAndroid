package link.firestar.companion

import org.json.JSONObject

/** The car bridge exposes only the existing car screen's read and write actions. */
internal object GalaxyBridgePolicy {
    fun allowed(method: String, path: String): Boolean {
        if (path.length > 180 || path.contains("..") || path.contains("//") || path.contains('\\')) return false
        val stats = path.startsWith("/api/drives/stats?timezone=") && runCatching {
            java.net.URLDecoder.decode(path.substringAfter('='), "UTF-8").matches(Regex("[A-Za-z0-9_+/-]{1,50}"))
        }.getOrDefault(false)
        val page = path.removePrefix("/api/settings/pages/").replace(Regex("%2F", RegexOption.IGNORE_CASE), "/")
        val validPage = path.startsWith("/api/settings/pages/") && page.matches(Regex("[A-Za-z0-9_/-]{1,80}")) &&
            !page.contains("..") && !page.contains("//")
        val read = path in setOf("/api/system/monitor", "/api/models/status") || stats || validPage
        val write = path in setOf("/api/settings/preview", "/api/settings/confirm")
        return method == "GET" && read || method == "POST" && write
    }

    fun validBody(path: String, body: JSONObject): Boolean = when (path) {
        "/api/settings/preview" -> validPreview(body.keys().asSequence().toSet(), body.optString("view"), body.optInt("row", -1), body.optString("value"))
        "/api/settings/confirm" -> validConfirm(body.keys().asSequence().toSet(), body.optString("intent"), body.optBoolean("confirmed"), body.optString("page"))
        else -> false
    }

    fun validPreview(keys: Set<String>, view: String, row: Int, value: String) =
        keys == setOf("view", "row", "value") && view.matches(Regex("[A-Za-z0-9_/-]{1,80}")) &&
            row in 0..100 && value in setOf("On", "Off")

    fun validConfirm(keys: Set<String>, intent: String, confirmed: Boolean, page: String) =
        keys == setOf("intent", "confirmed", "page") && intent.length in 1..256 && confirmed &&
            page.matches(Regex("[A-Za-z0-9_/-]{1,80}"))
}
