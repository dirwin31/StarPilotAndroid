package com.starpilot.galaxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDiscoveryTest {
    private fun ip(value: String) = value.split('.').map { it.toInt().toByte() }.toByteArray()
    private fun same(local: String, prefix: Int, remote: String) = LanDiscovery.sameSubnet(ip(local), prefix, ip(remote))

    @Test fun prefixZeroMatchesEverything() {
        assertTrue(same("192.168.1.10", 0, "10.0.0.1"))
        assertTrue(same("192.168.1.10", 0, "255.255.255.255"))
    }

    @Test fun wholeBytePrefixes() {
        assertTrue(same("10.1.2.3", 8, "10.200.0.9"))
        assertFalse(same("10.1.2.3", 8, "11.1.2.3"))
        assertTrue(same("192.168.1.10", 24, "192.168.1.25"))
        assertFalse(same("192.168.1.10", 24, "192.168.2.25"))
        assertFalse(same("192.168.1.10", 24, "192.169.1.10"))
    }

    @Test fun partialBytePrefixesWithHighBitBytes() {
        // 172.16.0.0/12 spans 172.16.x.x–172.31.x.x; the second byte's top four bits decide it.
        assertTrue(same("172.16.0.1", 12, "172.31.254.1"))
        assertFalse(same("172.16.0.1", 12, "172.32.0.1"))
        // /20 splits the third byte; 0xF0 masks bytes whose sign bit is set.
        assertTrue(same("192.168.240.1", 20, "192.168.255.254"))
        assertFalse(same("192.168.240.1", 20, "192.168.239.1"))
        assertTrue(same("10.0.0.1", 20, "10.0.15.200"))
        assertFalse(same("10.0.0.1", 20, "10.0.16.1"))
        // /25 splits the last byte at its sign bit.
        assertTrue(same("192.168.1.130", 25, "192.168.1.255"))
        assertFalse(same("192.168.1.130", 25, "192.168.1.127"))
    }

    @Test fun narrowestPrefixes() {
        assertTrue(same("192.168.43.200", 31, "192.168.43.201"))
        assertFalse(same("192.168.43.200", 31, "192.168.43.202"))
        assertTrue(same("192.168.43.200", 32, "192.168.43.200"))
        assertFalse(same("192.168.43.200", 32, "192.168.43.201"))
    }

    @Test fun theStarPilotHotspotIsNotOnAHomeLan() {
        assertFalse(same("192.168.1.10", 24, "172.31.254.1"))
        assertTrue(same("172.31.254.20", 24, "172.31.254.1"))
    }

    @Test fun rejectsMalformedInput() {
        assertFalse(LanDiscovery.sameSubnet(ip("192.168.1.10"), 33, ip("192.168.1.10")))
        assertFalse(LanDiscovery.sameSubnet(ip("192.168.1.10"), -1, ip("192.168.1.10")))
        assertFalse(LanDiscovery.sameSubnet(ByteArray(16), 24, ip("192.168.1.10")))
        assertFalse(LanDiscovery.sameSubnet(ip("192.168.1.10"), 24, ByteArray(16)))
    }

    @Test fun matchesBitByBitAcrossEveryPartialPrefix() {
        for (prefix in 1..7) for (a in 0..255) for (b in 0..255) {
            val expected = (a xor b) shr (8 - prefix) == 0
            val actual = LanDiscovery.sameSubnet(byteArrayOf(a.toByte(), 0, 0, 0), prefix, byteArrayOf(b.toByte(), 0, 0, 0))
            if (expected != actual) throw AssertionError("prefix $prefix, $a vs $b")
        }
    }
}
