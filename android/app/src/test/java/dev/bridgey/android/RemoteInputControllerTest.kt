package dev.bridgey.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputControllerTest {
    private class Sent(val deviceId: String, val kind: String, val json: JSONObject)

    private val sent = mutableListOf<Sent>()
    private var available = setOf("mac")
    private var now = 10_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val controller = RemoteInputController(
        scope,
        object : RemoteInputTransport {
            override fun send(deviceId: String, kind: String, payload: ByteArray): Boolean {
                sent += Sent(deviceId, kind, JSONObject(payload.toString(Charsets.UTF_8)))
                return true
            }
            override fun isAvailable(deviceId: String) = deviceId in available
        },
        clock = { now },
        outboxDispatcher = Dispatchers.Unconfined,
        runsTimers = false,
    )

    @After fun tearDown() = scope.cancel()

    private fun sessionId() = sent.last { it.kind == "input.session.start" }.json.getString("inputSessionId")

    private fun host(kind: String, sequence: Long, reason: String? = null, session: String = sessionId(), device: String = "mac") {
        val payload = JSONObject().put("version", 1).put("inputSessionId", session).put("sequence", sequence)
        if (reason != null) payload.put("reason", reason)
        controller.onHostMessage(device, kind, payload.toString().toByteArray())
    }

    private fun startActive() {
        controller.start("mac", RemoteMode.TOUCHPAD)
        host("input.session.started", 1)
    }

    @Test fun startWaitsForTheMacThenRuns() {
        controller.start("mac", RemoteMode.PRESENTATION)
        assertEquals(RemoteSessionState.Starting("mac", RemoteMode.PRESENTATION), controller.state.value)
        val start = sent.single()
        assertEquals("input.session.start", start.kind)
        assertEquals("presentation", start.json.getString("mode"))
        assertEquals(1L, start.json.getLong("sequence"))
        assertFalse("no input before the Mac answers", controller.send(RemoteInput.Pointer(1, 0)))
        host("input.session.started", 1)
        assertEquals(RemoteSessionState.Active("mac", RemoteMode.PRESENTATION, sessionId()), controller.state.value)
    }

    @Test fun inputGetsIncreasingSequencesInOrder() {
        startActive()
        assertTrue(controller.send(RemoteInput.Pointer(3, 4)))
        assertTrue(controller.send(RemoteInput.Button(PointerButton.LEFT, true)))
        assertTrue(controller.send(RemoteInput.Key(RemoteKey.RETURN)))
        assertEquals(listOf("input.session.start", "input.pointer", "input.button", "input.key"), sent.map { it.kind })
        assertEquals(listOf(1L, 2L, 3L, 4L), sent.map { it.json.getLong("sequence") })
        assertTrue(sent.all { it.json.getString("inputSessionId") == sessionId() && it.deviceId == "mac" })
    }

    @Test fun invalidInputIsNeverSent() {
        startActive()
        assertFalse(controller.send(RemoteInput.Pointer(9_999, 0)))
        assertFalse(controller.send(RemoteInput.Text("two\nlines")))
        assertFalse(controller.send(RemoteInput.Key(RemoteKey.PREVIOUS_APP, shift = true)))
        assertFalse("sessions start and stop through start and stop", controller.send(RemoteInput.Stop(RemoteStopReason.USER)))
        assertEquals(1, sent.size)
        assertEquals(4, controller.dropped)
    }

    @Test fun rateLimitsMatchTheMac() {
        startActive()
        val pointer = (0 until 150).count { controller.send(RemoteInput.Pointer(1, 0)) }
        val keys = (0 until 30).count { controller.send(RemoteInput.Key(RemoteKey.SPACE)) }
        assertEquals(120, pointer)
        assertEquals(20, keys)
        now += 1_000
        assertTrue(controller.send(RemoteInput.Pointer(1, 0)))
        assertEquals(40, controller.dropped)
    }

    @Test fun stopTellsTheMacAndShowsWhy() {
        startActive()
        controller.stop(RemoteStopReason.SCREEN_OFF)
        assertEquals("input.session.stop", sent.last().kind)
        assertEquals("screen_off", sent.last().json.getString("reason"))
        assertEquals(RemoteSessionState.Ended("mac", RemoteStopReason.SCREEN_OFF), controller.state.value)
        assertFalse(controller.send(RemoteInput.Pointer(1, 0)))
        val count = sent.size
        controller.stop()
        assertEquals("stopping twice sends nothing", count, sent.size)
    }

    @Test fun theMacEndingTheSessionIsShown() {
        startActive()
        host("input.session.stop", 2, reason = "local_stop")
        assertEquals(RemoteSessionState.Ended("mac", RemoteStopReason.LOCAL_STOP), controller.state.value)
        assertEquals("nothing sent back", "input.session.start", sent.last().kind)
    }

    @Test fun refusedStartsCarryTheMacsReason() {
        controller.start("mac", RemoteMode.TOUCHPAD)
        host("input.session.stop", 1, reason = "permission_required")
        assertEquals(RemoteSessionState.Ended("mac", RemoteStopReason.PERMISSION_REQUIRED), controller.state.value)
    }

    @Test fun staleReplayedOrForeignHostMessagesAreIgnored() {
        controller.start("mac", RemoteMode.TOUCHPAD)
        host("input.session.started", 1, session = "0b9a54a2-6a4c-4b9e-8f45-3c2a1d0e9f11")
        host("input.session.started", 1, device = "other-mac")
        assertTrue(controller.state.value is RemoteSessionState.Starting)
        host("input.session.started", 1)
        host("input.session.stop", 1, reason = "busy")
        assertTrue("a replayed sequence is ignored", controller.state.value is RemoteSessionState.Active)
        host("input.session.stop", 2, reason = "timeout")
        assertEquals(RemoteSessionState.Ended("mac", RemoteStopReason.TIMEOUT), controller.state.value)
    }

    @Test fun heartbeatsOnlyWhenQuiet() {
        startActive()
        now += 1_500
        controller.tick()
        assertEquals(1, sent.size)
        now += 600
        controller.tick()
        assertEquals("input.session.heartbeat", sent.last().kind)
        controller.send(RemoteInput.Pointer(1, 1))
        now += 1_000
        controller.tick()
        assertEquals("recent input keeps the session alive", "input.pointer", sent.last().kind)
    }

    @Test fun aMacThatNeverAnswersEndsTheStart() {
        controller.start("mac", RemoteMode.TOUCHPAD)
        now += 4_999
        controller.tick()
        assertTrue(controller.state.value is RemoteSessionState.Starting)
        now += 1
        controller.tick()
        assertEquals(RemoteSessionState.Ended("mac", RemoteStopReason.NO_ANSWER), controller.state.value)
        assertEquals("the Mac is told, in case it answers late", "user", sent.last().json.getString("reason"))
    }

    @Test fun disconnectOrLostPermissionEnds() {
        startActive()
        controller.connectionsChanged(setOf("mac", "other"))
        assertTrue(controller.state.value is RemoteSessionState.Active)
        controller.connectionsChanged(setOf("other"))
        assertEquals(RemoteSessionState.Ended("mac", RemoteStopReason.DISCONNECTED), controller.state.value)

        startActive()
        available = emptySet()
        controller.connectionsChanged(setOf("mac"))
        assertEquals(RemoteSessionState.Ended("mac", RemoteStopReason.DISABLED), controller.state.value)
    }

    @Test fun startingWhenNotAllowedEndsAtOnce() {
        available = emptySet()
        controller.start("mac", RemoteMode.TOUCHPAD)
        assertEquals(RemoteSessionState.Ended("mac", RemoteStopReason.DISABLED), controller.state.value)
        assertTrue(sent.isEmpty())
    }

    @Test fun aNewSessionGetsANewIdAndSequence() {
        startActive()
        val first = sessionId()
        controller.stop()
        sent.clear()
        controller.start("mac", RemoteMode.TOUCHPAD)
        assertEquals(1L, sent.single().json.getLong("sequence"))
        assertTrue(sessionId() != first)
    }

    @Test fun thisPhoneCannotBeControlled() {
        val start = JSONObject().put("version", 1).put("inputSessionId", "0b9a54a2-6a4c-4b9e-8f45-3c2a1d0e9f11").put("sequence", 1).put("mode", "touchpad")
        controller.onHostMessage("mac", "input.session.start", start.toString().toByteArray())
        assertEquals("input.session.stop", sent.single().kind)
        assertEquals("unsupported", sent.single().json.getString("reason"))
        assertEquals(RemoteSessionState.Idle, controller.state.value)
    }

    @Test fun clearForgetsTheEndedSession() {
        startActive()
        controller.stop()
        controller.clear()
        assertEquals(RemoteSessionState.Idle, controller.state.value)
    }

    @Test fun typedTextIsNotInToString() {
        assertFalse(RemoteInput.Text("hunter2").toString().contains("hunter2"))
    }
}
