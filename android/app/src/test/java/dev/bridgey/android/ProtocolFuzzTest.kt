package dev.bridgey.android

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Property-based fuzzing of the frame reader, the envelope decoder and the payload decoders: random bytes,
 * mutated, truncated, oversized and deeply nested frames, wrong types, unknown kinds and replayed IDs.
 * Each property checks that nothing escapes except the documented session-closing errors, that whatever is
 * accepted stays within the contract's bounds, and that the reader finds the next frame afterwards.
 *
 * Runs are deterministic: [ITERATIONS] cases per property from a fixed seed. For longer runs set
 * BRIDGEY_FUZZ_ITERATIONS and, to explore other inputs, BRIDGEY_FUZZ_SEED; a failure prints its seed. The
 * macOS suite (ProtocolFuzzTests.swift) runs the same properties.
 */
class ProtocolFuzzTest {
    private val iterations = System.getenv("BRIDGEY_FUZZ_ITERATIONS")?.toIntOrNull() ?: ITERATIONS
    private val seed = System.getenv("BRIDGEY_FUZZ_SEED")?.toLongOrNull() ?: DEFAULT_SEED
    private val random = Random(seed)
    private val vectors = JSONObject(
        File(File(System.getProperty("bridgey.repoRoot") ?: "../.."), "protocol/test-vectors/protocol-v1.json").readText(Charsets.UTF_8),
    )
    private val sessionId = vectors.getString("sessionId")
    private val validFrames: List<String> = vectors.getJSONArray("frames").let { array ->
        (0 until array.length()).map(array::getJSONObject)
            .filter { it.has("line") && !it.has("lineEnding") && it.get("expect") is JSONObject }
            .map { it.getString("line") }
    }

    @Test fun randomBytesNeverEscapeTheReader() = property("random bytes", scale = 10) {
        val bytes = random.nextBytes(random.nextInt(0, 3 * MAX_PROTOCOL_FRAME_BYTES))
        // Some LFs, so random data also forms many short frames.
        repeat(random.nextInt(0, 64)) { if (bytes.isNotEmpty()) bytes[random.nextInt(bytes.size)] = '\n'.code.toByte() }
        drain(ByteArrayInputStream(bytes))
    }

    @Test fun mutatedFramesDecodeOrCloseCleanly() = property("mutated frames") {
        decodeOrClose(mutate(validFrames.random(random).toByteArray()))?.let(::assertWithinBounds)
    }

    @Test fun truncatedFramesNeverDecode() = property("truncated frames") {
        val frame = validFrames.random(random).trimEnd()
        // A strict prefix of an object is never a complete object.
        assertTrue(decodeOrClose(frame.substring(0, random.nextInt(0, frame.length)).toByteArray()) == null)
    }

    @Test fun wrongTypesAndUnknownKindsAreHandled() = property("structured envelopes") {
        val json = JSONObject()
        listOf("kind", "sessionId", "messageId", "deviceId", "deviceName", "publicKey", "nonce", "ciphertext", "transferId", "sequence", "extra")
            .filter { random.nextInt(3) != 0 }
            .forEach { json.put(it, randomJson(depth = 0)) }
        if (random.nextBoolean()) json.put("kind", randomKind())
        if (random.nextBoolean()) json.put("sessionId", sessionId)
        val decoded = decodeOrClose(json.toString().toByteArray())
        if (decoded != null) {
            assertWithinBounds(decoded)
            assertEquals(json.get("kind"), decoded.kind)
        }
        // A frame the strict check accepts must also be readable by org.json.
        if (isStrictJsonObject(json.toString())) assertNotNull(runCatching { JSONObject(json.toString()) }.getOrNull())
    }

    @Test fun oversizedAndDeepFramesClose() = property("oversized and deep frames", scale = 20) {
        val size = MAX_PROTOCOL_FRAME_BYTES + random.nextInt(1, 4096)
        val oversized = ("{\"kind\":\"ping.ack\",\"sessionId\":\"$sessionId\",\"x\":\"" + "a".repeat(size) + "\"}\n").toByteArray()
        assertTrue(runCatching { ByteArrayInputStream(oversized).readProtocolLine() }.exceptionOrNull() is ProtocolFrameTooLargeException)
        val depth = random.nextInt(MAX_PROTOCOL_JSON_DEPTH + 1, 30_000)
        val deep = "{\"kind\":\"x.y\",\"sessionId\":\"$sessionId\",\"x\":" + "[".repeat(depth) + "]".repeat(depth) + "}"
        assertTrue(decodeOrClose(deep.toByteArray()) == null)
        // Unbalanced nesting (only openers) must not recurse either.
        assertTrue(decodeOrClose(("{\"a\":" + "{\"a\":".repeat(depth)).toByteArray()) == null)
    }

    @Test fun readerFindsTheNextFrameAfterABadOne() = property("resynchronize") {
        // A bad frame closes the real session; the reader itself must still find the next frame boundary.
        val good = validFrames.random(random)
        val garbage = mutate(validFrames.random(random).toByteArray()).filter { it != '\n'.code.toByte() }.toByteArray()
        val stream = ByteArrayInputStream(garbage + "\n".toByteArray() + good.toByteArray() + "\n".toByteArray())
        runCatching { stream.readProtocolLine() }
        if (garbage.size <= MAX_PROTOCOL_FRAME_BYTES) assertEquals(good, stream.readProtocolLine())
    }

    @Test fun replayWindowMatchesAReferenceModel() = property("replay window", scale = 10) {
        val window = ReplayWindow()
        val reference = ArrayDeque<String>()
        val pool = random.nextInt(10, 2_000)
        repeat(random.nextInt(1, 3_000)) {
            val id = "id-" + random.nextInt(pool)
            val expected = id !in reference
            assertEquals("replay of $id", expected, window.accept(id))
            if (expected) {
                reference.addLast(id)
                if (reference.size > 256) reference.removeFirst()
            }
            assertTrue(window.size <= 256)
        }
        // A replay of the newest IDs is always caught, however many came before.
        reference.takeLast(10).forEach { assertFalse(window.accept(it)) }
    }

    @Test fun payloadDecodersRejectGarbageWithoutThrowing() = property("payload decoders") {
        val payload = randomPayload()
        decodeFeatureState(payload)
        decodeBatteryStatus(payload)?.let { assertTrue(it.level in 0..100) }
        RichClipboardContent.decode(payload)?.let {
            assertTrue(it.text.isNotEmpty() && it.html.isNotBlank() && (it.text + it.html).toByteArray().size <= MAX_CLIPBOARD_CONTENT_BYTES)
        }
        assertEquals(FeatureStateDecoding.Invalid, decodeFeatureState(("{\"version\":1,\"features\":[" + "1,".repeat(1_000) + "1]}").toByteArray()))
    }

    private fun property(name: String, scale: Int = 1, case: () -> Unit) {
        val count = maxOf(1, iterations / scale)
        for (index in 0 until count) {
            try {
                case()
            } catch (error: Throwable) {
                throw AssertionError("fuzz property \"$name\" failed at case $index (BRIDGEY_FUZZ_SEED=$seed): $error", error)
            }
        }
    }

    /** Reads every frame; only the documented session-closing errors may escape. */
    private fun drain(input: InputStream) {
        while (true) {
            val line = try {
                input.readProtocolLine() ?: return
            } catch (_: ProtocolFrameTooLargeException) {
                return
            } catch (_: CharacterCodingException) {
                return
            }
            assertTrue(line.toByteArray().size <= MAX_PROTOCOL_FRAME_BYTES)
            try {
                assertWithinBounds(decodeProtocolMessage(line))
            } catch (_: JSONException) {
                // Closes the session in the app; here the next frame is read.
            }
        }
    }

    private fun decodeOrClose(frame: ByteArray): Message? = try {
        decodeProtocolMessage(ByteArrayInputStream(frame + '\n'.code.toByte()).readProtocolLine() ?: "")
    } catch (_: JSONException) {
        null
    } catch (_: ProtocolFrameTooLargeException) {
        null
    } catch (_: CharacterCodingException) {
        null
    } catch (error: Throwable) {
        fail("unexpected ${error.javaClass.simpleName} for ${frame.toString(Charsets.UTF_8).take(200)}")
        null
    }

    private fun assertWithinBounds(message: Message) {
        assertTrue(message.kind.isNotEmpty() && message.kind.toByteArray().size <= 64)
        assertTrue(message.sessionId.toByteArray().size <= 128)
        assertTrue(message.sessionId.isNotEmpty() || message.kind == "pairing.cancel")
        message.sequence?.let { assertTrue(kotlin.math.abs(it) <= JSON_MAX_SAFE_INTEGER) }
        listOfNotNull(message.messageId, message.transferId, message.nonce, message.deviceName).forEach { assertTrue(it.isNotEmpty()) }
    }

    private fun mutate(bytes: ByteArray): ByteArray {
        var value = bytes
        repeat(random.nextInt(1, 6)) {
            value = when (random.nextInt(6)) {
                0 -> value.copyOf().also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = random.nextInt(256).toByte() }
                1 -> if (value.isEmpty()) value else value.copyOfRange(0, random.nextInt(value.size))
                2 -> value + random.nextBytes(random.nextInt(1, 32))
                3 -> if (value.size < 2) value else random.nextInt(value.size - 1).let { i -> value.copyOfRange(0, i) + value.copyOfRange(i + 1, value.size) }
                4 -> value + value
                else -> value.toString(Charsets.UTF_8)
                    .replace("\"sessionId\"", if (random.nextBoolean()) "\"sessionid\"" else "\"sessionId\":5,\"x\"")
                    .toByteArray()
            }
        }
        return value
    }

    private fun randomKind(): String = when (random.nextInt(5)) {
        0 -> "features.update"
        1 -> "future.kind" + random.nextInt(100)
        2 -> "k." + "x".repeat(random.nextInt(60, 70))
        3 -> ""
        else -> listOf("pairing.cancel", "files.chunk", "quick.request", "notifications.post").random(random)
    }

    private fun randomString(): String = when (random.nextInt(6)) {
        0 -> ""
        1 -> "x".repeat(random.nextInt(0, 300))
        2 -> String(CharArray(random.nextInt(0, 40)) { random.nextInt(0x20, 0x2FFF).toChar() })
        3 -> "\u0000\u0007\n\"\\"
        4 -> "é".repeat(random.nextInt(0, 80))
        else -> java.util.UUID(random.nextLong(), random.nextLong()).toString()
    }

    private fun randomJson(depth: Int): Any = when (random.nextInt(if (depth > 6) 6 else 9)) {
        0 -> randomString()
        1 -> random.nextInt()
        2 -> random.nextLong()
        3 -> random.nextDouble() * 1e6
        4 -> random.nextBoolean()
        5 -> JSONObject.NULL
        6 -> JSONArray().apply { repeat(random.nextInt(0, 5)) { put(randomJson(depth + 1)) } }
        7 -> JSONObject().apply { repeat(random.nextInt(0, 5)) { put(randomString().take(12), randomJson(depth + 1)) } }
        else -> listOf(Long.MAX_VALUE, Long.MIN_VALUE, JSON_MAX_SAFE_INTEGER + 1, 0, -1, 1).random(random)
    }

    private fun randomPayload(): ByteArray {
        val samples = listOf("featuresUpdate", "batteryUpdate", "clipboardRich").flatMap { group ->
            vectors.getJSONArray(group).let { array ->
                (0 until array.length()).map { array.getJSONObject(it).let { v -> v.opt("plaintext")?.toString() ?: v.getString("plaintextString") } }
            }
        }
        return when (random.nextInt(4)) {
            0 -> random.nextBytes(random.nextInt(0, 4096))
            1 -> mutate(samples.random(random).toByteArray())
            2 -> JSONObject().apply {
                listOf("version", "features", "level", "isCharging", "text", "html").filter { random.nextBoolean() }.forEach { put(it, randomJson(0)) }
            }.toString().toByteArray()
            else -> samples.random(random).toByteArray()
        }
    }

    private companion object {
        const val ITERATIONS = 300
        const val DEFAULT_SEED = 20_261_028L
    }
}
