import Foundation
import XCTest
@testable import BridgeyMac

final class FolderTransferTests: XCTestCase {
    private var root: URL!

    override func setUpWithError() throws {
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("BridgeyFolderTransferTests-\(UUID().uuidString)")
            .appendingPathComponent("Photos")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: root.deletingLastPathComponent())
    }

    private func write(_ path: String, _ text: String = "x") throws {
        let url = root.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(text.utf8).write(to: url)
    }

    func testPathRulesMatchTheProtocol() {
        for path in ["../a.txt", "/etc/passwd", "a/../b", "./a", "a//b", "a/", "tab\there", "nul\u{0}", "del\u{7f}"] {
            XCTAssertFalse(FolderRules.isValidPath(path), path)
        }
        for path in [".hidden", "back\\slash", "colon:name", "spaces ok ", "Ünïcode/名前"] {
            XCTAssertTrue(FolderRules.isValidPath(path), path)
        }
        XCTAssertTrue(FolderRules.isValidPath(Array(repeating: "d", count: 32).joined(separator: "/")))
        XCTAssertFalse(FolderRules.isValidPath(Array(repeating: "d", count: 33).joined(separator: "/")))
        XCTAssertTrue(FolderRules.isValidName(String(repeating: "n", count: 255)))
        XCTAssertFalse(FolderRules.isValidName(String(repeating: "n", count: 256)))
        XCTAssertFalse(FolderRules.isValidName(String(repeating: "é", count: 128)))
    }

    func testScanListsParentsFirstAndSkipsWhatIsNotSent() throws {
        try write("b.txt", "bb")
        try write("a/z.txt", "zzz")
        try write("a/inner/y.txt")
        try FileManager.default.createDirectory(at: root.appendingPathComponent("empty"), withIntermediateDirectories: true)
        try write(".DS_Store")
        try write("a/.hidden")
        try write("c.txt.bridgey-part")
        try FileManager.default.createSymbolicLink(
            at: root.appendingPathComponent("link"),
            withDestinationURL: root.appendingPathComponent("b.txt")
        )

        let manifest = try FolderScanner.scan(root)

        XCTAssertEqual(manifest.name, "Photos")
        XCTAssertEqual(manifest.entries, [
            FolderEntry(path: "a", isDirectory: true, size: 0),
            FolderEntry(path: "a/inner", isDirectory: true, size: 0),
            FolderEntry(path: "a/inner/y.txt", isDirectory: false, size: 1),
            FolderEntry(path: "a/z.txt", isDirectory: false, size: 3),
            FolderEntry(path: "b.txt", isDirectory: false, size: 2),
            FolderEntry(path: "empty", isDirectory: true, size: 0),
        ])
        XCTAssertEqual(manifest.fileCount, 3)
        XCTAssertEqual(manifest.folderCount, 3)
        XCTAssertEqual(manifest.totalSize, 6)
        XCTAssertEqual(manifest.skippedItems, 2)
        XCTAssertEqual(manifest.fileURLs[2]?.lastPathComponent, "y.txt")
        XCTAssertEqual(manifest.fileURLs.count, 3)
    }

    func testFolderWithoutFilesIsNotSent() throws {
        try FileManager.default.createDirectory(at: root.appendingPathComponent("a/b"), withIntermediateDirectories: true)
        try write(".hidden")
        XCTAssertThrowsError(try FolderScanner.scan(root)) { error in
            XCTAssertEqual(error as? FolderScanError, .empty)
        }
    }

    func testScanStopsWhenCancelled() throws {
        try write("a.txt")
        XCTAssertThrowsError(try FolderScanner.scan(root, isCancelled: { true })) { error in
            XCTAssertEqual(error as? FolderScanError, .cancelled)
        }
    }

    func testPagesRespectEntryAndSizeLimits() {
        let entries = (0..<600).map { FolderEntry(path: "file-\($0).txt", isDirectory: false, size: 1) }
        let manifest = OutgoingFolderManifest(name: "Many", entries: entries, fileURLs: [:], skippedItems: 0)

        let pages = manifest.pages()
        XCTAssertEqual(pages.map(\.count), [256, 256, 88])
        XCTAssertEqual(pages.flatMap { $0 }.map(\.path), entries.map(\.path))
        XCTAssertEqual(pages[0][0], FolderEntryPayload(type: "file", path: "file-0.txt", size: 1))

        let small = manifest.pages(maxEntryBytes: 1_000)
        XCTAssertGreaterThan(small.count, 3)
        let encoder = JSONEncoder()
        for page in small {
            let bytes = page.reduce(0) { $0 + ((try? encoder.encode($1))?.count ?? 0) + 1 }
            XCTAssertLessThanOrEqual(bytes, 1_000)
        }

        let folder = OutgoingFolderManifest(
            name: "Tree",
            entries: [FolderEntry(path: "dir", isDirectory: true, size: 0)],
            fileURLs: [:],
            skippedItems: 0
        )
        XCTAssertEqual(folder.pages(), [[FolderEntryPayload(type: "dir", path: "dir", size: nil)]])
    }
}
