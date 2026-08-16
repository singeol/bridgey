package dev.bridgey.android

import java.io.File
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.Properties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCryptoIntegrationTest {
    private val vector = Properties().apply {
        File(System.getProperty("bridgey.repoRoot"), "protocol/test-vectors/crypto-v1.properties")
            .reader(Charsets.UTF_8)
            .use { load(it) }
    }

    @Test
    fun androidMatchesSharedPairingVectorInBothDirections() {
        val a = keyPair(vector.required("privateKeyA"), vector.required("publicKeyA"))
        val b = keyPair(vector.required("privateKeyB"), vector.required("publicKeyB"))
        val fromA = Crypto.pairingMaterial(a, vector.required("publicKeyB"), vector.required("sessionId"))
        val fromB = Crypto.pairingMaterial(b, vector.required("publicKeyA"), vector.required("sessionId"))

        assertEquals(vector.required("expectedCode"), fromA.code)
        assertEquals(fromA.code, fromB.code)
        assertArrayEquals(Base64.getDecoder().decode(vector.required("expectedKey")), fromA.key)
        assertArrayEquals(fromA.key, fromB.key)
    }

    @Test
    fun androidMatchesSharedProofAndAesGcmVector() {
        val key = Base64.getDecoder().decode(vector.required("expectedKey"))
        val proof = Crypto.confirmationProof(
            key,
            vector.required("sessionId"),
            vector.required("deviceId"),
            vector.required("identityKey"),
        )
        assertEquals(vector.required("expectedConfirmationProof"), proof)
        assertTrue(Crypto.verifyConfirmationProof(
            key,
            vector.required("sessionId"),
            vector.required("deviceId"),
            vector.required("identityKey"),
            proof,
        ))
        assertFalse(Crypto.verifyConfirmationProof(
            key,
            vector.required("sessionId") + "-tampered",
            vector.required("deviceId"),
            vector.required("identityKey"),
            proof,
        ))

        val plaintext = Crypto.decrypt(
            key,
            vector.required("aesNonce"),
            vector.required("aesCiphertext"),
        )
        assertEquals(vector.required("aesPlaintext"), plaintext?.toString(Charsets.UTF_8))
        val tampered = Base64.getDecoder().decode(vector.required("aesCiphertext")).also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertNull(Crypto.decrypt(key, vector.required("aesNonce"), Base64.getEncoder().encodeToString(tampered)))
    }

    private fun keyPair(privateKey: String, publicKey: String): KeyPair {
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val factory = KeyFactory.getInstance("EC")
        val privateValue = BigInteger(1, Base64.getDecoder().decode(privateKey))
        val rawPublic = Base64.getDecoder().decode(publicKey)
        require(rawPublic.size == 65 && rawPublic[0] == 4.toByte())
        val point = ECPoint(
            BigInteger(1, rawPublic.copyOfRange(1, 33)),
            BigInteger(1, rawPublic.copyOfRange(33, 65)),
        )
        return KeyPair(
            factory.generatePublic(ECPublicKeySpec(point, parameters)),
            factory.generatePrivate(ECPrivateKeySpec(privateValue, parameters)),
        )
    }

    private fun Properties.required(key: String): String =
        getProperty(key) ?: error("Missing test vector property: $key")
}
