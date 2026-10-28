package dev.bridgey.android

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * protocol/messages.json lists every message kind of Bridgey Protocol v1 (docs/protocol.md, Version 1
 * compatibility contract). These tests fail when the Android sources send or dispatch a kind that is not
 * listed, when a listed kind is not used here, or when the file is inconsistent. The macOS suite runs the
 * same source scan in ProtocolContractTests.swift.
 */
class ProtocolContractTest {
    private val root = File(System.getProperty("bridgey.repoRoot") ?: "../..")
    private val contract = JSONObject(File(root, "protocol/messages.json").readText(Charsets.UTF_8))
    private val messages = contract.getJSONArray("messages").objects()
    private val kinds = messages.map { it.getString("kind") }

    @Test fun everyKindTheSourcesUseIsListed() {
        val found = scanKinds(listOf(File(root, "android/app/src/main"), File(root, "android/core")))
        assertTrue("the scan found almost nothing; did the sources move?", found.size >= 30)
        val problems = found.filterKeys { it !in kinds }.map { (kind, file) -> "$kind (in $file) is not in protocol/messages.json" } +
            kinds.filter { it !in found }.map { "$it is listed in protocol/messages.json but the Android sources never use it" }
        assertEquals("Describe new messages in docs/protocol.md and protocol/messages.json", emptyList<String>(), problems)
    }

    @Test fun featureKeysMatchTheSettingsModel() {
        val features = contract.getJSONArray("features").objects()
        assertEquals(BridgeyFeature.entries.map(BridgeyFeature::key), features.map { it.getString("key") })
        features.forEach { entry ->
            val feature = BridgeyFeature.entries.first { it.key == entry.getString("key") }
            assertEquals("requiredInMap for ${feature.key}", featureEnabledByLegacyPeer(feature), entry.getBoolean("requiredInMap"))
        }
    }

    @Test fun transportLimitsMatchTheCode() {
        val transport = contract.getJSONObject("transport")
        assertEquals(MAX_PROTOCOL_FRAME_BYTES, transport.getInt("maxFrameBytes"))
        assertEquals(MAX_PROTOCOL_JSON_DEPTH, transport.getInt("maxJsonDepth"))
    }

    @Test fun contractIsConsistent() {
        val directions = contract.getJSONObject("directions").keys().asSequence().toSet()
        val features = contract.getJSONArray("features").objects().map { it.getString("key") }.toSet()
        val envelope = contract.getJSONObject("envelope").keys().asSequence().toSet()
        val problems = mutableListOf<String>()
        if (kinds.size != kinds.toSet().size) problems += "a kind is listed twice"
        messages.forEach { message ->
            val kind = message.getString("kind")
            val outer = message.getJSONArray("envelope").strings()
            val encrypted = message.getBoolean("encrypted")
            if (!kindPattern.matches(kind) || kind.toByteArray(Charsets.UTF_8).size > 64) problems += "$kind: invalid kind"
            if (message.getString("direction") !in directions) problems += "$kind: unknown direction"
            message.getJSONArray("features").strings().filterNot(features::contains).forEach { problems += "$kind: unknown feature $it" }
            outer.filterNot(envelope::contains).forEach { problems += "$kind: unknown envelope field $it" }
            if ("sessionId" !in outer) problems += "$kind: every message carries sessionId"
            if (encrypted != ("nonce" in outer && "ciphertext" in outer)) problems += "$kind: nonce and ciphertext must match encrypted"
            if (encrypted != message.has("payload")) problems += "$kind: encrypted messages, and only those, describe a payload"
            if (message.has("replay") && "messageId" !in outer) problems += "$kind: replay protection needs messageId"
            message.optJSONArray("replies")?.strings()?.filterNot(kinds::contains)?.forEach { problems += "$kind: reply $it is not listed" }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test fun everyKindIsDescribedInTheProtocolDocument() {
        val document = File(root, "docs/protocol.md").readText(Charsets.UTF_8)
        assertEquals(emptyList<String>(), kinds.filter { "`$it`" !in document })
    }

    @Test fun scanFindsEveryStyleTheSourcesUse() {
        val directory = kotlin.io.path.createTempDirectory("contract").toFile()
        try {
            File(directory, "A.kt").writeText(
                """
                current.send(Message(kind = "a.one", sessionId = id))
                            "a.two", "a.three" -> receive()
                    FOUR("a.four"),
                scope.launch { sendFindCommand("a.five") }
                if (message.kind == "a.six") return
                send("a.seven", JSONObject())
                private val preferences = context.getSharedPreferences("bridgey.settings", 0)
                private const val KEY = "calls.direct_enabled"
                """.trimIndent(),
            )
            val expected = listOf("one", "two", "three", "four", "five", "six", "seven").map { "a.$it" }.toSet()
            assertEquals(expected, scanKinds(listOf(directory)).keys)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)

    private companion object {
        const val K = """[a-z][a-z0-9]*(?:\.[a-z0-9_]+)+"""
        val kindPattern = Regex(K)
        val patterns = listOf(
            Regex("""kind\s*=\s*"($K)""""),
            Regex("""kind\s*==\s*"($K)""""),
            Regex("""\bsend\w*\((?:[^()"]*,\s*)?"($K)""""),
            Regex("""^\s*[A-Z_]+\("($K)"\)"""),
        )
        val branch = Regex("""^\s*((?:"$K"\s*,\s*)*"$K")\s*->""")
        val literal = Regex(""""($K)"""")

        /** Kinds the Kotlin sources under [roots] send or dispatch, with the first file that uses each. */
        fun scanKinds(roots: List<File>): Map<String, String> {
            val found = sortedMapOf<String, String>()
            roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }.sortedBy(File::getPath).forEach { file ->
                file.readLines(Charsets.UTF_8).forEach { line ->
                    patterns.forEach { pattern -> pattern.findAll(line).forEach { found.putIfAbsent(it.groupValues[1], file.name) } }
                    branch.find(line)?.let { match -> literal.findAll(match.groupValues[1]).forEach { found.putIfAbsent(it.groupValues[1], file.name) } }
                }
            }
            return found
        }
    }
}
