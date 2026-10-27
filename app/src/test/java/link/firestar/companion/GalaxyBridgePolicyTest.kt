package link.firestar.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalaxyBridgePolicyTest {
    @Test fun carScreenActionsAreAllowed() {
        assertTrue(GalaxyBridgePolicy.allowed("GET", "/api/system/monitor"))
        assertTrue(GalaxyBridgePolicy.allowed("GET", "/api/models/status"))
        assertTrue(GalaxyBridgePolicy.allowed("GET", "/api/drives/stats?timezone=America%2FNew_York"))
        assertTrue(GalaxyBridgePolicy.allowed("GET", "/api/settings/pages/hub"))
        assertTrue(GalaxyBridgePolicy.allowed("GET", "/api/settings/pages/conditional/foo"))
        assertTrue(GalaxyBridgePolicy.allowed("GET", "/api/settings/pages/conditional%2Fexperimental"))
        assertTrue(GalaxyBridgePolicy.allowed("GET", "/api/settings/pages/conditional%2fchill"))
        assertTrue(GalaxyBridgePolicy.allowed("POST", "/api/settings/preview"))
        assertTrue(GalaxyBridgePolicy.allowed("POST", "/api/settings/confirm"))
    }

    @Test fun unrelatedAndEncodedPathsAreDenied() {
        assertFalse(GalaxyBridgePolicy.allowed("POST", "/api/system/monitor"))
        assertFalse(GalaxyBridgePolicy.allowed("GET", "/api/settings/confirm"))
        assertFalse(GalaxyBridgePolicy.allowed("POST", "/api/device/restart"))
        assertFalse(GalaxyBridgePolicy.allowed("GET", "/api/settings/pages/../secrets"))
        assertFalse(GalaxyBridgePolicy.allowed("GET", "/api/settings/pages/%2e%2e/secrets"))
        assertFalse(GalaxyBridgePolicy.allowed("GET", "/api/settings/pages/conditional%252Fexperimental"))
        assertFalse(GalaxyBridgePolicy.allowed("GET", "/api/settings/pages/conditional%2F..%2Fsecrets"))
        assertFalse(GalaxyBridgePolicy.allowed("GET", "/api/drives/stats?timezone=America%2F..%2Fetc"))
    }

    @Test fun carWritesRequireKnownFieldsAndSimpleValues() {
        assertTrue(GalaxyBridgePolicy.validPreview(setOf("view", "row", "value"), "display", 2, "On"))
        assertFalse(GalaxyBridgePolicy.validPreview(setOf("view", "row", "value"), "display", 2, "100"))
        assertTrue(GalaxyBridgePolicy.validConfirm(setOf("intent", "confirmed", "page"), "token", true, "display"))
        assertFalse(GalaxyBridgePolicy.validConfirm(setOf("intent", "confirmed"), "token", true, "display"))
        assertFalse(GalaxyBridgePolicy.validConfirm(setOf("intent", "confirmed", "page"), "token", false, "display"))
    }
}
