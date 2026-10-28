import Foundation
import XCTest
@testable import BridgeyMac

final class FileSendQueueTests: XCTestCase {
    func testFilesStartOneAfterAnotherInSelectionOrder() {
        var queue = FileSendQueue<String>()
        queue.enqueue(id: "a", item: "first.txt")
        queue.enqueue(id: "b", item: "second.txt")

        XCTAssertEqual(queue.startNext()?.item, "first.txt")
        XCTAssertNil(queue.startNext())
        XCTAssertEqual(queue.pendingIDs, ["b"])

        XCTAssertTrue(queue.finish(id: "a"))
        XCTAssertEqual(queue.startNext()?.id, "b")
        XCTAssertTrue(queue.finish(id: "b"))
        XCTAssertNil(queue.startNext())
        XCTAssertTrue(queue.isEmpty)
    }

    func testFinishingAnotherTransferDoesNotAdvanceTheQueue() {
        var queue = FileSendQueue<String>()
        queue.enqueue(id: "a", item: "first.txt")
        queue.enqueue(id: "b", item: "second.txt")
        _ = queue.startNext()

        XCTAssertFalse(queue.finish(id: "incoming"))
        XCTAssertFalse(queue.finish(id: "b"))
        XCTAssertEqual(queue.currentID, "a")
        XCTAssertTrue(queue.finish(id: "a"))
        XCTAssertFalse(queue.finish(id: "a"))
    }

    func testCancellingAWaitingFileKeepsTheRest() {
        var queue = FileSendQueue<String>()
        ["a", "b", "c"].forEach { queue.enqueue(id: $0, item: "\($0).txt") }
        _ = queue.startNext()

        XCTAssertTrue(queue.removePending(id: "b"))
        XCTAssertFalse(queue.removePending(id: "a"))
        XCTAssertEqual(queue.currentID, "a")
        XCTAssertEqual(queue.pendingIDs, ["c"])
    }

    func testCancellingTheRemainingQueueLeavesTheCurrentFile() {
        var queue = FileSendQueue<String>()
        ["a", "b", "c"].forEach { queue.enqueue(id: $0, item: "\($0).txt") }
        _ = queue.startNext()

        XCTAssertEqual(queue.removeAllPending(), ["b", "c"])
        XCTAssertEqual(queue.currentID, "a")
        XCTAssertTrue(queue.finish(id: "a"))
        XCTAssertNil(queue.startNext())
    }

    func testInterruptionForgetsCurrentAndWaitingFiles() {
        var queue = FileSendQueue<String>()
        ["a", "b"].forEach { queue.enqueue(id: $0, item: "\($0).txt") }
        _ = queue.startNext()

        XCTAssertEqual(queue.removeAll(), ["a", "b"])
        XCTAssertTrue(queue.isEmpty)
        XCTAssertFalse(queue.finish(id: "a"))
    }

    func testDuplicateIDsAreIgnored() {
        var queue = FileSendQueue<String>()
        queue.enqueue(id: "a", item: "first.txt")
        queue.enqueue(id: "a", item: "again.txt")
        _ = queue.startNext()
        queue.enqueue(id: "a", item: "again.txt")

        XCTAssertEqual(queue.pendingIDs, [])
    }

    func testReceivedNameConflictsAreNumberedDeterministically() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("BridgeyFileSendQueueTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let requested = directory.appendingPathComponent("photo.jpg")

        XCTAssertEqual(uniqueReceivedFileURL(requested).lastPathComponent, "photo.jpg")
        FileManager.default.createFile(atPath: requested.path, contents: Data())
        XCTAssertEqual(uniqueReceivedFileURL(requested).lastPathComponent, "photo 2.jpg")
        FileManager.default.createFile(
            atPath: directory.appendingPathComponent("photo 2.jpg.bridgey-part").path,
            contents: Data()
        )
        XCTAssertEqual(uniqueReceivedFileURL(requested).lastPathComponent, "photo 3.jpg")

        let noExtension = directory.appendingPathComponent("notes")
        FileManager.default.createFile(atPath: noExtension.path, contents: Data())
        XCTAssertEqual(uniqueReceivedFileURL(noExtension).lastPathComponent, "notes 2")
    }
}
