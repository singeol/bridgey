package dev.bridgey.android

import android.app.Notification
import android.telephony.TelephonyManager
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

    @Test fun reconnectedListenerRetractsOnlyNotificationsThatAreNoLongerActive() {
        val tracked = linkedSetOf("one", "two", "three")

        assertTrue(staleForwardedNotificationIds(tracked, setOf("two")) == listOf("one", "three"))
        assertTrue(staleForwardedNotificationIds(tracked, tracked).isEmpty())
        assertTrue(staleForwardedNotificationIds(emptySet(), setOf("one")).isEmpty())
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

    @Test fun mapsPlatformPhoneStateWithoutReadingCallHistory() {
        assertTrue(telephonyCallType(TelephonyManager.CALL_STATE_RINGING) == "incoming")
        assertTrue(telephonyCallType(TelephonyManager.CALL_STATE_OFFHOOK) == "ongoing")
        assertTrue(telephonyCallType(TelephonyManager.CALL_STATE_IDLE) == "idle")
    }

    @Test fun exposesRequiredCallStyleActionsWhenPhoneAppDoesNotPublishRegularActions() {
        assertTrue(callStyleFallbackActions("incoming").map { it.title } == listOf("Decline", "Answer"))
        assertTrue(callStyleFallbackActions("ongoing").map { it.title } == listOf("Hang Up"))
        assertTrue(callStyleFallbackActions("screening").map { it.title } == listOf("Hang Up", "Answer"))
        assertTrue(callStyleFallbackActions("unknown").isEmpty())
    }

    @Test fun callStyleActionsSurviveTheForwardedActionLimit() {
        val callStyle = listOf("Decline" to "decline-intent", "Answer" to "answer-intent")
        val own = listOf(
            "Reply" to "reply-intent",
            "Message" to "message-intent",
            "Remind me" to "remind-intent",
            "Speaker" to "speaker-intent",
        )

        val actions = prioritizedCallActions(callStyle, own) { it.second }.take(4)

        assertTrue(actions.map { it.first } == listOf("Decline", "Answer", "Reply", "Message"))
    }

    @Test fun ownActionSharingACallStyleIntentIsNotRepeated() {
        val callStyle = listOf("Hang Up" to "hang-up-intent")
        val own = listOf("End call" to "hang-up-intent", "Speaker" to "speaker-intent")

        val actions = prioritizedCallActions(callStyle, own) { it.second }

        assertTrue(actions.map { it.first } == listOf("Hang Up", "Speaker"))
    }

    @Test fun requiredCallIntentsOverrideAnIncorrectReportedCallType() {
        assertTrue(resolvedNotificationCallType("ongoing", hasAnswer = true, hasDecline = true, hasHangUp = false) == "incoming")
        assertTrue(resolvedNotificationCallType("incoming", hasAnswer = false, hasDecline = false, hasHangUp = true) == "ongoing")
        assertTrue(resolvedNotificationCallType("unknown", hasAnswer = true, hasDecline = false, hasHangUp = true) == "screening")
        assertTrue(resolvedNotificationCallType("incoming", hasAnswer = false, hasDecline = false, hasHangUp = false) == "incoming")
        assertTrue(resolvedNotificationCallType("ongoing", hasAnswer = true, hasDecline = true, hasHangUp = true) == "ongoing")
    }

    @Test fun samsungFullScreenCallIntentOverridesItsIncorrectOngoingType() {
        assertTrue(
            resolvedNotificationCallType(
                reportedType = "ongoing",
                hasAnswer = false,
                hasDecline = false,
                hasHangUp = true,
                hasFullScreenIntent = true,
            ) == "incoming",
        )
        assertTrue(
            resolvedNotificationCallType(
                reportedType = "ongoing",
                hasAnswer = false,
                hasDecline = false,
                hasHangUp = true,
                hasFullScreenIntent = false,
            ) == "ongoing",
        )
    }

    @Test fun appCallKeepingItsFullScreenIntentAfterAnsweringIsOngoing() {
        assertTrue(
            resolvedNotificationCallType(
                reportedType = "ongoing",
                hasAnswer = false,
                hasDecline = false,
                hasHangUp = true,
                hasFullScreenIntent = true,
                isTelephonyCall = false,
            ) == "ongoing",
        )
        assertTrue(
            resolvedNotificationCallType(
                reportedType = "incoming",
                hasAnswer = false,
                hasDecline = false,
                hasHangUp = true,
                hasFullScreenIntent = true,
                isTelephonyCall = false,
            ) == "ongoing",
        )
        assertTrue(
            resolvedNotificationCallType(
                reportedType = "ongoing",
                hasAnswer = true,
                hasDecline = true,
                hasHangUp = false,
                hasFullScreenIntent = true,
                isTelephonyCall = false,
            ) == "incoming",
        )
        assertTrue(
            resolvedNotificationCallType(
                reportedType = "unknown",
                hasAnswer = false,
                hasDecline = false,
                hasHangUp = false,
                hasFullScreenIntent = true,
                isTelephonyCall = false,
            ) == "incoming",
        )
    }

    @Test fun telephonyStateOverridesAmbiguousSamsungNotificationSignals() {
        assertTrue(
            resolvedNotificationCallType(
                reportedType = "ongoing",
                hasAnswer = false,
                hasDecline = false,
                hasHangUp = true,
                hasFullScreenIntent = true,
                telephonyCallType = "incoming",
            ) == "incoming",
        )
        assertTrue(
            resolvedNotificationCallType(
                reportedType = "ongoing",
                hasAnswer = false,
                hasDecline = false,
                hasHangUp = true,
                hasFullScreenIntent = true,
                telephonyCallType = "ongoing",
            ) == "ongoing",
        )
    }

    @Test fun systemCallControlsMatchTheResolvedCallState() {
        assertTrue(systemCallActionTitles("incoming", 36) == listOf("Decline", "Answer"))
        assertTrue(systemCallActionTitles("ongoing", 36) == listOf("Hang Up"))
        assertTrue(systemCallActionTitles("screening", 36) == listOf("Hang Up", "Answer"))
        assertTrue(systemCallActionTitles("incoming", 26) == listOf("Answer"))
        assertTrue(systemCallActionTitles("unknown", 36).isEmpty())
    }

    @Test fun telephonyOverridesApplyOnlyToDialerAndSystemInCallNotifications() {
        assertTrue(isTelephonyCallSource("com.google.android.dialer", "com.google.android.dialer", null) { false })
        assertTrue(isTelephonyCallSource("com.android.dialer", "third.party.dialer", "com.android.dialer") { false })
        assertTrue(isTelephonyCallSource("com.samsung.android.incallui", "com.samsung.android.dialer", null) { true })
        assertFalse(isTelephonyCallSource("com.whatsapp", "com.google.android.dialer", "com.android.dialer") { false })
        assertFalse(isTelephonyCallSource("org.thoughtcrime.securesms", null, null) { false })
    }

    @Test fun dialerMatchSkipsTheInCallServiceLookup() {
        var lookedUp = false
        assertTrue(isTelephonyCallSource("com.google.android.dialer", "com.google.android.dialer", null) { lookedUp = true; false })
        assertFalse(lookedUp)
    }

    @Test fun systemCallActionsRequireAMatchingTelecomState() {
        assertTrue(canPerformSystemCallAction(SystemCallAction.ANSWER, isRinging = true, isInCall = true, sdkInt = 36))
        assertFalse(canPerformSystemCallAction(SystemCallAction.ANSWER, isRinging = false, isInCall = true, sdkInt = 36))
        assertTrue(canPerformSystemCallAction(SystemCallAction.END, isRinging = false, isInCall = true, sdkInt = 36))
        assertFalse(canPerformSystemCallAction(SystemCallAction.END, isRinging = false, isInCall = false, sdkInt = 36))
        assertFalse(canPerformSystemCallAction(SystemCallAction.END, isRinging = true, isInCall = true, sdkInt = 27))
    }

    @Test fun ongoingCallPostsUseASettleDelayToSuppressTerminalSamsungUpdates() {
        assertTrue(shouldDelayCallPost("ongoing"))
        assertFalse(shouldDelayCallPost("incoming"))
        assertFalse(shouldDelayCallPost(null))
    }
}
