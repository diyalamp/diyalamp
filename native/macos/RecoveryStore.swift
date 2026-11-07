import Darwin
import Foundation

struct RecoverySnapshot: Equatable {
    let noteURL: URL
    let original: Data
    let edited: String

    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.noteURL == rhs.noteURL && lhs.original == rhs.original
            && lhs.edited.utf8.elementsEqual(rhs.edited.utf8)
    }
}

final class RecoveryLease {
    private let descriptor: Int32

    fileprivate init(_ descriptor: Int32) { self.descriptor = descriptor }

    deinit { close(descriptor) }
}

struct RecoveryStore {
    let fileURL: URL

    private struct Record: Codable {
        let version: Int
        let notePath: String
        let original: Data
        let edited: Data
    }

    private enum Failure: LocalizedError {
        case invalidSnapshot, unsafeStorage

        var errorDescription: String? {
            switch self {
            case .invalidSnapshot: "The recovery copy has an invalid note path or format."
            case .unsafeStorage: "The recovery location is not a regular app data file."
            }
        }
    }

    static func applicationSupport() throws -> RecoveryStore {
        let support = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true)
        let directory = support.appendingPathComponent("com.diyalamp.preview.nativeworkspace", isDirectory: true)
        return RecoveryStore(fileURL: directory.appendingPathComponent("recovery.plist"))
    }

    func lock() throws -> RecoveryLease {
        guard let lease = try withDirectory(create: true, { directory, name in
            let descriptor = openat(directory, ".\(name).lock",
                O_RDWR | O_CREAT | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0o600)
            guard descriptor >= 0 else { throw posixError() }
            do {
                try requireRegularFile(descriptor)
                guard flock(descriptor, LOCK_EX | LOCK_NB) == 0 else { throw posixError() }
                return RecoveryLease(descriptor)
            } catch {
                close(descriptor)
                throw error
            }
        }) else { throw Failure.unsafeStorage }
        return lease
    }

    func load() throws -> RecoverySnapshot? {
        try withDirectory { directory, name -> RecoverySnapshot? in
            let descriptor = openat(directory, name, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC)
            guard descriptor >= 0 else {
                if errno == ENOENT { return nil }
                throw posixError()
            }
            defer { close(descriptor) }
            try requireRegularFile(descriptor)
            let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: false)
            let bytes = try handle.readToEnd() ?? Data()
            let record = try PropertyListDecoder().decode(Record.self, from: bytes)
            guard record.version == 1, isAbsolutePath(record.notePath),
                let edited = String(validating: record.edited, as: UTF8.self)
            else { throw Failure.invalidSnapshot }
            let noteURL = URL(fileURLWithPath: record.notePath, isDirectory: false)
            guard noteURL.standardizedFileURL.path == record.notePath else { throw Failure.invalidSnapshot }
            return RecoverySnapshot(noteURL: noteURL, original: record.original, edited: edited)
        } ?? nil
    }

    func save(_ snapshot: RecoverySnapshot) throws {
        let path = snapshot.noteURL.standardizedFileURL.path
        guard snapshot.noteURL.isFileURL, isAbsolutePath(path),
            path != fileURL.standardizedFileURL.path,
            path != fileURL.resolvingSymlinksInPath().path
        else { throw Failure.invalidSnapshot }
        let record = Record(version: 1, notePath: path, original: snapshot.original, edited: Data(snapshot.edited.utf8))
        let encoder = PropertyListEncoder()
        encoder.outputFormat = .binary
        let data = try encoder.encode(record)

        try withDirectory(create: true) { directory, name in
            _ = try regularEntryExists(in: directory, named: name)
            let temporary = ".\(name).\(UUID().uuidString).tmp"
            let descriptor = openat(directory, temporary, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0o600)
            guard descriptor >= 0 else { throw posixError() }
            defer {
                close(descriptor)
                unlinkat(directory, temporary, 0)
            }
            let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: false)
            try handle.write(contentsOf: data)
            guard renameat(directory, temporary, directory, name) == 0 else { throw posixError() }
        }
    }

    func clear() throws {
        try withDirectory { directory, name in
            guard try regularEntryExists(in: directory, named: name) else { return }
            guard unlinkat(directory, name, 0) == 0 || errno == ENOENT else { throw posixError() }
        }
    }

    private func withDirectory<Result>(create: Bool = false,
        _ operation: (Int32, String) throws -> Result) throws -> Result? {
        let path = fileURL.path
        let name = fileURL.lastPathComponent
        guard fileURL.isFileURL, isAbsolutePath(path), !name.isEmpty, name != "/", name != ".", name != ".."
        else { throw Failure.unsafeStorage }
        let parent = fileURL.deletingLastPathComponent()
        let flags = O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC
        var descriptor = open(parent.path, flags)
        if descriptor < 0 && errno == ENOENT {
            guard create else { return nil }
            try FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true,
                attributes: [.posixPermissions: 0o700])
            descriptor = open(parent.path, flags)
        }
        guard descriptor >= 0 else { throw posixError() }
        defer { close(descriptor) }
        return try operation(descriptor, name)
    }

    private func regularEntryExists(in directory: Int32, named name: String) throws -> Bool {
        var metadata = stat()
        guard fstatat(directory, name, &metadata, AT_SYMLINK_NOFOLLOW) == 0 else {
            if errno == ENOENT { return false }
            throw posixError()
        }
        try requireRegularFile(metadata)
        return true
    }

    private func requireRegularFile(_ descriptor: Int32) throws {
        var metadata = stat()
        guard fstat(descriptor, &metadata) == 0 else { throw posixError() }
        try requireRegularFile(metadata)
    }

    private func requireRegularFile(_ metadata: stat) throws {
        guard metadata.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG) else { throw Failure.unsafeStorage }
    }

    private func isAbsolutePath(_ path: String) -> Bool {
        path.hasPrefix("/") && path != "/" && !path.utf8.contains(0)
    }

    private func posixError() -> POSIXError {
        POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
    }
}
