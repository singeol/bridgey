import XCTest
@testable import BridgeyMac

final class RemoteControlAuditTests: XCTestCase {
    private var directory: URL!
    private let day: TimeInterval = 24 * 60 * 60
    private let now = Date(timeIntervalSince1970: 1_800_000_000)

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("remote-audit-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private var fileURL: URL { directory.appendingPathComponent("support/remote-control-audit.json") }

    private func entry(_ id: String, endedAgo: TimeInterval, accepted: Bool = true) -> RemoteControlAuditEntry {
        RemoteControlAuditEntry(id: id, deviceID: "phone", deviceName: "OnePlus 11", mode: "touchpad", accepted: accepted,
                                startedAt: now.addingTimeInterval(-endedAgo - 60), endedAt: now.addingTimeInterval(-endedAgo),
                                endReason: "user", counts: ["pointer": 10], dropped: 0, rejected: 0)
    }

    func testKeepsThirtyDaysNewestFirstAndAtMostFiveHundred() {
        let entries = [entry("old", endedAgo: 31 * day), entry("recent", endedAgo: 60), entry("edge", endedAgo: 30 * day - 1), entry("week", endedAgo: 7 * day)]
        XCTAssertEqual(RemoteControlAuditStore.pruned(entries, now: now).map(\.id), ["recent", "week", "edge"])
        let many = (0..<600).map { entry("e\($0)", endedAgo: TimeInterval($0)) }
        let kept = RemoteControlAuditStore.pruned(many, now: now)
        XCTAssertEqual(kept.count, 500)
        XCTAssertEqual(kept.first?.id, "e0")
    }

    func testFileIsOwnerOnlyAndExpiredEntriesGoOnLoad() throws {
        let store = RemoteControlAuditStore(fileURL: fileURL)
        store.save([entry("recent", endedAgo: 60), entry("old", endedAgo: 40 * day)])
        let attributes = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        XCTAssertEqual((attributes[.posixPermissions] as? NSNumber)?.intValue, 0o600)
        let folder = try FileManager.default.attributesOfItem(atPath: fileURL.deletingLastPathComponent().path)
        XCTAssertEqual((folder[.posixPermissions] as? NSNumber)?.intValue, 0o700)
        XCTAssertEqual(store.load(now: now).map(\.id), ["recent"])
        XCTAssertEqual(RemoteControlAuditStore(fileURL: fileURL).load(now: now).map(\.id), ["recent"], "the pruned list was written back")
    }

    @MainActor func testClearDeletesTheFileAndPruneDropsExpiredEntries() {
        var clock = now
        let log = RemoteControlAuditLog(store: RemoteControlAuditStore(fileURL: fileURL), clock: { clock })
        log.record(entry("a", endedAgo: 0))
        XCTAssertTrue(FileManager.default.fileExists(atPath: fileURL.path))
        clock = now.addingTimeInterval(31 * day)
        log.prune()
        XCTAssertTrue(log.entries.isEmpty)
        log.record(entry("b", endedAgo: -31 * day))
        log.clear()
        XCTAssertTrue(log.entries.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: fileURL.path))
    }

    /// A real session through the host: what was typed, which keys and where the pointer went never
    /// reach the file; only the device, times, the reason and counts do.
    @MainActor func testSessionLogStoresCountsButNeverTextKeysOrPositions() throws {
        final class NoInjection: RemoteInputInjecting {
            func move(dx: Int, dy: Int, held: Set<PointerButton>) {}
            func button(_ button: PointerButton, down: Bool) {}
            func scroll(dx: Int, dy: Int, unit: ScrollUnit) {}
            func type(_ text: String) {}
            func press(_ strokes: [KeyStroke]) {}
        }
        final class NoIndicator: RemoteControlIndicating {
            func show(deviceName: String, mode: RemoteInputMode, onStop: @escaping () -> Void) {}
            func hide() {}
            func showLaser(x: Int, y: Int, anchor: Bool) {}
            func hideLaser() {}
        }
        let clock = now
        let audit = RemoteControlAuditLog(store: RemoteControlAuditStore(fileURL: fileURL), clock: { clock })
        let host = RemoteInputHost(injector: NoInjection(), indicator: NoIndicator(), audit: audit, accessibility: { true },
                                   clock: { clock }, schedulesTimer: false)
        host.isAllowed = { _ in true }
        let id = "0b9a54a2-6a4c-4b9e-8f45-3c2a1d0e9f11"
        var sequence = 0
        func send(_ kind: String, _ fields: [String: Any]) {
            sequence += 1
            var object = fields
            object["version"] = 1
            object["inputSessionId"] = id
            object["sequence"] = sequence
            host.receive(kind: kind, payload: try! JSONSerialization.data(withJSONObject: object), deviceID: "phone", deviceName: "OnePlus 11")
        }
        send("input.session.start", ["mode": "touchpad"])
        send("input.text", ["text": "hunter2-secret-passphrase"])
        send("input.key", ["key": "backspace"])
        send("input.pointer", ["dx": 417, "dy": -293])
        send("input.present", ["action": "laser_on", "x": 7321, "y": 1234])
        send("input.session.stop", ["reason": "user"])

        let raw = try String(contentsOf: fileURL, encoding: .utf8)
        for secret in ["hunter2", "secret", "passphrase", "backspace", "417", "293", "7321", "1234"] {
            XCTAssertFalse(raw.contains(secret), secret)
        }
        XCTAssertTrue(raw.contains("OnePlus 11"))
        XCTAssertEqual(audit.entries.first?.counts, ["text": 1, "key": 1, "pointer": 1, "present": 1])
        XCTAssertEqual(audit.entries.first?.endReason, "user")
    }
}
