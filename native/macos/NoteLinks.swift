import CMarkGFM
import Darwin
import Foundation

enum WikiLinkSyntax {
    static func links(in bytes: [UInt8], range: Range<Int>, limit: Int = .max)
        -> [(range: Range<Int>, destination: String)] {
        var result: [(Range<Int>, String)] = []
        var opening: Int?
        var backslashes = 0
        var index = range.lowerBound
        // The span may begin after an escape that the parser excluded from the text node.
        var prefix = index
        while prefix > 0 && bytes[prefix - 1] == 92 {
            backslashes += 1
            prefix -= 1
        }
        while index + 1 < range.upperBound && result.count < limit {
            let byte = bytes[index]
            let escaped = backslashes % 2 != 0
            backslashes = byte == 92 ? backslashes + 1 : 0
            if byte == 91 {
                opening = nil
                if !escaped && bytes[index + 1] == 91 {
                    opening = index
                    index += 1
                }
            } else if byte == 93 && !escaped && bytes[index + 1] == 93, let start = opening {
                let raw = bytes[(start + 2)..<index]
                if let value = String(validating: raw, as: UTF8.self) {
                    let destination = value.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !destination.isEmpty { result.append((start..<(index + 2), destination)) }
                }
                opening = nil
                index += 1
            }
            index += 1
        }
        return result
    }
}

enum NoteLinks {
    struct Target: Equatable, Sendable {
        let destination: String
        let wiki: Bool
    }

    struct Occurrence: Sendable {
        let target: Target
        let sourceRange: NSRange?
    }

    struct Overlay: Sendable {
        let url: URL
        let text: String
    }

    struct Snapshot: Sendable {
        fileprivate var destinations: [String: Set<String>] = [:]
        fileprivate var sources: [String: Set<String>] = [:]
        fileprivate var unresolvedCounts: [String: Int] = [:]
        fileprivate(set) var partial = false

        func incoming(_ relativePath: String) -> [String] { sources[relativePath]?.sorted() ?? [] }
        func outgoing(_ relativePath: String) -> [String] { destinations[relativePath]?.sorted() ?? [] }
        func unresolved(_ relativePath: String) -> Int { unresolvedCounts[relativePath] ?? 0 }
    }

    static let parserLock = NSLock()
    static let maxNoteBytes = 1_048_576
    private static let maxNotes = 5_000
    private static let maxTotalBytes = 32 * 1_048_576
    private static let maxTargets = 100_000
    private static let utf8BOM: [UInt8] = [0xef, 0xbb, 0xbf]

    static func targets(_ markdown: String) -> [Target] {
        ((try? parse(markdown, includeRanges: false, limit: maxTargets, budget: Budget { false })) ?? []).map(\.target)
    }

    static func occurrences(_ markdown: String) -> [Occurrence] {
        (try? parse(markdown, includeRanges: true, limit: maxTargets, budget: Budget { false })) ?? []
    }

    static func resolve(_ target: Target, from source: URL, in workspace: FolderWorkspace) -> FolderWorkspace.Entry? {
        guard let index = workspace.index(of: source) else { return nil }
        let entry = workspace.notes[index]
        var resolver = Resolver(workspace)
        guard resolver.isSafe(entry) else { return nil }
        return resolver.resolve(target, from: entry.relativePath)
    }

    static func activeLinks(_ overlay: Overlay, in workspace: FolderWorkspace,
                            isCancelled: @escaping () -> Bool = { false }) throws -> (paths: [String], unresolved: Int, partial: Bool) {
        let budget = Budget(isCancelled: isCancelled)
        try budget.checkCancellation()
        guard let source = workspace.index(of: overlay.url) else { return ([], 0, false) }
        guard overlay.text.utf8.count <= maxNoteBytes else { return ([], 0, true) }
        let entry = workspace.notes[source]
        var resolver = Resolver(workspace)
        guard resolver.isSafe(entry) else { return ([], 0, true) }
        let links = try parse(overlay.text, includeRanges: false, limit: maxTargets, budget: budget)
        var paths: Set<String> = []
        var unresolved = 0
        for link in links {
            try budget.checkCancellation()
            if budget.expired { break }
            if let destination = resolver.resolve(link.target, from: entry.relativePath) {
                paths.insert(destination.relativePath)
            } else if link.target.wiki {
                unresolved += 1
            }
        }
        return (paths.sorted(), unresolved, budget.expired || links.count == maxTargets || workspace.notes.count > maxNotes)
    }

    static func scan(_ workspace: FolderWorkspace, overlay: Overlay? = nil,
                     isCancelled: @escaping () -> Bool = { false }) throws -> Snapshot {
        let budget = Budget(isCancelled: isCancelled)
        try budget.checkCancellation()
        var result = Snapshot()
        var resolver = Resolver(workspace)
        let overlaidIndex = overlay.flatMap { workspace.index(of: $0.url) }
        var remaining = maxTotalBytes
        var targetsRemaining = maxTargets
        result.partial = workspace.notes.count > maxNotes

        for (index, entry) in workspace.notes.prefix(maxNotes).enumerated() {
            try budget.checkCancellation()
            guard !budget.expired, remaining > 0, targetsRemaining > 0 else {
                result.partial = true
                break
            }
            guard resolver.isSafe(entry) else { result.partial = true; continue }
            let limit = min(maxNoteBytes, remaining)
            let markdown: String?
            if index == overlaidIndex, let overlay {
                let size = overlay.text.utf8.count
                markdown = size <= limit ? overlay.text : nil
                if markdown != nil { remaining -= size }
            } else {
                do { markdown = try read(entry, in: workspace, limit: limit, remaining: &remaining, budget: budget) }
                catch is CancellationError { throw CancellationError() }
                catch { markdown = nil }
            }
            guard let markdown else { result.partial = true; continue }
            let links = try parse(markdown, includeRanges: false, limit: targetsRemaining, budget: budget)
            targetsRemaining -= links.count
            if budget.expired || targetsRemaining == 0 { result.partial = true }
            for link in links {
                try budget.checkCancellation()
                if budget.expired { result.partial = true; break }
                let source = entry.relativePath
                if let destination = resolver.resolve(link.target, from: source)?.relativePath {
                    result.destinations[source, default: []].insert(destination)
                    result.sources[destination, default: []].insert(source)
                } else if link.target.wiki {
                    result.unresolvedCounts[source, default: 0] += 1
                }
            }
        }
        try budget.checkCancellation()
        return result
    }

    private struct Budget {
        let isCancelled: () -> Bool
        let deadline = DispatchTime.now() + .seconds(5)
        var expired: Bool { DispatchTime.now() >= deadline }

        func checkCancellation() throws {
            if isCancelled() { throw CancellationError() }
        }
    }

    private static func parse(_ markdown: String, includeRanges: Bool, limit: Int,
                              budget: Budget) throws -> [Occurrence] {
        try budget.checkCancellation()
        guard !budget.expired, limit > 0, markdown.utf8.count <= maxNoteBytes else { return [] }
        let bytes = Array(markdown.utf8)
        var source = SourcePositions(bytes)
        parserLock.lock()
        let parsed = bytes.withUnsafeBytes {
            cmark_parse_document($0.baseAddress?.assumingMemoryBound(to: CChar.self), $0.count, CMARK_OPT_SOURCEPOS)
        }
        parserLock.unlock()
        guard let root = parsed else { return [] }
        defer { cmark_node_free(root) }
        guard let iterator = cmark_iter_new(root) else { return [] }
        defer { cmark_iter_free(iterator) }
        var result: [Occurrence] = []

        while result.count < limit {
            try budget.checkCancellation()
            if budget.expired { break }
            let event = cmark_iter_next(iterator)
            if event == CMARK_EVENT_DONE { break }
            guard event == CMARK_EVENT_ENTER, let node = cmark_iter_get_node(iterator) else { continue }
            let type = cmark_node_get_type(node)
            if type == CMARK_NODE_LINK || type == CMARK_NODE_IMAGE {
                if type == CMARK_NODE_LINK, let url = cmark_node_get_url(node),
                   let destination = String(validatingCString: url) {
                    let target = Target(destination: destination, wiki: false)
                    let range = includeRanges ? source.markdownRange(of: node) : nil
                    result.append(Occurrence(target: target, sourceRange: range))
                }
                cmark_iter_reset(iterator, node, CMARK_EVENT_EXIT)
            } else if type == CMARK_NODE_TEXT, let range = source.range(of: node),
                      let literal = cmark_node_get_literal(node) {
                let text = String(cString: literal)
                let literalText = bytes[range].elementsEqual(text.utf8) ? nil : text as NSString
                var literalOffset = 0
                for link in WikiLinkSyntax.links(in: bytes, range: range, limit: limit - result.count) {
                    // Discard uncertain source positions instead of attributing nearby literal code to prose.
                    if let literalText {
                        let token = String(decoding: bytes[link.range], as: UTF8.self)
                        let match = literalText.range(of: token, options: .literal,
                            range: NSRange(location: literalOffset, length: literalText.length - literalOffset))
                        guard match.location != NSNotFound else { continue }
                        literalOffset = NSMaxRange(match)
                    }
                    let target = Target(destination: link.destination, wiki: true)
                    result.append(Occurrence(target: target,
                        sourceRange: includeRanges ? source.utf16Range(link.range) : nil))
                }
            }
        }
        try budget.checkCancellation()
        return result
    }

    private struct SourcePositions {
        let bytes: [UInt8]
        let lines: [Range<Int>]
        private var lastByte = 0
        private var lastUTF16 = 0

        init(_ bytes: [UInt8]) {
            self.bytes = bytes
            var lines: [Range<Int>] = []
            var start = 0
            var end = start
            while end < bytes.count {
                if bytes[end] == 10 || bytes[end] == 13 {
                    lines.append(start..<end)
                    if bytes[end] == 13, end + 1 < bytes.count, bytes[end + 1] == 10 { end += 1 }
                    start = end + 1
                }
                end += 1
            }
            lines.append(start..<bytes.count)
            self.lines = lines
        }

        func range(of node: OpaquePointer) -> Range<Int>? {
            let line = Int(cmark_node_get_start_line(node))
            let first = Int(cmark_node_get_start_column(node))
            let last = Int(cmark_node_get_end_column(node))
            guard line > 0, line <= lines.count, cmark_node_get_end_line(node) == line,
                  first > 0, last >= first, last <= lines[line - 1].count else { return nil }
            let start = lines[line - 1].lowerBound
            return (start + first - 1)..<(start + last)
        }

        mutating func markdownRange(of node: OpaquePointer) -> NSRange? {
            guard let range = range(of: node), let first = bytes[range].first, let last = bytes[range].last,
                  (first == 91 && (last == 93 || last == 41)) || (first == 60 && last == 62)
            else { return nil }
            return utf16Range(range)
        }

        mutating func utf16Range(_ range: Range<Int>) -> NSRange? {
            guard range.lowerBound >= lastByte,
                  let prefix = String(validating: bytes[lastByte..<range.lowerBound], as: UTF8.self),
                  let text = String(validating: bytes[range], as: UTF8.self) else { return nil }
            let start = lastUTF16 + prefix.utf16.count
            let length = text.utf16.count
            lastByte = range.upperBound
            lastUTF16 = start + length
            return NSRange(location: start, length: length)
        }
    }

    private struct Resolver {
        let workspace: FolderWorkspace
        private let exact: [String: [FolderWorkspace.Entry]]
        private let relative: [String: [FolderWorkspace.Entry]]
        private let names: [String: [FolderWorkspace.Entry]]
        private var safe: [String: Bool] = [:]

        init(_ workspace: FolderWorkspace) {
            self.workspace = workspace
            let entries = workspace.notes.prefix(maxNotes)
            exact = Dictionary(grouping: entries, by: \.relativePath)
            relative = Dictionary(grouping: entries, by: { Self.stem($0.relativePath) })
            names = Dictionary(grouping: entries, by: { Self.stem($0.relativePath.split(separator: "/").last.map(String.init) ?? "") })
        }

        mutating func isSafe(_ entry: FolderWorkspace.Entry) -> Bool {
            if let value = safe[entry.relativePath] { return value }
            let value = (try? workspace.checkedURL(for: entry)) != nil
            safe[entry.relativePath] = value
            return value
        }

        mutating func resolve(_ target: Target, from source: String) -> FolderWorkspace.Entry? {
            var name = target.destination.trimmingCharacters(in: .whitespacesAndNewlines)
            if target.wiki { name = Self.before("|", in: name).trimmingCharacters(in: .whitespacesAndNewlines) }
            name = Self.before("#", in: name)
            if !target.wiki {
                guard let decoded = Self.before("?", in: name).removingPercentEncoding else { return nil }
                name = decoded
            }
            guard !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                  !name.hasPrefix("/"), !name.contains(":"), !name.contains("\\"),
                  !name.unicodeScalars.contains(where: { $0.value < 32 || (127...159).contains($0.value) })
            else { return nil }
            let components = name.split(separator: "/").map(String.init)
            let parent = source.split(separator: "/").dropLast().map(String.init)
            guard let local = Self.normalized(parent + components), !local.isEmpty else { return nil }
            if let direct = candidate(exact[local]) { return direct }
            guard target.wiki else { return nil }
            if !Self.isMarkdown(local) {
                if let direct = candidate(exact[local + ".md"]) { return direct }
                if let direct = candidate(exact[local + ".markdown"]) { return direct }
            }
            if name.contains("/") {
                guard let rooted = Self.normalized(components), !rooted.isEmpty else { return nil }
                return candidate(relative[Self.stem(rooted)])
            }
            return candidate(names[Self.stem(name)])
        }

        private mutating func candidate(_ entries: [FolderWorkspace.Entry]?) -> FolderWorkspace.Entry? {
            guard let entries, entries.count == 1, isSafe(entries[0]) else { return nil }
            return entries[0]
        }

        private static func before(_ separator: Character, in name: String) -> String {
            guard let index = name.firstIndex(of: separator) else { return name }
            return String(name[..<index])
        }

        private static func normalized(_ components: [String]) -> String? {
            var resolved: [String] = []
            for component in components {
                switch component {
                case ".": continue
                case "..":
                    guard !resolved.isEmpty else { return nil }
                    resolved.removeLast()
                default: resolved.append(component)
                }
            }
            return resolved.joined(separator: "/")
        }

        private static func isMarkdown(_ path: String) -> Bool {
            let lower = path.lowercased()
            return lower.hasSuffix(".md") || lower.hasSuffix(".markdown")
        }

        private static func stem(_ path: String) -> String {
            let lower = path.lowercased()
            if lower.hasSuffix(".markdown") { return String(lower.dropLast(9)) }
            if lower.hasSuffix(".md") { return String(lower.dropLast(3)) }
            return lower
        }
    }

    private static func read(_ entry: FolderWorkspace.Entry, in workspace: FolderWorkspace, limit: Int,
                             remaining: inout Int, budget: Budget) throws -> String? {
        let flags = O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC
        var descriptor = open(workspace.root.path, flags | O_DIRECTORY)
        guard descriptor >= 0 else { return nil }
        defer { close(descriptor) }
        var actualRoot = [CChar](repeating: 0, count: Int(MAXPATHLEN))
        guard fcntl(descriptor, F_GETPATH, &actualRoot) == 0,
              String(validatingCString: actualRoot).map({ URL(fileURLWithPath: $0).resolvingSymlinksInPath().path })
                  == workspace.root.path else { return nil }
        let components = entry.relativePath.split(separator: "/")
        for (index, component) in components.enumerated() {
            let next = openat(descriptor, String(component), index == components.count - 1 ? flags : flags | O_DIRECTORY)
            guard next >= 0 else { return nil }
            close(descriptor)
            descriptor = next
        }
        var metadata = stat()
        guard fstat(descriptor, &metadata) == 0, metadata.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG),
              metadata.st_size <= limit else { return nil }
        let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: false)
        var data = Data()
        while data.count <= limit {
            try budget.checkCancellation()
            guard !budget.expired else { return nil }
            guard let chunk = try handle.read(upToCount: min(65_536, limit + 1 - data.count)),
                  !chunk.isEmpty else { break }
            data.append(chunk)
            remaining -= min(remaining, chunk.count)
        }
        guard data.count <= limit else { return nil }
        let body = data.starts(with: utf8BOM) ? data.dropFirst(utf8BOM.count) : data[...]
        return String(validating: body, as: UTF8.self)
    }
}
