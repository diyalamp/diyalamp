import Foundation

@main
enum RecoveryStoreTests {
    static func main() throws {
        let folder = FileManager.default.temporaryDirectory
            .appendingPathComponent("diyalamp-recovery-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: folder) }
        try preservesExactBytes(in: folder)
        try rejectsMalformedAndUnsafeFiles(in: folder)
        try excludesAnotherOwner(in: folder)
        print("PASS Native recovery store byte fidelity, isolation, and exclusive lock.")
    }

    private static func preservesExactBytes(in folder: URL) throws {
        let support = folder.appendingPathComponent("Support", isDirectory: true)
        let store = RecoveryStore(fileURL: support.appendingPathComponent("draft.plist"))
        let note = folder.appendingPathComponent("Cafe\u{0301}.md").resolvingSymlinksInPath()
        let original = Data([0xEF, 0xBB, 0xBF]) + Data("Original\r\n".utf8)
        try original.write(to: note)
        let initial = RecoverySnapshot(noteURL: note, original: original, edited: "Cafe\u{0301} 🪔\r\n\u{0}")
        let latest = RecoverySnapshot(noteURL: note, original: original, edited: "Café 🪔\r\n\u{0}")
        precondition(initial != latest)
        try require(store.load() == nil, "No recovery exists before the first edit")
        try store.clear()
        try store.save(initial)
        try require(store.load() == initial, "Draft preserves exact original and edited bytes")
        try require(permissions(at: store.fileURL) == 0o600, "Recovery file is private")
        try require(permissions(at: support) == 0o700, "Recovery directory is private")
        try store.save(latest)
        try require(store.load() == latest, "New draft replaces the previous draft")
        try require(Data(contentsOf: note) == original, "Saving recovery leaves the note untouched")
        try FileManager.default.removeItem(at: note)
        try require(store.load() == latest, "Draft remains available after note removal")
        try store.clear()
        try store.clear()
        try require(store.load() == nil, "Clearing twice removes recovery without error")
    }

    private static func rejectsMalformedAndUnsafeFiles(in folder: URL) throws {
        let path = folder.appendingPathComponent("bad.plist")
        let other = folder.appendingPathComponent("external.md")
        let original = Data("External\r\n".utf8)
        try original.write(to: other)
        let store = RecoveryStore(fileURL: path)
        let snapshot = RecoverySnapshot(noteURL: other, original: original, edited: "Changed")
        let malformed = Data("not a property list".utf8)
        try malformed.write(to: path)
        try expectFailure { _ = try store.load() }
        try require(Data(contentsOf: path) == malformed, "Invalid recovery is preserved for inspection")
        for record: [String: Any] in [
            ["version": 2, "notePath": other.path, "original": original, "edited": Data()],
            ["version": 1, "notePath": "relative.md", "original": original, "edited": Data()],
            ["version": 1, "notePath": other.path, "original": original, "edited": Data([0xFF])],
        ] {
            let data = try PropertyListSerialization.data(fromPropertyList: record, format: .binary, options: 0)
            try data.write(to: path)
            try expectFailure { _ = try store.load() }
        }
        try store.clear()
        try FileManager.default.createSymbolicLink(at: path, withDestinationURL: other)
        try expectFailure { _ = try store.load() }
        try expectFailure { try store.save(snapshot) }
        try expectFailure { try store.clear() }
        try require(Data(contentsOf: other) == original, "Unsafe storage never changes the linked note")
        let alias = folder.appendingPathComponent("Alias")
        try FileManager.default.createSymbolicLink(at: alias, withDestinationURL: folder)
        let aliasedStore = RecoveryStore(fileURL: alias.appendingPathComponent("through-alias.plist"))
        try expectFailure { try aliasedStore.save(snapshot) }
        require(!FileManager.default.fileExists(atPath: folder.appendingPathComponent("through-alias.plist").path),
            "A symlink cannot serve as the recovery directory")
    }

    private static func excludesAnotherOwner(in folder: URL) throws {
        let store = RecoveryStore(fileURL: folder.appendingPathComponent("lease.plist"))
        let lockPath = folder.appendingPathComponent(".lease.plist.lock")
        try withExtendedLifetime(store.lock()) {
            try expectFailure { _ = try store.lock() }
            try require(permissions(at: lockPath) == 0o600, "Lock file is private")
            let other = RecoveryStore(fileURL: folder.appendingPathComponent("other.plist"))
            withExtendedLifetime(try other.lock()) {}
        }
        withExtendedLifetime(try store.lock()) {}
        require(FileManager.default.fileExists(atPath: lockPath.path), "Closing a lease leaves its lock path")
        try FileManager.default.removeItem(at: lockPath)
        let target = folder.appendingPathComponent("external.md")
        try FileManager.default.createSymbolicLink(at: lockPath, withDestinationURL: target)
        try expectFailure { _ = try store.lock() }
    }

    private static func permissions(at url: URL) throws -> Int? {
        try FileManager.default.attributesOfItem(atPath: url.path)[.posixPermissions] as? Int
    }

    private static func require(_ value: @autoclosure () throws -> Bool, _ message: String) rethrows {
        let satisfied = try value()
        precondition(satisfied, message)
    }

    private static func expectFailure(_ action: () throws -> Void) throws {
        do { try action() } catch { return }
        preconditionFailure("Operation should have failed")
    }
}
