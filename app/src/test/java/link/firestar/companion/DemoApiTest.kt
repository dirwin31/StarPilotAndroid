package link.firestar.companion

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DemoApiTest {
    @Test fun fixturesAreJsonAndContainNoPersonalFields() {
        val root = File("src/main/assets/demo/api")
        assertTrue(root.isDirectory)
        val privateKeys = Regex("(?i)(dongle|serial|slug|token|password|secret|latitude|longitude|^lat$|^lon$|^lng$|ssid|address|^home$|^work$)")
        fun inspect(value: com.google.gson.JsonElement) {
            if (value.isJsonObject) value.asJsonObject.entrySet().forEach { (key, item) -> assertFalse(key, privateKeys.containsMatchIn(key) && !item.isJsonNull && !(item.isJsonArray && item.asJsonArray.size() == 0) && !(item.isJsonPrimitive && item.asJsonPrimitive.isBoolean)); inspect(item) }
            else if (value.isJsonArray) value.asJsonArray.forEach { inspect(it) }
            else if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                val text = value.asString
                assertFalse(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").containsMatchIn(text))
                assertFalse(Regex("(?i)^[0-9a-f]{16}$").matches(text))
            }
        }
        root.walkTopDown().filter { it.extension == "json" }.forEach { inspect(JsonParser.parseString(it.readText())) }
    }
    @Test fun allDemoRequestsStayOfflineAndMutationsPersist() {
        var calls = 0
        val api = DemoApi({ if (it == "settings.json") "{\"enabled\":false}" else null }, { calls++; error("Network opened") })
        assertEquals(200, api.request("GET", "/api/settings").status)
        api.request("POST", "/api/settings", "{\"enabled\":true}")
        assertEquals("{\"enabled\":true}", api.request("GET", "/api/settings").body)
        assertEquals(404, api.request("POST", "/api/unknown").status)
        assertEquals(403, api.request("GET", "https://example.com").status)
        assertEquals(403, api.request("GET", "/api/../secret").status)
        assertEquals(0, calls)
    }
}
