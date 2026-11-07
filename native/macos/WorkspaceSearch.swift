import Darwin
import Foundation

enum WorkspaceSearch {
    enum Mode: Sendable { case filenames, text }

    struct Options: Sendable {
        var mode: Mode = .filenames
        var caseSensitive = false
        var wholeWord = false
        var regularExpression = false
        var include = ""
        var exclude = ""
    }

    struct Overlay: Sendable {
        let url: URL
        let text: String
    }

    struct Match: Sendable {
        let noteIndex: Int
        let relativePath: String
        let lineNumber: Int?
        let range: NSRange?
        let preview: String
        let matchedText: String?
    }

    struct Result: Sendable {
        let matches: [Match]
        let truncated: Bool
        let skipped: Int
    }

    private enum Failure: LocalizedError {
        case invalidExpression, expressionTooLong, invalidGlob(String)

        var errorDescription: String? {
            switch self {
            case .invalidExpression: "The regular expression is invalid."
            case .expressionTooLong: "The search expression is too long."
            case .invalidGlob(let field): "The \(field) pattern is invalid. Use paths relative to the open folder."
            }
        }
    }

    static let maxFileBytes = 5 * 1024 * 1024
    static let maxResults = 1_000
    static let maxMatchesPerNote = 100
    private static let maxNotes = 5_000
    private static let maxTotalBytes = 24 * 1024 * 1024
    private static let maxExpression = 1_024
    private static let utf8BOM = Data([0xEF, 0xBB, 0xBF])

    static func search(_ query: String, in workspace: FolderWorkspace, options: Options,
                       overlay: Overlay? = nil, isCancelled: @escaping () -> Bool = { false }) throws -> Result {
        let budget = Budget(isCancelled: isCancelled)
        try budget.checkCancellation()
        guard !query.isEmpty else { return Result(matches: [], truncated: false, skipped: 0) }
        guard query.utf16.count <= maxExpression else { throw Failure.expressionTooLong }
        let term = try Term(query, options: options)
        let include = try glob(options.include, field: "include")
        let exclude = try glob(options.exclude, field: "exclude")
        let activePath = overlay?.url.standardizedFileURL.path
        var matches: [Match] = []
        var truncated = false
        var skipped = 0
        var remaining = maxTotalBytes

        for (index, entry) in workspace.notes.enumerated() {
            try budget.checkCancellation()
            if index >= maxNotes || budget.expired { truncated = true; break }
            let path = entry.relativePath
            guard let included = try matchesPath(path, include, default: true, budget: budget),
                  let excluded = try matchesPath(path, exclude, default: false, budget: budget) else {
                truncated = true
                break
            }
            guard included && !excluded else { continue }

            guard let url = try? workspace.checkedURL(for: entry) else { skipped += 1; continue }
            if options.mode == .filenames {
                var found = false
                let completed = try term.scan(url.lastPathComponent, budget: budget) { _ in
                    found = true
                    return false
                }
                guard completed else {
                    truncated = true
                    break
                }
                if found {
                    if matches.count == maxResults { truncated = true; break }
                    matches.append(Match(noteIndex: index, relativePath: path, lineNumber: nil,
                                         range: nil, preview: path, matchedText: nil))
                }
                continue
            }

            guard remaining > 0 else { truncated = true; break }
            let limit = min(maxFileBytes, remaining)
            let markdown: String?
            if let overlay, url.standardizedFileURL.path == activePath {
                let size = overlay.text.utf8.count
                markdown = size <= limit ? overlay.text : nil
                if markdown != nil { remaining -= size }
            } else {
                markdown = try? readNote(entry, in: workspace, limit: limit, remaining: &remaining, budget: budget)
            }
            guard let markdown else { skipped += 1; continue }

            var lines = Excerpts(markdown)
            var noteMatches = 0
            var totalOverflow = false
            let completed = try term.scan(markdown, budget: budget) { range in
                if matches.count == maxResults { totalOverflow = true; return false }
                if noteMatches == maxMatchesPerNote { truncated = true; return false }
                guard let excerpt = lines.at(range.location, budget: budget) else { return false }
                matches.append(Match(noteIndex: index, relativePath: path, lineNumber: excerpt.line,
                                     range: range, preview: excerpt.preview,
                                     matchedText: lines.text.substring(with: range)))
                noteMatches += 1
                return true
            }
            if !completed || totalOverflow { truncated = true; break }
        }
        try budget.checkCancellation()
        return Result(matches: matches, truncated: truncated, skipped: skipped)
    }

    private enum Term {
        case literal(String, String.CompareOptions, wholeWord: Bool)
        case regex(NSRegularExpression)

        init(_ query: String, options: Options) throws {
            guard options.regularExpression else {
                self = .literal(query, options.caseSensitive ? [] : .caseInsensitive, wholeWord: options.wholeWord)
                return
            }
            var pattern = query
            if options.wholeWord {
                let word = #"[\p{L}\p{M}\p{N}\p{Pc}]"#
                pattern = "(?<!\(word))(?:\(pattern))(?!\(word))"
            }
            var flags: NSRegularExpression.Options = [.anchorsMatchLines]
            if !options.caseSensitive { flags.insert(.caseInsensitive) }
            do { self = try .regex(NSRegularExpression(pattern: pattern, options: flags)) }
            catch { throw Failure.invalidExpression }
        }

        func scan(_ text: String, budget: Budget, consume: (NSRange) -> Bool) throws -> Bool {
            switch self {
            case .regex(let expression):
                return try budget.scan(expression, in: text, consume: consume)
            case .literal(let query, let options, let wholeWord):
                let source = text as NSString
                let length = source.length
                var offset = 0
                while offset < length {
                    try budget.checkCancellation()
                    if budget.expired { return false }
                    let found = source.range(of: query, options: options,
                                             range: NSRange(location: offset, length: length - offset))
                    guard found.location != NSNotFound, found.length > 0 else { break }
                    let end = NSMaxRange(found)
                    let boundary = !wholeWord || (!isWord(at: found.location - 1, in: source, length: length)
                                                  && !isWord(at: end, in: source, length: length))
                    if boundary {
                        offset = end
                        if !consume(found) { break }
                    } else {
                        offset = found.location + ((0xD800...0xDBFF).contains(source.character(at: found.location)) ? 2 : 1)
                    }
                }
                try budget.checkCancellation()
                return !budget.expired
            }
        }

        private func isWord(at index: Int, in text: NSString, length: Int) -> Bool {
            guard index >= 0 && index < length else { return false }
            var start = index
            if start > 0 && (0xDC00...0xDFFF).contains(text.character(at: start)) { start -= 1 }
            let high = UInt32(text.character(at: start))
            var scalar = high
            if (0xD800...0xDBFF).contains(high), start + 1 < length {
                let low = UInt32(text.character(at: start + 1))
                if (0xDC00...0xDFFF).contains(low) { scalar = 0x10000 + (high - 0xD800) * 0x400 + low - 0xDC00 }
            }
            switch UnicodeScalar(scalar)?.properties.generalCategory {
            case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
                 .nonspacingMark, .spacingMark, .enclosingMark, .decimalNumber, .letterNumber, .otherNumber,
                 .connectorPunctuation: return true
            default: return false
            }
        }
    }

    private static func matchesPath(_ path: String, _ pattern: NSRegularExpression?, default fallback: Bool,
                                    budget: Budget) throws -> Bool? {
        guard let pattern else { return fallback }
        var found = false
        let completed = try budget.scan(pattern, in: path) { _ in found = true; return false }
        return completed ? found : nil
    }

    private static func readNote(_ entry: FolderWorkspace.Entry, in workspace: FolderWorkspace,
                                 limit: Int, remaining: inout Int, budget: Budget) throws -> String? {
        let flags = O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC
        var descriptor = open(workspace.root.path, flags | O_DIRECTORY)
        guard descriptor >= 0 else { throw CocoaError(.fileReadNoSuchFile) }
        defer { close(descriptor) }

        var actualRoot = [CChar](repeating: 0, count: Int(MAXPATHLEN))
        guard fcntl(descriptor, F_GETPATH, &actualRoot) == 0,
              String(validatingCString: actualRoot).map({ URL(fileURLWithPath: $0).resolvingSymlinksInPath().path })
                  == workspace.root.path else { throw CocoaError(.fileReadNoSuchFile) }

        // Keep each path component anchored to the open folder and reject a symlink substituted after listing.
        let components = entry.relativePath.split(separator: "/")
        for (index, component) in components.enumerated() {
            let entryFlags = index == components.count - 1 ? flags : flags | O_DIRECTORY
            let next = openat(descriptor, String(component), entryFlags)
            guard next >= 0 else { throw CocoaError(.fileReadNoSuchFile) }
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
        return String(validating: data.starts(with: utf8BOM) ? data.dropFirst(utf8BOM.count) : data[...], as: UTF8.self)
    }

    private struct Budget {
        let isCancelled: () -> Bool
        let deadline = DispatchTime.now() + .seconds(3)

        var expired: Bool { DispatchTime.now() >= deadline }

        func checkCancellation() throws {
            if isCancelled() { throw CancellationError() }
        }

        func scan(_ expression: NSRegularExpression, in text: String,
                  consume: (NSRange) -> Bool) throws -> Bool {
            var completed = true
            expression.enumerateMatches(in: text, options: [.reportProgress, .reportCompletion],
                                        range: NSRange(location: 0, length: text.utf16.count)) { match, flags, stop in
                if isCancelled() || expired || flags.contains(.internalError) {
                    completed = false
                    stop.pointee = true
                } else if let match, !consume(match.range) {
                    stop.pointee = true
                }
            }
            try checkCancellation()
            return completed && !expired
        }
    }

    private struct Excerpts {
        let text: NSString
        private var line = 1
        private var start = 0
        private var end = 0
        private var next = 0

        init(_ text: String) {
            self.text = text as NSString
            readLine()
        }

        mutating func at(_ location: Int, budget: Budget) -> (line: Int, preview: String)? {
            let length = text.length
            while next > start && next <= location && (next < length || end < next) {
                start = next
                line += 1
                if line.isMultiple(of: 1_024), budget.isCancelled() || budget.expired { return nil }
                readLine()
            }
            var from = max(start, min(location, end) - 36)
            var to = min(end, from + 140)
            if from > start && isLowSurrogate(text.character(at: from)) { from -= 1 }
            if to < end && isLowSurrogate(text.character(at: to)) { to -= 1 }
            let excerpt = text.substring(with: NSRange(location: from, length: to - from))
            return (line, (from > start ? "…" : "") + excerpt + (to < end ? "…" : ""))
        }

        private mutating func readLine() {
            text.getLineStart(nil, end: &next, contentsEnd: &end, for: NSRange(location: start, length: 0))
        }

        private func isLowSurrogate(_ value: UInt16) -> Bool { (0xDC00...0xDFFF).contains(value) }
    }

    private static func glob(_ input: String, field: String) throws -> NSRegularExpression? {
        guard input.utf16.count <= maxExpression else { throw Failure.invalidGlob(field) }
        var parser = GlobParser(input)
        do {
            let patterns = try parser.paths()
            guard !patterns.isEmpty else { return nil }
            return try NSRegularExpression(pattern: #"\A(?:"# + patterns.joined(separator: "|") + #")(?:/.*)?\z"#,
                                           options: [.caseInsensitive, .dotMatchesLineSeparators])
        } catch { throw Failure.invalidGlob(field) }
    }

    private struct GlobParser {
        private struct Malformed: Error {}
        private let input: [Character]
        private var index = 0

        init(_ input: String) { self.input = Array(input) }

        mutating func paths() throws -> [String] {
            var patterns: [String] = []
            var start = 0
            var depth = 0
            while index < input.count {
                let character = input[index]
                if character == "\\" { index += 1 }
                if character == "[" { index = try rangeEnd() }
                if character == "{" { depth += 1 }
                if character == "}" { depth -= 1 }
                if character == ",", depth == 0 {
                    if let pattern = try Self.path(input[start..<index]) { patterns.append(pattern) }
                    start = index + 1
                }
                index += 1
            }
            if let pattern = try Self.path(input[start...]) { patterns.append(pattern) }
            return patterns
        }

        private static func path(_ characters: ArraySlice<Character>) throws -> String? {
            var path = String(characters).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !path.isEmpty else { return nil }
            let rooted = path.hasPrefix("./")
            if rooted { path.removeFirst(2) }
            if path.hasSuffix("/") { path.removeLast() }
            guard !path.isEmpty, !path.hasPrefix("/") else { throw Malformed() }
            var parser = GlobParser(path)
            return (rooted ? "(?:" : "(?:.*/)?(?:") + (try parser.expression()) + ")"
        }

        private mutating func expression() throws -> String {
            var regex = ""
            var depth = 0
            while index < input.count {
                let character = input[index]
                switch character {
                case "\\":
                    index += 1
                    guard index < input.count else { throw Malformed() }
                    regex += NSRegularExpression.escapedPattern(for: String(input[index]))
                case "*":
                    if index + 1 < input.count && input[index + 1] == "*" {
                        index += 1
                        if index + 1 < input.count && input[index + 1] == "/" {
                            index += 1
                            regex += "(?:.*/)?"
                        } else { regex += ".*" }
                    } else { regex += "[^/]*" }
                case "?": regex += "[^/]"
                case "[":
                    let end = try rangeEnd()
                    let negated = input[index + 1] == "!"
                    let start = index + (negated ? 2 : 1)
                    let range = input[start..<end].map { character in
                        #"\[]&^"#.contains(character) ? "\\\(character)" : String(character)
                    }.joined()
                    regex += "(?!/)[\(negated ? "^" : "")\(range)]"
                    index = end
                case "{": depth += 1; regex += "(?:"
                case "}":
                    guard depth > 0 else { throw Malformed() }
                    depth -= 1
                    regex += ")"
                case "," where depth > 0: regex += "|"
                default: regex += NSRegularExpression.escapedPattern(for: String(character))
                }
                index += 1
            }
            guard depth == 0 else { throw Malformed() }
            return regex
        }

        private func rangeEnd() throws -> Int {
            var start = index + 1
            if start < input.count && input[start] == "!" { start += 1 }
            guard start + 1 < input.count,
                  let end = input[(start + 1)...].firstIndex(of: "]") else { throw Malformed() }
            return end
        }
    }
}
