package dev.bridgey.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardedNotificationRegistryTest {
    @Test fun removesOnlyForwardedNotificationsOnce() {
        val registry = ForwardedNotificationRegistry()
        registry.record("one", "system-one", "one.app")

        assertTrue(registry.removeSystemKey("system-one") == "one")
        assertTrue(registry.removeSystemKey("system-one") == null)
        assertTrue(registry.systemKey("one") == null)
    }

    @Test fun evictsOldestNotificationAtLimit() {
        val registry = ForwardedNotificationRegistry(limit = 2)
        registry.record("one", "system-one", "one.app")
        registry.record("two", "system-two", "two.app")
        registry.record("three", "system-three", "three.app")

        assertTrue(registry.systemKey("one") == null)
        assertTrue(registry.systemKey("two") == "system-two")
        assertTrue(registry.systemKey("three") == "system-three")
    }

    @Test fun removesEveryMirroredNotificationForFilteredApplication() {
        val registry = ForwardedNotificationRegistry()
        registry.record("one", "system-one", "chat.app")
        registry.record("two", "system-two", "mail.app")
        registry.record("three", "system-three", "chat.app")

        assertTrue(registry.removePackage("chat.app") == listOf("one", "three"))
        assertTrue(registry.systemKey("one") == null)
        assertTrue(registry.systemKey("two") == "system-two")
    }

    @Test fun createsStableOpaqueNotificationToken() {
        val token = notificationToken("0|package|42|tag|uid")

        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        assertTrue(token == notificationToken("0|package|42|tag|uid"))
        assertFalse(token == notificationToken("different"))
        assertFalse(notificationActionToken(token, 0) == notificationActionToken(token, 1))
    }
}
