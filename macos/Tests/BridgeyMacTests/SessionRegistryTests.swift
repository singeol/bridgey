import XCTest
@testable import BridgeyMac

final class SessionRegistryTests: XCTestCase {
    private final class FakeSession {
        let name: String
        init(_ name: String) { self.name = name }
    }

    // The Mac's ID sorts between the two phones: "a-phone" dials the Mac, the Mac dials "z-phone".
    private let macID = "m-mac"
    private let phoneA = "a-phone"
    private let phoneZ = "z-phone"

    private func connect(
        _ registry: SessionRegistry<FakeSession>,
        _ session: FakeSession,
        _ id: String,
        local: Bool
    ) -> SessionRegistry<FakeSession>.Admission {
        registry.addPending(session, initiatedLocally: local)
        return registry.authenticate(session, deviceID: id, peerName: session.name)
    }

    private func isAccepted(_ admission: SessionRegistry<FakeSession>.Admission, replacing expected: FakeSession? = nil) -> Bool {
        guard case let .accepted(replaced) = admission else { return false }
        return replaced === expected
    }

    func testSeveralDevicesSideBySide() {
        let registry = SessionRegistry<FakeSession>(localDeviceID: macID)
        let a = FakeSession("Pixel")
        let z = FakeSession("Galaxy")
        XCTAssertTrue(isAccepted(connect(registry, a, phoneA, local: false)))
        XCTAssertTrue(isAccepted(connect(registry, z, phoneZ, local: true)))
        XCTAssertEqual(registry.connectedIDs, [phoneA, phoneZ])
        XCTAssertTrue(registry.session(for: phoneA) === a)
        XCTAssertEqual(registry.deviceID(of: z), phoneZ)
        XCTAssertEqual(registry.connections.map(\.name), ["Pixel", "Galaxy"])
    }

    func testPendingSessionsStayApartFromConnectedDevices() {
        let registry = SessionRegistry<FakeSession>(localDeviceID: macID)
        let pairing = FakeSession("pairing")
        registry.addPending(pairing, initiatedLocally: false)
        XCTAssertTrue(registry.isPending(pairing))
        XCTAssertFalse(registry.isActive(pairing))
        XCTAssertTrue(registry.connectedIDs.isEmpty)
        XCTAssertNil(registry.target(selected: nil))
        XCTAssertNil(registry.remove(pairing))
        XCTAssertFalse(registry.contains(pairing))
    }

    func testPairingSurvivesTrustedDeviceReconnecting() {
        let registry = SessionRegistry<FakeSession>(localDeviceID: macID)
        let stale = FakeSession("old")
        _ = connect(registry, stale, phoneA, local: false)
        let pairing = FakeSession("new phone")
        registry.addPending(pairing, initiatedLocally: false)
        let reconnect = FakeSession("reconnect")
        XCTAssertTrue(isAccepted(connect(registry, reconnect, phoneA, local: false), replacing: stale))
        XCTAssertTrue(registry.isPending(pairing))
        XCTAssertFalse(registry.contains(stale))
        XCTAssertTrue(registry.session(for: phoneA) === reconnect)
        XCTAssertTrue(isAccepted(registry.authenticate(pairing, deviceID: phoneZ, peerName: "Galaxy")))
        XCTAssertEqual(registry.connectedIDs, [phoneA, phoneZ])
        XCTAssertTrue(registry.pendingSessions.isEmpty)
    }

    func testDuplicateKeepsSessionDialledByLowerID() {
        // m-mac < z-phone: the Mac's own dial wins in either order.
        let registry = SessionRegistry<FakeSession>(localDeviceID: macID)
        let macDialled = FakeSession("mac dialled")
        let phoneDialled = FakeSession("phone dialled")
        _ = connect(registry, macDialled, phoneZ, local: true)
        if case .accepted = connect(registry, phoneDialled, phoneZ, local: false) { XCTFail("duplicate accepted") }
        XCTAssertTrue(registry.session(for: phoneZ) === macDialled)
        XCTAssertTrue(registry.isPending(phoneDialled), "the caller closes and removes the rejected session")

        let reversed = SessionRegistry<FakeSession>(localDeviceID: macID)
        let first = FakeSession("phone dialled")
        let second = FakeSession("mac dialled")
        _ = connect(reversed, first, phoneZ, local: false)
        XCTAssertTrue(isAccepted(connect(reversed, second, phoneZ, local: true), replacing: first))
        XCTAssertTrue(reversed.session(for: phoneZ) === second)
    }

    func testDuplicateFromSameSideKeepsNewerSession() {
        let registry = SessionRegistry<FakeSession>(localDeviceID: macID)
        let older = FakeSession("older")
        let newer = FakeSession("newer")
        _ = connect(registry, older, phoneA, local: false)
        XCTAssertTrue(isAccepted(connect(registry, newer, phoneA, local: false), replacing: older))
        XCTAssertTrue(registry.session(for: phoneA) === newer)
    }

    func testBothEndsKeepTheSameSocket() {
        // Socket A was dialled by the phone, socket B by the Mac. Each end sees the pair from its side.
        final class Tag { let value: String; init(_ value: String) { self.value = value } }
        let macSide = SessionRegistry<Tag>(localDeviceID: "z-mac")
        let a1 = Tag("A"), b1 = Tag("B")
        macSide.addPending(a1, initiatedLocally: false)
        _ = macSide.authenticate(a1, deviceID: "m-phone", peerName: "Phone")
        macSide.addPending(b1, initiatedLocally: true)
        _ = macSide.authenticate(b1, deviceID: "m-phone", peerName: "Phone")

        let phoneSide = SessionRegistry<Tag>(localDeviceID: "m-phone")
        let b2 = Tag("B"), a2 = Tag("A")
        phoneSide.addPending(b2, initiatedLocally: false)
        _ = phoneSide.authenticate(b2, deviceID: "z-mac", peerName: "Mac")
        phoneSide.addPending(a2, initiatedLocally: true)
        _ = phoneSide.authenticate(a2, deviceID: "z-mac", peerName: "Mac")

        XCTAssertEqual(macSide.session(for: "m-phone")?.value, "A")
        XCTAssertEqual(phoneSide.session(for: "z-mac")?.value, "A")
    }

    func testReplacementKeepsTheDeviceState() {
        let registry = SessionRegistry<FakeSession>(localDeviceID: macID)
        _ = connect(registry, FakeSession("old"), phoneA, local: false)
        registry.update(phoneA) { $0.battery = RemoteBatteryStatus(level: 40, isCharging: false) }
        _ = connect(registry, FakeSession("new"), phoneA, local: false)
        XCTAssertEqual(registry.link(for: phoneA)?.battery, RemoteBatteryStatus(level: 40, isCharging: false))
    }

    func testDeviceStateIsIsolated() {
        let registry = SessionRegistry<FakeSession>(localDeviceID: macID)
        _ = connect(registry, FakeSession("a"), phoneA, local: false)
        _ = connect(registry, FakeSession("z"), phoneZ, local: true)
        registry.update(phoneA) {
            $0.battery = RemoteBatteryStatus(level: 80, isCharging: true)
            $0.ringing = true
            $0.remoteFeatures[.clipboard] = false
            $0.featureStateReceived = true
        }
        XCTAssertEqual(registry.link(for: phoneA)?.battery, RemoteBatteryStatus(level: 80, isCharging: true))
        XCTAssertNil(registry.link(for: phoneZ)?.battery)
        XCTAssertEqual(registry.link(for: phoneZ)?.ringing, false)
        XCTAssertEqual(registry.link(for: phoneZ)?.featureStateReceived, false)
        XCTAssertEqual(registry.link(for: phoneZ)?.remoteFeatures, defaultRemoteFeatureState())
        XCTAssertFalse(registry.update("unknown") { _ in })
    }

    func testBroadcastTargetsOnlyConnectedDevicesThatQualify() {
        let registry = SessionRegistry<FakeSession>(localDeviceID: macID)
        let a = FakeSession("a")
        _ = connect(registry, a, phoneA, local: false)
        _ = connect(registry, FakeSession("z"), phoneZ, local: true)
        registry.update(phoneZ) { $0.remoteFeatures[.battery] = false }
        registry.addPending(FakeSession("pairing"), initiatedLocally: false)
        let targets = registry.broadcastTargets { _, link in link.remoteFeatures[.battery] != false }
        XCTAssertEqual(targets.map(\.deviceID), [phoneA])
        XCTAssertTrue(targets.first?.session === a)
        XCTAssertEqual(registry.broadcastTargets { _, _ in true }.count, 2)
    }

    func testTargetIsSelectionWhileConnectedElseLatest() {
        let registry = SessionRegistry<FakeSession>(localDeviceID: macID)
        XCTAssertNil(registry.target(selected: nil))
        _ = connect(registry, FakeSession("a"), phoneA, local: false)
        _ = connect(registry, FakeSession("z"), phoneZ, local: true)
        XCTAssertEqual(registry.target(selected: nil), phoneZ)
        XCTAssertEqual(registry.target(selected: phoneA), phoneA)
        XCTAssertEqual(registry.target(selected: "gone"), phoneZ)
        XCTAssertEqual(registry.remove(registry.session(for: phoneZ)!), phoneZ)
        XCTAssertEqual(registry.target(selected: nil), phoneA)
    }

    func testRemoveAllReturnsEverySession() {
        let registry = SessionRegistry<FakeSession>(localDeviceID: macID)
        _ = connect(registry, FakeSession("a"), phoneA, local: false)
        registry.addPending(FakeSession("pending"), initiatedLocally: true)
        XCTAssertEqual(registry.removeAll().map(\.name), ["pending", "a"])
        XCTAssertTrue(registry.connectedIDs.isEmpty)
        XCTAssertTrue(registry.pendingSessions.isEmpty)
    }

    func testAuthenticateUnknownSessionIsRejected() {
        let registry = SessionRegistry<FakeSession>(localDeviceID: macID)
        if case .accepted = registry.authenticate(FakeSession("x"), deviceID: phoneA, peerName: "x") {
            XCTFail("a session that was never added must not authenticate")
        }
    }
}
