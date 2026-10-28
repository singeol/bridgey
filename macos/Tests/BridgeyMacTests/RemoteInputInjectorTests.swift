import XCTest
@testable import BridgeyMac

/// Pure parts of injection. No CGEvent is created or posted here.
final class RemoteInputInjectorTests: XCTestCase {
    /// A 1512 x 982 laptop as the main display, a 2560 x 1440 monitor to its right whose top is
    /// 300 points higher, and a 1080 x 1920 portrait display below the laptop: gaps and different sizes.
    private let laptop = CGRect(x: 0, y: 0, width: 1512, height: 982)
    private let monitor = CGRect(x: 1512, y: -300, width: 2560, height: 1440)
    private let portrait = CGRect(x: 216, y: 982, width: 1080, height: 1920)
    private var displays: [CGRect] { [laptop, monitor, portrait] }

    // MARK: Coordinate clamping

    func testPointsOnADisplayAreUnchanged() {
        for point in [CGPoint(x: 10, y: 10), CGPoint(x: 1511, y: 981), CGPoint(x: 3000, y: -250), CGPoint(x: 700, y: 2800)] {
            XCTAssertEqual(clampToDisplays(point, displays: displays), point)
        }
    }

    func testPointsOffEveryDisplayGoToTheNearestEdge() {
        XCTAssertEqual(clampToDisplays(CGPoint(x: -50, y: 400), displays: displays), CGPoint(x: 0, y: 400))
        XCTAssertEqual(clampToDisplays(CGPoint(x: 800, y: -40), displays: displays), CGPoint(x: 800, y: 0))
        XCTAssertEqual(clampToDisplays(CGPoint(x: 5000, y: 100), displays: displays), CGPoint(x: 4071, y: 100))
        XCTAssertEqual(clampToDisplays(CGPoint(x: 2000, y: -900), displays: displays), CGPoint(x: 2000, y: -300))
        // The gap above the laptop's right edge belongs to no display: it snaps to the closer one.
        XCTAssertEqual(clampToDisplays(CGPoint(x: 1500, y: -100), displays: displays), CGPoint(x: 1512, y: -100))
        // Beside the portrait display, under the laptop's left corner.
        XCTAssertEqual(clampToDisplays(CGPoint(x: 100, y: 1500), displays: displays), CGPoint(x: 216, y: 1500))
        XCTAssertEqual(clampToDisplays(CGPoint(x: 100, y: 990), displays: displays), CGPoint(x: 100, y: 981))
        XCTAssertEqual(clampToDisplays(CGPoint(x: 700, y: 5000), displays: displays), CGPoint(x: 700, y: 2901))
    }

    func testNoDisplaysLeavesThePointAndEmptyRectsAreIgnored() {
        XCTAssertEqual(clampToDisplays(CGPoint(x: -5, y: -5), displays: []), CGPoint(x: -5, y: -5))
        XCTAssertEqual(clampToDisplays(CGPoint(x: -5, y: -5), displays: [.zero, laptop]), CGPoint(x: 0, y: 0))
    }

    // MARK: Keys

    func testKeysMapToFixedStrokesAndPreviousAppIsTheOnlyShortcut() {
        XCTAssertEqual(RemoteKey.allCases.map(\.rawValue), [
            "left", "right", "up", "down", "return", "escape", "tab", "space", "backspace",
            "page_up", "page_down", "home", "end", "f5", "previous_app",
        ])
        XCTAssertEqual(RemoteKey.previousApp.stroke(shift: false), KeyStroke(keyCode: KeyStroke.Code.tab, modifiers: .command))
        XCTAssertEqual(RemoteKey.tab.stroke(shift: true), KeyStroke(keyCode: KeyStroke.Code.tab, modifiers: .shift))
        XCTAssertEqual(RemoteKey.escape.stroke(shift: false), KeyStroke(keyCode: KeyStroke.Code.escape))
        for key in RemoteKey.allCases where key != .previousApp {
            XCTAssertTrue(key.stroke(shift: true).modifiers.isSubset(of: .shift), key.rawValue)
            XCTAssertEqual(key.stroke(shift: false).modifiers, [], key.rawValue)
        }
    }

    func testOnlyPreviousAppUsesCommandControlOrOption() {
        // Every stroke a controller can cause through keys.
        let strokes = RemoteKey.allCases.flatMap { [$0.stroke(shift: false), $0.stroke(shift: true)] }
        let shortcuts = Set(strokes.filter { !$0.modifiers.isSubset(of: .shift) }.map { "\($0.keyCode):\($0.modifiers.rawValue)" })
        XCTAssertEqual(shortcuts, ["\(KeyStroke.Code.tab):\(KeyModifiers.command.rawValue)"])
        // Never Q, W, H, M, Space, Delete or the screenshot keys with a modifier.
        let forbidden: Set<CGKeyCode> = [12, 13, 4, 46, 49, 51, 20, 21, 23]
        XCTAssertTrue(strokes.allSatisfy { $0.modifiers.isSubset(of: .shift) || !forbidden.contains($0.keyCode) })
    }

    // MARK: Text and clicks

    func testTextSplitsIntoTwentyUnitChunksWithoutBreakingCharacters() {
        XCTAssertEqual(remoteTextChunks("Hello"), [Array("Hello".utf16)])
        let long = String(repeating: "a", count: 45)
        XCTAssertEqual(remoteTextChunks(long).map(\.count), [20, 20, 5])
        // Each emoji is two UTF-16 units (a flag is four); none is cut in half.
        let emoji = String(repeating: "👋", count: 11) + "🇮🇳"
        let chunks = remoteTextChunks(emoji)
        XCTAssertEqual(chunks.map(\.count), [20, 2 + 4])
        XCTAssertEqual(String(decoding: chunks.joined(), as: UTF16.self), emoji)
        XCTAssertEqual(remoteTextChunks(""), [])
    }

    func testClickCountGrowsForQuickClicksInPlace() {
        var counter = ClickCounter(interval: 0.5, slop: 4)
        let point = CGPoint(x: 100, y: 100)
        XCTAssertEqual(counter.down(.left, at: 1.0, point: point), 1)
        XCTAssertEqual(counter.down(.left, at: 1.3, point: CGPoint(x: 102, y: 99)), 2)
        XCTAssertEqual(counter.down(.left, at: 1.6, point: point), 3)
        XCTAssertEqual(counter.down(.left, at: 1.8, point: point), 1, "a fourth starts again")
        XCTAssertEqual(counter.down(.left, at: 2.6, point: point), 1, "too slow")
        XCTAssertEqual(counter.down(.left, at: 2.7, point: CGPoint(x: 120, y: 100)), 1, "moved away")
        XCTAssertEqual(counter.down(.right, at: 2.8, point: CGPoint(x: 120, y: 100)), 1, "another button")
    }
}
