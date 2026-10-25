package link.firestar.companion

class DemoApi(private val fixture: (String) -> String?, private val network: (String) -> String = { error("Demo has no network") }) {
    data class Reply(val status: Int, val body: String)
    private val overlay = mutableMapOf<String, String>()
    @Synchronized fun request(method: String, target: String, body: String = "{}", merge: (String, String) -> String = { _, next -> next }): Reply {
        val path = target.substringBefore('?')
        if (!path.startsWith("/api/") || path.contains("..") || path.contains('%') || path.contains('\\') || path.contains("//")) return Reply(403, "{\"error\":\"Demo only\"}")
        val original = overlay[path] ?: fixture(path.removePrefix("/api/") + ".json") ?: return Reply(404, "{\"error\":\"Not included in the demo\"}")
        if (method == "GET" || method == "HEAD") return Reply(200, original)
        if (method !in setOf("POST", "PUT", "PATCH", "DELETE")) return Reply(405, "{\"error\":\"Unsupported method\"}")
        overlay[path] = merge(original, body)
        return Reply(200, overlay[path]!!)
    }
}
