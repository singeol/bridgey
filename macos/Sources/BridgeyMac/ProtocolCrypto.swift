import CryptoKit
import Foundation

enum ProtocolCryptoError: Error {
    case invalidInput
}

func pairingMaterial(
    privateKey: P256.KeyAgreement.PrivateKey,
    remoteKey: String,
    sessionID: String
) throws -> (code: String, key: Data) {
    guard let data = Data(base64Encoded: remoteKey) else { throw ProtocolCryptoError.invalidInput }
    let publicKey = try P256.KeyAgreement.PublicKey(x963Representation: data)
    let secret = try privateKey.sharedSecretFromKeyAgreement(with: publicKey)
    let salt = Data(SHA256.hash(data: Data(sessionID.utf8)))
    let key = secret.hkdfDerivedSymmetricKey(
        using: SHA256.self,
        salt: salt,
        sharedInfo: Data("bridgey-pairing-v1".utf8),
        outputByteCount: 32
    )
    let bytes = key.withUnsafeBytes { Array($0.prefix(4)) }
    let value = (UInt32(bytes[0]) << 24) | (UInt32(bytes[1]) << 16) |
        (UInt32(bytes[2]) << 8) | UInt32(bytes[3])
    return (String(format: "%06d", value % 1_000_000), key.withUnsafeBytes { Data($0) })
}

func confirmationProof(key: Data, sessionID: String, deviceID: String, identityKey: String) -> String {
    let data = Data("bridgey-confirm-v1\0\(sessionID)\0\(deviceID)\0\(identityKey)".utf8)
    let code = HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key))
    return Data(code).base64EncodedString()
}

func verifyConfirmationProof(
    _ proof: String,
    key: Data,
    sessionID: String,
    deviceID: String,
    identityKey: String
) -> Bool {
    guard let received = Data(base64Encoded: proof) else { return false }
    let data = Data("bridgey-confirm-v1\0\(sessionID)\0\(deviceID)\0\(identityKey)".utf8)
    return HMAC<SHA256>.isValidAuthenticationCode(
        received,
        authenticating: data,
        using: SymmetricKey(data: key)
    )
}

func encrypt(_ plaintext: Data, key: Data) throws -> (nonce: String, ciphertext: String) {
    let sealed = try AES.GCM.seal(plaintext, using: SymmetricKey(data: key))
    return (
        Data(sealed.nonce).base64EncodedString(),
        (sealed.ciphertext + sealed.tag).base64EncodedString()
    )
}

func decrypt(nonce: String, ciphertext: String, key: Data) throws -> Data {
    guard let nonceData = Data(base64Encoded: nonce),
          let combined = Data(base64Encoded: ciphertext),
          combined.count >= 16 else { throw ProtocolCryptoError.invalidInput }
    let sealed = try AES.GCM.SealedBox(
        nonce: AES.GCM.Nonce(data: nonceData),
        ciphertext: combined.dropLast(16),
        tag: combined.suffix(16)
    )
    return try AES.GCM.open(sealed, using: SymmetricKey(data: key))
}
