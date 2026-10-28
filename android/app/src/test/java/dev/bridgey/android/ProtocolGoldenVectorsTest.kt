package dev.bridgey.android

import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * protocol/test-vectors/protocol-v1.json: the macOS suite (ProtocolGoldenVectorsTests.swift) decodes the
 * same vectors and must reach the same results.
 */
class ProtocolGoldenVectorsTest {
    private val vectors = JSONObject(
        File(File(System.getProperty("bridgey.repoRoot") ?: "../.."), "protocol/test-vectors/protocol-v1.json").readText(Charsets.UTF_8),
    )
    private val sessionId = vectors.getString("sessionId")

    @Test fun frames() = each("frames") { vector ->
        val padding = if (vector.has("padTo")) "a".repeat(vector.getInt("padTo") - padPrefix().length - 2) else ""
        val bytes = when {
            vector.has("lineBase64") -> Base64.getDecoder().decode(vector.getString("lineBase64"))
            vector.has("nest") -> nested(vector.getInt("nest")).toByteArray()
            vector.has("padTo") -> (padPrefix() + padding + "\"}").toByteArray()
            else -> vector.getString("line").toByteArray()
        } + vector.optString("lineEnding", "\n").toByteArray()
        val decoded = runCatching {
            val line = ByteArrayInputStream(bytes).readProtocolLine() ?: error("no frame")
            decodeProtocolMessage(line).toMap()
        }.getOrNull()
        val expect = vector.get("expect")
        if (expect == "reject") return@each decoded == null
        val expected = normalize(expect) as Map<*, *>
        decoded == expected.mapValues { (_, value) -> if (value == "@PAD@") padding else value }
    }

    @Test fun featuresUpdate() = each("featuresUpdate") { vector ->
        val result = when (val state = decodeFeatureState(plaintext(vector))) {
            FeatureStateDecoding.Invalid -> "invalid"
            FeatureStateDecoding.Ignored -> "ignored"
            is FeatureStateDecoding.State -> mapOf("features" to state.features.mapKeys { it.key.key })
        }
        result == normalize(vector.get("expect"))
    }

    @Test fun batteryUpdate() = each("batteryUpdate") { vector ->
        val result: Any = decodeBatteryStatus(plaintext(vector))
            ?.let { mapOf("level" to it.level.toLong(), "isCharging" to it.isCharging) } ?: "reject"
        result == normalize(vector.get("expect"))
    }

    @Test fun clipboardRich() = each("clipboardRich") { vector ->
        val result: Any = RichClipboardContent.decode(plaintext(vector))?.let { mapOf("text" to it.text, "html" to it.html) } ?: "reject"
        result == normalize(vector.get("expect"))
    }

    @Test fun richClipboardIsBoundedTogetherAtThirtyTwoKiB() {
        fun payload(textBytes: Int) = JSONObject().put("version", 1).put("text", "t".repeat(textBytes))
            .put("html", "<b>" + "h".repeat(16 * 1024 - 7) + "</b>").toString().toByteArray()
        assertEquals(MAX_CLIPBOARD_CONTENT_BYTES, RichClipboardContent.decode(payload(16 * 1024))?.let { (it.text + it.html).toByteArray().size })
        assertNull(RichClipboardContent.decode(payload(16 * 1024 + 1)))
    }

    private fun each(group: String, check: (JSONObject) -> Boolean) {
        val array = vectors.getJSONArray(group)
        assertTrue(array.length() > 5)
        val failed = (0 until array.length()).map(array::getJSONObject)
            .filterNot { runCatching { check(it) }.getOrDefault(false) }
            .map { it.getString("name") }
        assertEquals("$group vectors that decode differently from the contract", emptyList<String>(), failed)
    }

    private fun plaintext(vector: JSONObject): ByteArray =
        (if (vector.has("plaintextString")) vector.getString("plaintextString") else vector.get("plaintext").toString()).toByteArray()

    private fun padPrefix() = "{\"kind\":\"pairing.offer\",\"sessionId\":\"$sessionId\",\"deviceName\":\""

    private fun nested(depth: Int) = "{\"kind\":\"x.y\",\"sessionId\":\"$sessionId\",\"x\":" + "[".repeat(depth - 1) + "]".repeat(depth - 1) + "}"

    private fun Message.toMap(): Map<String, Any> = listOfNotNull(
        "kind" to kind, "sessionId" to sessionId, deviceId?.let { "deviceId" to it }, deviceName?.let { "deviceName" to it },
        publicKey?.let { "publicKey" to it }, identityKey?.let { "identityKey" to it }, proof?.let { "proof" to it },
        signature?.let { "signature" to it }, messageId?.let { "messageId" to it }, nonce?.let { "nonce" to it },
        ciphertext?.let { "ciphertext" to it }, transferId?.let { "transferId" to it }, sequence?.let { "sequence" to it },
    ).toMap()

    companion object {
        /** JSON as plain Kotlin values: maps, lists, Long for integers, null for JSON null. */
        fun normalize(value: Any?): Any? = when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { normalize(value.opt(it)) }
            is JSONArray -> (0 until value.length()).map { normalize(value.opt(it)) }
            JSONObject.NULL -> null
            is Number -> jsonInteger(value) ?: value.toDouble()
            else -> value
        }
    }
}
