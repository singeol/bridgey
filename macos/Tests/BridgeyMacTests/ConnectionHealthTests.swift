import XCTest
@testable import BridgeyMac

final class ConnectionHealthTests: XCTestCase {
    func testRoutesAreClassesNeverAddresses() {
        for host in ["192.168.1.20", "10.0.0.5", "172.20.1.1", "169.254.3.4", "127.0.0.1", "fe80::1%en0", "fd12::4", "::1", "::ffff:192.168.0.2"] {
            XCTAssertEqual(classifyRoute(host), .local, host)
        }
        XCTAssertEqual(classifyRoute("100.101.102.103"), .shared)
        XCTAssertEqual(classifyRoute("8.8.8.8"), .other)
        XCTAssertEqual(classifyRoute("2001:db8::1"), .other)
        for host in [nil, "", "phone.local", "999.1.1.1", "1.2.3", "fe80::zz"] {
            XCTAssertEqual(classifyRoute(host), .unknown, host ?? "nil")
        }
    }

    func testRoundTripsComeOnlyFromOwnPings() {
        var health = ConnectionHealth()
        let start = Date(timeIntervalSince1970: 1_000)
        health.connected(host: "192.168.1.2", openedHere: true, at: start)
        health.heartbeatSent("a", at: start)
        XCTAssertFalse(health.heartbeatAnswered("unknown", at: start.addingTimeInterval(0.05)))
        XCTAssertTrue(health.heartbeatAnswered("a", at: start.addingTimeInterval(0.03)))
        XCTAssertFalse(health.heartbeatAnswered("a", at: start.addingTimeInterval(0.4)))
        for (id, rtt) in [("b", 0.05), ("c", 0.01)] {
            health.heartbeatSent(id, at: start)
            health.heartbeatAnswered(id, at: start.addingTimeInterval(rtt))
        }
        XCTAssertEqual(health.snapshot.roundTripSamples, 3)
        XCTAssertEqual(health.snapshot.lastRoundTrip ?? 0, 0.01, accuracy: 0.0001)
        XCTAssertEqual(health.snapshot.medianRoundTrip ?? 0, 0.03, accuracy: 0.0001)
        XCTAssertEqual(health.snapshot.route, .local)
    }

    func testDisconnectsKeepOnlyAReasonAndDiagnosticsHoldNoAddress() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "UTC"))
        var health = ConnectionHealth(calendar: calendar)
        let day = Date(timeIntervalSince1970: 86_400)
        health.disconnected(.user, at: day)
        XCTAssertNil(health.snapshot.lastDisconnect)
        health.attempt(at: day)
        health.attempt(at: day.addingTimeInterval(1))
        health.connected(host: "192.168.7.7", openedHere: false, at: day.addingTimeInterval(2))
        let connected = health.current(at: day.addingTimeInterval(3))
        let report = try BridgeyDiagnostics().report(connectionState: "connected", transfers: [], localFeatures: [:],
                                                     remoteFeatures: [:], connectionHealth: connected)
        let text = String(decoding: report, as: UTF8.self)
        XCTAssertTrue(text.contains("\"route\" : \"local_network\""))
        XCTAssertFalse(text.contains("192.168"))
        health.disconnected(.heartbeat, at: day.addingTimeInterval(4))
        XCTAssertFalse(health.snapshot.connected)
        XCTAssertNil(health.snapshot.openedHere)
        XCTAssertEqual(health.snapshot.lastDisconnect, .heartbeat)
        XCTAssertEqual(health.current(at: day.addingTimeInterval(5)).attemptsToday, 2)
        XCTAssertEqual(health.current(at: day.addingTimeInterval(86_400)).attemptsToday, 0)
    }
}
