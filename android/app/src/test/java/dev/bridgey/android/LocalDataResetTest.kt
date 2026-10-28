package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings, Reset Bridgey on this phone, applied to the stores of every released version. */
class LocalDataResetTest {
    @Test fun resettingSettingsDeletesEveryStoredValueAndRestoresDefaults() {
        MigrationFixtures.versions.forEach { version ->
            val preferences = MigrationFixtures.preferences(version, BridgeySettings.PREFERENCES_NAME)
            val settings = BridgeySettings(preferences, "Default")
            settings.reset()
            assertTrue(version, preferences.values.isEmpty())
            val state = settings.state.value
            assertEquals("Default", state.deviceName)
            assertTrue(state.globalFeatures.values.all { it })
            assertTrue(state.deviceFeatures.isEmpty())
            assertTrue(state.notificationApplications.isEmpty())
            assertTrue(state.disabledNotificationPackages.isEmpty())
            assertFalse(state.directCallsEnabled)
        }
    }

    @Test fun clearingTrustForgetsEveryDevice() {
        MigrationFixtures.versions.forEach { version ->
            val preferences = MigrationFixtures.preferences(version, AndroidTrustRegistry.PREFERENCES_NAME)
            val trust = AndroidTrustRegistry(preferences)
            val known = trust.trustedDeviceIds.value
            assertTrue(version, known.isNotEmpty())
            trust.clear()
            assertTrue(version, preferences.values.isEmpty())
            assertTrue(trust.trustedDeviceIds.value.isEmpty())
            assertTrue(trust.trustedDevices.value.isEmpty())
            known.forEach { assertNull(trust.identityKey(it)) }
        }
    }
}
