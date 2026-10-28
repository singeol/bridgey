package dev.bridgey.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings and trust written by released versions load with the current code (docs/upgrade-policy.md).
 * Each directory in testdata/migrations/android holds the SharedPreferences files of one release.
 */
class StorageMigrationTest {
    @Test fun fixturesCoverEveryStoredFormat() {
        assertEquals(listOf("v0.2.0", "v0.5.0", "v0.6.0-rc.2"), MigrationFixtures.versions)
        assertEquals("bridgey.settings", BridgeySettings.PREFERENCES_NAME)
        assertEquals("bridgey.trust", AndroidTrustRegistry.PREFERENCES_NAME)
        MigrationFixtures.versions.forEach { version ->
            listOf("bridgey", BridgeySettings.PREFERENCES_NAME, AndroidTrustRegistry.PREFERENCES_NAME).forEach { name ->
                assertTrue("$version/$name.xml", MigrationFixtures.file(version, "$name.xml").isFile)
            }
        }
    }

    @Test fun settingsFrom020KeepTheirValuesAndLaterFeaturesStartEnabled() {
        val state = settings("v0.2.0").state.value
        assertEquals("Test Phone", state.deviceName)
        assertFalse(state.globalFeatures.getValue(BridgeyFeature.NOTIFICATIONS))
        assertTrue(state.globalFeatures.getValue(BridgeyFeature.CLIPBOARD))
        // Features added after 0.2 have no stored value and use the current default.
        listOf(BridgeyFeature.PING, BridgeyFeature.LINKS, BridgeyFeature.MEDIA, BridgeyFeature.CALLS)
            .forEach { assertTrue(it.key, state.globalFeatures.getValue(it)) }
        assertEquals(mapOf(BridgeyFeature.FILES to false, BridgeyFeature.FIND_DEVICE to true), state.deviceFeatures[MAC_1])
        assertTrue(state.notificationApplications.isEmpty())
        assertTrue(state.disabledNotificationPackages.isEmpty())
        assertFalse(state.directCallsEnabled)
    }

    @Test fun settingsFrom050KeepNotificationFiltersAndCallChoices() {
        val settings = settings("v0.5.0")
        val state = settings.state.value
        assertFalse(state.globalFeatures.getValue(BridgeyFeature.BATTERY))
        assertTrue(state.globalFeatures.getValue(BridgeyFeature.CALLS))
        assertEquals(mapOf(BridgeyFeature.CLIPBOARD to false), state.deviceFeatures[MAC_1])
        assertEquals(mapOf(BridgeyFeature.CALLS to false), state.deviceFeatures[MAC_2])
        assertFalse(settings.isEnabled(BridgeyFeature.CALLS, MAC_2))
        assertTrue(settings.isEnabled(BridgeyFeature.CALLS, MAC_1))
        assertEquals(mapOf("com.example.chat" to "Chat", "com.example.mail" to "Mail"), state.notificationApplications)
        assertEquals(setOf("com.example.mail"), state.disabledNotificationPackages)
        assertFalse(settings.isNotificationApplicationEnabled("com.example.mail"))
        assertTrue(state.directCallsEnabled)
    }

    @Test fun settingsFrom060rc2LoadEveryFeature() {
        val state = settings("v0.6.0-rc.2").state.value
        assertEquals(
            mapOf(
                BridgeyFeature.CLIPBOARD to true, BridgeyFeature.FILES to true, BridgeyFeature.NOTIFICATIONS to true,
                BridgeyFeature.BATTERY to true, BridgeyFeature.FIND_DEVICE to true, BridgeyFeature.PING to true,
                BridgeyFeature.LINKS to false, BridgeyFeature.MEDIA to true, BridgeyFeature.CALLS to false,
            ),
            state.globalFeatures,
        )
        assertEquals(mapOf(BridgeyFeature.MEDIA to false, BridgeyFeature.LINKS to true), state.deviceFeatures[MAC_1])
        assertTrue(state.disabledNotificationPackages.isEmpty())
        assertFalse(state.directCallsEnabled)
    }

    @Test fun changingASettingKeepsEveryOtherStoredValue() {
        MigrationFixtures.versions.forEach { version ->
            val preferences = MigrationFixtures.preferences(version, BridgeySettings.PREFERENCES_NAME)
            val before = preferences.values.toMap()
            BridgeySettings(preferences, "Default").setGlobal(BridgeyFeature.PING, false)
            assertEquals(version, before + ("global.ping" to false), preferences.values)
        }
    }

    @Test fun unknownStoredKeysAreIgnored() {
        val preferences = MigrationFixtures.preferences("v0.6.0-rc.2", BridgeySettings.PREFERENCES_NAME)
        preferences.values["global.future_feature"] = false
        preferences.values["device.$MAC_1.future_feature"] = true
        preferences.values["future.setting"] = 7
        val state = BridgeySettings(preferences, "Default").state.value
        assertEquals(BridgeyFeature.entries.toSet(), state.globalFeatures.keys)
        assertEquals(setOf(BridgeyFeature.MEDIA, BridgeyFeature.LINKS), state.deviceFeatures.getValue(MAC_1).keys)
    }

    @Test fun trustedDevicesLoadFromEveryVersion() {
        val expected = mapOf(
            "v0.2.0" to listOf(TrustedDevice(MAC_1, "Test Mac")),
            "v0.5.0" to listOf(TrustedDevice(MAC_1, "Test Mac"), TrustedDevice(MAC_2, "Unknown device")),
            "v0.6.0-rc.2" to listOf(TrustedDevice(MAC_2, "Studio Mac"), TrustedDevice(MAC_1, "Test Mac")),
        )
        expected.forEach { (version, devices) ->
            val trust = AndroidTrustRegistry(MigrationFixtures.preferences(version, AndroidTrustRegistry.PREFERENCES_NAME))
            assertEquals(version, devices, trust.trustedDevices.value)
            assertEquals(version, devices.map(TrustedDevice::id).toSet(), trust.trustedDeviceIds.value)
            assertEquals(version, KEY_A, trust.identityKey(MAC_1))
        }
    }

    @Test fun forgettingADeviceRemovesOnlyItsKeys() {
        val preferences = MigrationFixtures.preferences("v0.6.0-rc.2", AndroidTrustRegistry.PREFERENCES_NAME)
        val trust = AndroidTrustRegistry(preferences)
        trust.remove(MAC_2)
        assertNull(trust.identityKey(MAC_2))
        assertEquals(setOf("peer.$MAC_1.name", "peer.$MAC_1.identityKey"), preferences.values.keys)
    }

    @Test fun applicationPreferencesKeepTheDeviceIdentifier() {
        MigrationFixtures.versions.forEach { version ->
            val preferences = MigrationFixtures.preferences(version, "bridgey")
            assertEquals(version, PHONE, preferences.getString("device_id", null))
            assertTrue(version, preferences.contains("bridgey_enabled"))
        }
    }

    private fun settings(version: String) =
        BridgeySettings(MigrationFixtures.preferences(version, BridgeySettings.PREFERENCES_NAME), "Default")

    private companion object {
        const val PHONE = "3c2b1a09-8f7e-4d6c-9b5a-4f3e2d1c0b9a"
        const val MAC_1 = "6f1d2c3b-4a59-4e68-9d7c-8b6a5f4e3d21"
        const val MAC_2 = "0a9b8c7d-6e5f-4a3b-8c2d-1e0f9a8b7c6d"
        const val KEY_A = "BGsX0fLhLEJH+Lzm5WOkQPJ3A32BLeszoPShOUXYmMKWT+NC4v4af5uO5+tKfA+eFivOM1drMV7Oy7ZAaDe/UfU="
    }
}
