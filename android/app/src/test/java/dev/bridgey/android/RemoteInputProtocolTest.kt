package dev.bridgey.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputProtocolTest {
    private val session = "0b9a54a2-6a4c-4b9e-8f45-3c2a1d0e9f11"

    private fun json(input: RemoteInput, sequence: Long = 7): JSONObject =
        JSONObject(RemoteInputCodec.encode(input, session, sequence).toString(Charsets.UTF_8))

    // Message validation and bounds

    @Test fun everyMessageCarriesVersionSessionAndSequence() {
        val payload = json(RemoteInput.Pointer(3, -4), sequence = 42)
        assertEquals(1, payload.getInt("version"))
        assertEquals(session, payload.getString("inputSessionId"))
        assertEquals(42L, payload.getLong("sequence"))
        assertEquals(3, payload.getInt("dx"))
        assertEquals(-4, payload.getInt("dy"))
        assertEquals("input.pointer", RemoteInput.Pointer(1, 0).kind)
    }

    @Test fun pointerDragAndScrollDeltasAreBounded() {
        assertTrue(RemoteInput.Pointer(512, -512).isValid())
        assertFalse(RemoteInput.Pointer(513, 0).isValid())
        assertFalse(RemoteInput.Pointer(0, -513).isValid())
        assertFalse(RemoteInput.Pointer(0, 0).isValid())
        assertTrue(RemoteInput.DragMove(-512, 1).isValid())
        assertFalse(RemoteInput.DragMove(600, 0).isValid())
        assertTrue(RemoteInput.Scroll(0, 1000).isValid())
        assertFalse(RemoteInput.Scroll(0, 1001).isValid())
        assertTrue(RemoteInput.Scroll(10, 0, ScrollUnit.LINE).isValid())
        assertFalse(RemoteInput.Scroll(11, 0, ScrollUnit.LINE).isValid())
        assertEquals("pixel", json(RemoteInput.Scroll(0, 5)).getString("unit"))
        assertEquals("move", json(RemoteInput.DragMove(1, 1)).getString("phase"))
        assertEquals("start", json(RemoteInput.DragStart).getString("phase"))
        assertEquals("end", json(RemoteInput.DragEnd).getString("phase"))
    }

    @Test fun buttonsSayDownOrUp() {
        val down = json(RemoteInput.Button(PointerButton.RIGHT, down = true))
        assertEquals("right", down.getString("button"))
        assertEquals("down", down.getString("action"))
        assertEquals("up", json(RemoteInput.Button(PointerButton.LEFT, down = false)).getString("action"))
    }

    @Test fun textIsBoundedWithoutControlCharacters() {
        assertTrue(isValidRemoteText("Hello, Привет 👋"))
        assertTrue(isValidRemoteText("a".repeat(256)))
        assertFalse(isValidRemoteText("a".repeat(257)))
        assertFalse(isValidRemoteText(""))
        // Emoji count once each: 256 four-byte characters are exactly 1,024 bytes.
        assertTrue(isValidRemoteText("𝄞".repeat(256)))
        assertFalse(isValidRemoteText("𝄞".repeat(257)))
        listOf("a\nb", "a\rb", "a\tb", "\u001B[31m", "a\u0000b", "a\u007F", "a\u0085b", "a\uD800b").forEach {
            assertFalse(it, isValidRemoteText(it))
        }
        assertFalse(RemoteInput.Text("line\nbreak").isValid())
        assertEquals("Text(4 chars)", RemoteInput.Text("pass").toString())
    }

    @Test fun typedTextIsCutAndStrippedBeforeSending() {
        assertEquals("ab", boundedRemoteText("a\nb"))
        assertEquals("a".repeat(256), boundedRemoteText("a".repeat(300)))
        assertEquals(256, boundedRemoteText("👋".repeat(300)).codePointCount(0, boundedRemoteText("👋".repeat(300)).length))
        assertTrue(isValidRemoteText(boundedRemoteText("tab\there " + "x".repeat(400))))
    }

    @Test fun presentationActionsCarryPositionOnlyForTheLaser() {
        assertTrue(RemoteInput.Present(PresentAction.NEXT).isValid())
        assertFalse(RemoteInput.Present(PresentAction.NEXT, 1, 1).isValid())
        assertTrue(RemoteInput.Present(PresentAction.LASER_ON, 0, 10_000).isValid())
        assertFalse(RemoteInput.Present(PresentAction.LASER_ON).isValid())
        assertFalse(RemoteInput.Present(PresentAction.LASER_MOVE, 10_001, 0).isValid())
        assertFalse(RemoteInput.Present(PresentAction.LASER_MOVE, -1, 0).isValid())
        val laser = json(RemoteInput.Present(PresentAction.LASER_MOVE, 5000, 2500))
        assertEquals("laser_move", laser.getString("action"))
        assertEquals(5000, laser.getInt("x"))
        assertFalse(json(RemoteInput.Present(PresentAction.BLACK)).has("x"))
    }

    @Test fun rateClassesMatchTheHost() {
        assertEquals(RateClass.MOTION, RemoteInput.Pointer(1, 0).rateClass)
        assertEquals(RateClass.MOTION, RemoteInput.Scroll(1, 0).rateClass)
        assertEquals(RateClass.MOTION, RemoteInput.DragMove(1, 0).rateClass)
        assertEquals(RateClass.MOTION, RemoteInput.Present(PresentAction.LASER_MOVE, 1, 1).rateClass)
        assertEquals(RateClass.DISCRETE, RemoteInput.Present(PresentAction.NEXT).rateClass)
        assertEquals(RateClass.DISCRETE, RemoteInput.Key(RemoteKey.TAB).rateClass)
        assertEquals(RateClass.DISCRETE, RemoteInput.Text("a").rateClass)
        assertEquals(RateClass.CONTROL, RemoteInput.Heartbeat.rateClass)
    }

    // Key allowlist

    @Test fun onlyNamedKeysAndShiftReachTheWire() {
        assertEquals(
            listOf("left", "right", "up", "down", "return", "escape", "tab", "space", "backspace",
                "page_up", "page_down", "home", "end", "f5", "previous_app"),
            RemoteKey.entries.map { it.wire },
        )
        RemoteKey.entries.forEach { key ->
            val plain = json(RemoteInput.Key(key))
            assertEquals(key.wire, plain.getString("key"))
            assertFalse(plain.has("modifiers"))
        }
        val shifted = json(RemoteInput.Key(RemoteKey.TAB, shift = true))
        assertEquals("[\"shift\"]", shifted.getJSONArray("modifiers").toString())
        assertFalse("the one shortcut takes no Shift", RemoteInput.Key(RemoteKey.PREVIOUS_APP, shift = true).isValid())
        assertTrue(RemoteInput.Key(RemoteKey.PREVIOUS_APP).isValid())
    }

    @Test fun stopReasonsSentByThePhoneAreTheControllerOnes() {
        assertTrue(RemoteInput.Stop(RemoteStopReason.USER).isValid())
        assertTrue(RemoteInput.Stop(RemoteStopReason.LEFT_SCREEN).isValid())
        assertTrue(RemoteInput.Stop(RemoteStopReason.SCREEN_OFF).isValid())
        assertFalse(RemoteInput.Stop(RemoteStopReason.LOCAL_STOP).isValid())
        assertFalse(RemoteInput.Stop(RemoteStopReason.DISCONNECTED).isValid())
        assertEquals("screen_off", json(RemoteInput.Stop(RemoteStopReason.SCREEN_OFF)).getString("reason"))
    }

    // Host messages

    private fun host(kind: String, fields: JSONObject): HostMessage? = RemoteInputCodec.decodeHostMessage(kind, fields.toString().toByteArray())

    private fun base(sequence: Any = 1) = JSONObject().put("version", 1).put("inputSessionId", session).put("sequence", sequence)

    @Test fun hostMessagesAreValidated() {
        assertEquals(HostMessage.Started(session, 1, 2_000), host("input.session.started", base().put("heartbeatSeconds", 2)))
        assertEquals(HostMessage.Started(session, 1, 2_000), host("input.session.started", base()))
        assertEquals(HostMessage.Stopped(session, 2, RemoteStopReason.PERMISSION_REQUIRED),
            host("input.session.stop", base(2).put("reason", "permission_required")))
        assertEquals(HostMessage.Stopped(session, 2, RemoteStopReason.INVALID), host("input.session.stop", base(2).put("reason", "nonsense")))
        assertEquals(HostMessage.StartFromPeer(session, 1), host("input.session.start", base().put("mode", "touchpad")))
        assertNull(host("input.session.started", base().put("version", 2)))
        assertNull(host("input.session.started", base(0)))
        assertNull(host("input.session.started", base("1")))
        assertNull(host("input.session.started", base(1.5)))
        assertNull(host("input.session.started", base().put("inputSessionId", "nope")))
        assertNull(host("input.pointer", base().put("dx", 1).put("dy", 1)))
        assertNull(RemoteInputCodec.decodeHostMessage("input.session.stop", "not json".toByteArray()))
        assertNull(RemoteInputCodec.decodeHostMessage("input.session.stop", ByteArray(2_049) { 'a'.code.toByte() }))
    }

    // Sequence and replay

    @Test fun sequenceAcceptsOnlyIncreasingValues() {
        val sequence = InputSequence()
        assertTrue(sequence.accept(1))
        assertTrue(sequence.accept(2))
        assertFalse("replay", sequence.accept(2))
        assertFalse("older", sequence.accept(1))
        assertTrue("gaps are fine", sequence.accept(10))
        assertFalse(sequence.accept(0))
        assertFalse(sequence.accept(-3))
        assertFalse(sequence.accept(RemoteInputLimits.MAX_SEQUENCE + 1))
        assertTrue(sequence.accept(RemoteInputLimits.MAX_SEQUENCE))
        assertFalse(sequence.accept(RemoteInputLimits.MAX_SEQUENCE))
    }

    // Rate limiter

    @Test fun bucketAllowsOneSecondBurstThenItsRate() {
        val bucket = TokenBucket(120.0)
        assertEquals(120, (0 until 200).count { bucket.allow(1_000) })
        assertFalse(bucket.allow(1_001))
        assertTrue("1.2 tokens after 10 ms", bucket.allow(1_010))
        assertFalse(bucket.allow(1_010))
        assertEquals("never more than a second's worth", 120, (0 until 500).count { bucket.allow(3_600_000) })
    }

    @Test fun bucketSustainsTwentyASecondAndIgnoresClockSteps() {
        val bucket = TokenBucket(20.0)
        val accepted = (0 until 1_000).count { bucket.allow(it * 10L) }
        assertTrue("$accepted", accepted in 216..222)
        val stepped = TokenBucket(20.0)
        repeat(20) { stepped.allow(50_000) }
        assertFalse(stepped.allow(10_000))
    }

    @Test fun limiterKeepsClassesApart() {
        val limiter = RemoteRateLimiter()
        assertEquals(120, (0 until 150).count { limiter.allow(RateClass.MOTION, 5) })
        assertEquals(20, (0 until 50).count { limiter.allow(RateClass.DISCRETE, 5) })
        assertEquals(10, (0 until 50).count { limiter.allow(RateClass.CONTROL, 5) })
    }
}
