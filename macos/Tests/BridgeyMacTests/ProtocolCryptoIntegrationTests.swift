import CryptoKit
import Foundation
import XCTest
@testable import BridgeyMac

final class ProtocolCryptoIntegrationTests: XCTestCase {
    private lazy var vector: [String: String] = loadVector()

    func testMacMatchesSharedPairingVectorInBothDirections() throws {
        let a = try P256.KeyAgreement.PrivateKey(rawRepresentation: data("privateKeyA"))
        let b = try P256.KeyAgreement.PrivateKey(rawRepresentation: data("privateKeyB"))
        let fromA = try pairingMaterial(
            privateKey: a,
            remoteKey: required("publicKeyB"),
            sessionID: required("sessionId")
        )
        let fromB = try pairingMaterial(
            privateKey: b,
            remoteKey: required("publicKeyA"),
            sessionID: required("sessionId")
        )

        XCTAssertEqual(fromA.code, required("expectedCode"))
        XCTAssertEqual(fromA.code, fromB.code)
        XCTAssertEqual(fromA.key, data("expectedKey"))
        XCTAssertEqual(fromA.key, fromB.key)
    }

    func testMacMatchesSharedProofAndAesGCMVector() throws {
        let key = data("expectedKey")
        let proof = confirmationProof(
            key: key,
            sessionID: required("sessionId"),
            deviceID: required("deviceId"),
            identityKey: required("identityKey")
        )
        XCTAssertEqual(proof, required("expectedConfirmationProof"))
        XCTAssertTrue(verifyConfirmationProof(
            proof,
            key: key,
            sessionID: required("sessionId"),
            deviceID: required("deviceId"),
            identityKey: required("identityKey")
        ))
        XCTAssertFalse(verifyConfirmationProof(
            proof,
            key: key,
            sessionID: required("sessionId") + "-tampered",
            deviceID: required("deviceId"),
            identityKey: required("identityKey")
        ))

        let plaintext = try decrypt(
            nonce: required("aesNonce"),
            ciphertext: required("aesCiphertext"),
            key: key
        )
        XCTAssertEqual(String(data: plaintext, encoding: .utf8), required("aesPlaintext"))
        var tampered = data("aesCiphertext")
        tampered[tampered.index(before: tampered.endIndex)] ^= 1
        XCTAssertThrowsError(try decrypt(
            nonce: required("aesNonce"),
            ciphertext: tampered.base64EncodedString(),
            key: key
        ))
    }

    private func loadVector() -> [String: String] {
        let url = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .appendingPathComponent("../../../protocol/test-vectors/crypto-v1.properties")
            .standardizedFileURL
        let content = try! String(contentsOf: url, encoding: .utf8)
        return Dictionary(uniqueKeysWithValues: content.split(whereSeparator: { $0.isNewline }).map { line in
            let pair = line.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            return (String(pair[0]), String(pair[1]))
        })
    }

    private func required(_ key: String) -> String {
        guard let value = vector[key] else { XCTFail("Missing test vector property: \(key)"); return "" }
        return value
    }

    private func data(_ key: String) -> Data {
        guard let value = Data(base64Encoded: required(key)) else {
            XCTFail("Invalid base64 test vector property: \(key)")
            return Data()
        }
        return value
    }
}
