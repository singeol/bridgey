package dev.bridgey.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeySettingsTest {
    @Test
    fun globalSwitchOverridesPerDeviceSwitch() {
        assertFalse(effectiveFeatureEnabled(globalEnabled = false, deviceEnabled = true))
        assertFalse(effectiveFeatureEnabled(globalEnabled = false, deviceEnabled = null))
    }

    @Test
    fun deviceSwitchOverridesEnabledGlobalDefault() {
        assertFalse(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = false))
        assertTrue(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = true))
        assertTrue(effectiveFeatureEnabled(globalEnabled = true, deviceEnabled = null))
    }
}
