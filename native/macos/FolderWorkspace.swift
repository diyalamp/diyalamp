import Foundation

struct FolderWorkspace {
    struct Entry {
        let relativePath: String
        fileprivate let components: [String]
    }

    let root: URL
    let notes: [Entry]

    init(opening directory: URL) throws {
        try self.init(root: directory.standardizedFileURL.resolvingSymlinksInPath())
    }

    private init(root: URL) throws {
        try Self.checkRoot(root)
        self.root = root
        let keys: Set<URLResourceKey> = [.isRegularFileKey, .isSymbolicLinkKey]
        var scanError: Error?
        guard let files = FileManager.default.enumerator(
            at: root, includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles, .skipsPackageDescendants],
            errorHandler: { _, error in scanError = error; return false }
        ) else { throw CocoaError(.fileReadUnknown) }

        var notes: [Entry] = []
        let prefix = root.pathComponents
        for case let url as URL in files {
            let values = try url.resourceValues(forKeys: keys)
            guard values.isSymbolicLink != true, values.isRegularFile == true,
                Self.markdownExtensions.contains(url.pathExtension.lowercased())
            else { continue }
            let components = url.resolvingSymlinksInPath().pathComponents
            guard components.starts(with: prefix) else { continue }
            let relative = Array(components.dropFirst(prefix.count))
            notes.append(Entry(relativePath: relative.joined(separator: "/"), components: relative))
        }
        if let scanError { throw scanError }
        self.notes = notes.sorted {
            let order = $0.relativePath.localizedStandardCompare($1.relativePath)
            return order == .orderedSame ? $0.relativePath < $1.relativePath : order == .orderedAscending
        }
    }

    func refreshed() throws -> FolderWorkspace {
        try FolderWorkspace(root: root)
    }

    func index(of url: URL) -> Int? {
        let prefix = root.pathComponents
        let components = url.standardizedFileURL.pathComponents
        guard components.starts(with: prefix) else { return nil }
        return notes.firstIndex { $0.components.elementsEqual(components.dropFirst(prefix.count)) }
    }

    func checkedURL(for entry: Entry) throws -> URL {
        try Self.checkRoot(root)
        var url = root
        for (index, component) in entry.components.enumerated() {
            let isDirectory = index < entry.components.count - 1
            url = url.appendingPathComponent(component, isDirectory: isDirectory)
            let values = try url.resourceValues(forKeys: [.isDirectoryKey, .isRegularFileKey, .isSymbolicLinkKey])
            guard values.isSymbolicLink != true,
                isDirectory ? values.isDirectory == true : values.isRegularFile == true
            else { throw CocoaError(.fileReadNoSuchFile) }
        }
        return url
    }

    private static let markdownExtensions: Set<String> = ["md", "markdown"]

    private static func checkRoot(_ root: URL) throws {
        let path = root.path
        let freshURL = URL(fileURLWithPath: path, isDirectory: true)
        guard freshURL.resolvingSymlinksInPath().path == path,
            try freshURL.resourceValues(forKeys: [.isDirectoryKey]).isDirectory == true
        else { throw CocoaError(.fileReadNoSuchFile) }
    }
}
