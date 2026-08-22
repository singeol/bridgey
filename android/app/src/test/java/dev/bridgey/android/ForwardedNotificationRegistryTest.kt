package dev.bridgey.android

import android.app.Notification
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

    @Test fun ongoingCallsAreForwardedWhileOtherOngoingNotificationsAreIgnored() {
        assertFalse(shouldIgnoreOngoingNotification(Notification.FLAG_ONGOING_EVENT, Notification.CATEGORY_CALL))
        assertTrue(shouldIgnoreOngoingNotification(Notification.FLAG_ONGOING_EVENT, Notification.CATEGORY_SERVICE))
        assertFalse(shouldIgnoreOngoingNotification(0, Notification.CATEGORY_SERVICE))
    }

    @Test fun readsBoundedCallTypeWithoutPhonePermissions() {
        assertTrue(notificationCallType(1) == "incoming")
        assertTrue(notificationCallType(2) == "ongoing")
        assertTrue(notificationCallType(99) == "unknown")
    }

    @Test fun exposesRequiredCallStyleActionsWhenPhoneAppDoesNotPublishRegularActions() {
        assertTrue(callStyleFallbackActions("incoming").map { it.title } == listOf("Decline", "Answer"))
        assertTrue(callStyleFallbackActions("ongoing").map { it.title } == listOf("Hang Up"))
        assertTrue(callStyleFallbackActions("screening").map { it.title } == listOf("Hang Up", "Answer"))
        assertTrue(callStyleFallbackActions("unknown").isEmpty())
    }

    @Test fun requiredCallIntentsOverrideAnIncorrectReportedCallType() {
        assertTrue(resolvedNotificationCallType("ongoing", hasAnswer = true, hasDecline = true, hasHangUp = false) == "incoming")
        assertTrue(resolvedNotificationCallType("incoming", hasAnswer = false, hasDecline = false, hasHangUp = true) == "ongoing")
        assertTrue(resolvedNotificationCallType("unknown", hasAnswer = true, hasDecline = false, hasHangUp = true) == "screening")
        assertTrue(resolvedNotificationCallType("incoming", hasAnswer = false, hasDecline = false, hasHangUp = false) == "incoming")
        assertTrue(resolvedNotificationCallType("ongoing", hasAnswer = true, hasDecline = true, hasHangUp = true) == "ongoing")
    }

    @Test fun ongoingCallPostsUseASettleDelayToSuppressTerminalSamsungUpdates() {
        assertTrue(shouldDelayCallPost("ongoing"))
        assertFalse(shouldDelayCallPost("incoming"))
        assertFalse(shouldDelayCallPost(null))
    }
}
