package com.starpilot.galaxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSwitchPolicyTest {
    @Test fun onlyAvailableServerActionsWithBinaryValuesCanChangeInCar() {
        assertTrue(carSwitchAllowed(listOf("On", "Off"), true, true, "Off"))
        assertFalse(carSwitchAllowed(listOf("On", "Off"), false, true, "Off"))
        assertFalse(carSwitchAllowed(listOf("On", "Off"), true, false, "Off"))
        assertFalse(carSwitchAllowed(listOf("Eco", "Sport"), true, true, "Eco"))
        assertFalse(carSwitchAllowed(listOf("On", "Off"), true, true, "Unknown"))
    }
}
