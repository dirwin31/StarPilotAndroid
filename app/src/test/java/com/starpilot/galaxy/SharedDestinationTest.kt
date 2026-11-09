package com.starpilot.galaxy

import org.junit.Assert.*
import org.junit.Test

class SharedDestinationTest {
    private fun place(text: String) = SharedDestination.parse(text) as? SharedDestination.Place

    @Test fun readsCoordinatesFromMapLinks() {
        for (text in listOf("https://maps.google.com/@12.3,-45.6,15z", "https://maps.google.com/?q=12.3,-45.6", "geo:12.3,-45.6(Home)",
                "google.navigation:q=12.3,-45.6", "https://maps.google.com/?daddr=12.3,-45.6",
                "https://maps.google.com/@1,2,15z/data=!3d12.3!4d-45.6", "https://maps.apple.com/?ll=12.3,-45.6&q=Cafe",
                "https://maps.google.com/?q=12.3%2C-45.6")) {
            val result = place(text)
            assertNotNull(text, result)
            assertEquals(text, 12.3, result!!.latitude, 0.0); assertEquals(text, -45.6, result.longitude, 0.0)
        }
    }

    @Test fun namesThePlace() {
        assertEquals("Home", place("geo:12.3,-45.6(Home)")?.name)
        assertEquals("Cafe", place("https://maps.apple.com/?ll=12.3,-45.6&q=Cafe")?.name)
        val bellagio = place("Bellagio\n3600 S Las Vegas Blvd\nhttps://www.google.com/maps/place/Bellagio+Hotel/@36.11,-115.17,17z/data=!3d36.1126!4d-115.1767")
        assertEquals("Bellagio Hotel", bellagio?.name); assertEquals(36.1126, bellagio!!.latitude, 0.0)
    }

    @Test fun searchesWithoutCoordinates() {
        assertNull(place("geo:123,456"))
        assertEquals(SharedDestination.Search("10 Main Street"), SharedDestination.parse("geo:0,0?q=10+Main+Street"))
        assertEquals(SharedDestination.Search("Bellagio, 3600 S Las Vegas Blvd"), SharedDestination.parse("Bellagio\n3600 S Las Vegas Blvd\nhttps://maps.app.goo.gl/AbC123"))
        assertEquals(SharedDestination.Search("100% Coffee"), SharedDestination.parse("100% Coffee"))
    }

    @Test fun findsShortLinks() {
        assertEquals("https://maps.app.goo.gl/AbC123", SharedDestination.SHORT_LINK.find("Bellagio https://maps.app.goo.gl/AbC123 ")?.value)
        assertTrue(SharedDestination.isGoogleMapsHost("www.google.com"))
        assertFalse(SharedDestination.isGoogleMapsHost("evilgoogle.com"))
    }
}
