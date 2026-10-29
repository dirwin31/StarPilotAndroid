package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class GalaxyLinksTest {
    @Test fun opensSignedPackagesAndMirrorPagesOutsideGalaxy() {
        assertTrue(GalaxyLinks.external("https://download.pureapk.com/b/XAPK/com.google.android.projection.gearhead?version=latest&token=signed"))
        assertTrue(GalaxyLinks.external("https://download.pureapk.com/b/APK/com.google.android.projection.gearhead"))
        assertTrue(GalaxyLinks.external("https://www.apkmirror.com/apk/google-inc/android-auto/"))
    }

    @Test fun refusesLocalFilesIntentsAndNonHttpsDestinations() {
        listOf("file:///data/data/link.firestar.companion/files/key", "content://downloads/1",
            "intent://download/#Intent;scheme=https;end", "javascript:alert(1)", "http://example.com/app.apk",
            "https://user:password@example.com/app.apk", "https://example.com:8082/app.apk", "https:///app.apk", "not a URL").forEach {
            assertFalse(it, GalaxyLinks.external(it))
        }
    }
}
