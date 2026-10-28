package dev.bridgey.android

import org.junit.Assert.assertEquals
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

    @Test
    fun featureRequiresBothDevicesToOfferIt() {
        assertTrue(effectiveFeatureAvailable(localEnabled = true, remoteEnabled = true))
        assertFalse(effectiveFeatureAvailable(localEnabled = true, remoteEnabled = false))
        assertFalse(effectiveFeatureAvailable(localEnabled = false, remoteEnabled = true))
    }

    @Test
    fun newerFeaturesAreOffForLegacyPeers() {
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.CALLS))
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.PING))
        assertTrue(featureEnabledByLegacyPeer(BridgeyFeature.BATTERY))
    }

    @Test
    fun remoteInputIsOffByDefaultAndForLegacyPeers() {
        assertEquals("remote_input", BridgeyFeature.REMOTE_INPUT.key)
        assertFalse(featureEnabledByLegacyPeer(BridgeyFeature.REMOTE_INPUT))
        assertFalse(featureOnByDefault(BridgeyFeature.REMOTE_INPUT))
        assertTrue(featureOnByDefault(BridgeyFeature.CLIPBOARD))
    }
}
