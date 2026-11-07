import Foundation

@main
enum WorkspaceSearchTests {
    static func main() throws {
        let scratch = FileManager.default.temporaryDirectory
            .appendingPathComponent("diyalamp-search-\(UUID())", isDirectory: true)
        try FileManager.default.createDirectory(at: scratch, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: scratch) }
        try matchesFilenamesAndText(in: scratch.appendingPathComponent("Workspace"))
        try filtersPaths(in: scratch.appendingPathComponent("Filters"))
        try skipsUnsafeAndOversizedNotes(in: scratch.appendingPathComponent("Safety"))
        try capsMatches(in: scratch.appendingPathComponent("Limits"))
        try cancelsLongRegex(in: scratch.appendingPathComponent("Cancellation"))
        print("PASS Native workspace search filenames, text, filters, limits, and cancellation.")
    }

    private static func matchesFilenamesAndText(in root: URL) throws {
        let content = "🐈 Cat and catch\r\nCafe\u{301} meets CAT.\rthird cat\nlast"
        let intro = try write("Intro.md", in: root, bytes: Data([0xEF, 0xBB, 0xBF]) + Data(content.utf8))
        try write("Nested/Cats.markdown", in: root, text: "A cat.")
        try write(".HiddenCat.md", in: root, text: "Cat hidden")
        try write("Cat.txt", in: root, text: "Cat other")
        let workspace = try FolderWorkspace(opening: root)

        let filename = try WorkspaceSearch.search("cat", in: workspace, options: .init())
        expect(filename.matches.map(\.relativePath) == ["Nested/Cats.markdown"])
        expect(filename.matches[0].lineNumber == nil && filename.matches[0].range == nil)

        let words = try WorkspaceSearch.search("cat", in: workspace, options: .init(mode: .text, wholeWord: true))
        let introMatches = words.matches.filter { $0.relativePath == "Intro.md" }
        expect(introMatches.map(\.lineNumber) == [1, 2, 3])
        expect(introMatches.map(\.matchedText) == ["Cat", "CAT", "cat"])
        expect(introMatches[0].range == NSRange(location: 3, length: 3))
        expect(introMatches[0].preview == "🐈 Cat and catch")
        expect(words.matches.count == 4 && words.skipped == 0 && !words.truncated)

        let exact = try WorkspaceSearch.search("CAT", in: workspace, options: .init(mode: .text, caseSensitive: true))
        expect(exact.matches.map(\.lineNumber) == [2])
        let accent = try WorkspaceSearch.search("Café", in: workspace, options: .init(mode: .text, wholeWord: true))
        expect(accent.matches.count == 1)
        expect(accent.matches[0].matchedText?.utf8.elementsEqual("Cafe\u{301}".utf8) == true)
        expect(accent.matches[0].range?.length == 5)

        let anchored = try WorkspaceSearch.search(#"^third\s+cat$"#, in: workspace,
            options: .init(mode: .text, regularExpression: true))
        expect(anchored.matches.map(\.lineNumber) == [3])
        let multiline = try WorkspaceSearch.search(#"catch\r\nCafe"#, in: workspace,
            options: .init(mode: .text, regularExpression: true))
        expect(multiline.matches.map(\.lineNumber) == [1])
        expect(multiline.matches[0].matchedText == "catch\r\nCafe")

        let draft = WorkspaceSearch.Overlay(url: intro.resolvingSymlinksInPath(), text: "New draft.\nCat draft.")
        let overlay = try WorkspaceSearch.search("draft", in: workspace, options: .init(mode: .text), overlay: draft)
        expect(overlay.matches.map(\.lineNumber) == [1, 2])
        let disk = try Data(contentsOf: intro)
        expect(disk == Data([0xEF, 0xBB, 0xBF]) + Data(content.utf8))
        let emptyDraft = WorkspaceSearch.Overlay(url: draft.url, text: "")
        let empty = try WorkspaceSearch.search("catch", in: workspace, options: .init(mode: .text), overlay: emptyDraft)
        expect(empty.matches.isEmpty)

        let badRegex = try errorMessage { _ = try WorkspaceSearch.search("(", in: workspace,
            options: .init(mode: .text, regularExpression: true)) }
        expect(badRegex.contains("regular expression"))
    }

    private static func filtersPaths(in root: URL) throws {
        for path in ["Intro.md", "Overview1.markdown", "Docs/Apple.md", "Docs/Cherry.md", "Docs/Nested/Berry.md",
                     "Other/Docs/Apple.md", "Other/Overview2.markdown", "Comma, File.md", "Square[Brackets].md"] {
            try write(path, in: root, text: "marker")
        }
        let workspace = try FolderWorkspace(opening: root)
        let filters = WorkspaceSearch.Options(mode: .text,
            include: "./{Docs/[AB]*.md,Intro.md}, **/Overview?.markdown",
            exclude: "./Other/, ./Docs/Nested/**")
        let result = try WorkspaceSearch.search("marker", in: workspace, options: filters)
        expect(result.matches.map(\.relativePath) == ["Docs/Apple.md", "Intro.md", "Overview1.markdown"])

        let unanchored = try WorkspaceSearch.search("marker", in: workspace,
            options: .init(mode: .text, include: "Docs/**", exclude: "Cherry.md"))
        expect(unanchored.matches.map(\.relativePath) == ["Docs/Apple.md", "Docs/Nested/Berry.md", "Other/Docs/Apple.md"])

        let escaped = try WorkspaceSearch.search("marker", in: workspace,
            options: .init(mode: .text, include: #"Comma\, File.md, Square\[Brackets\].md"#))
        expect(escaped.matches.map(\.relativePath) == ["Comma, File.md", "Square[Brackets].md"])
        let badInclude = try errorMessage { _ = try WorkspaceSearch.search("marker", in: workspace,
            options: .init(mode: .text, include: "[abc")) }
        let badExclude = try errorMessage { _ = try WorkspaceSearch.search("marker", in: workspace,
            options: .init(mode: .text, exclude: "{Docs,Other")) }
        expect(badInclude.contains("include") && badExclude.contains("exclude"))
    }

    private static func skipsUnsafeAndOversizedNotes(in root: URL) throws {
        let bad = try write("Broken.md", in: root, bytes: Data([0xFF]))
        let tooLarge = try write("Large.md", in: root, text: "marker")
        let handle = try FileHandle(forWritingTo: tooLarge)
        try handle.truncate(atOffset: UInt64(WorkspaceSearch.maxFileBytes + 1))
        try handle.close()
        let stale = try write("Old.md", in: root, text: "marker")
        let nested = try write("Nested/Note.md", in: root, text: "marker")
        try write("Safe.md", in: root, text: "marker")
        let outside = try write("Outside/Note.md", in: root.deletingLastPathComponent(), text: "marker")
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("Shortcut.md"), withDestinationURL: outside)
        let workspace = try FolderWorkspace(opening: root)
        try FileManager.default.removeItem(at: stale)
        try FileManager.default.createSymbolicLink(at: stale, withDestinationURL: outside)
        let folder = nested.deletingLastPathComponent()
        try FileManager.default.removeItem(at: folder)
        try FileManager.default.createSymbolicLink(at: folder, withDestinationURL: outside.deletingLastPathComponent())

        let result = try WorkspaceSearch.search("marker", in: workspace, options: .init(mode: .text))
        expect(result.matches.map(\.relativePath) == ["Safe.md"])
        expect(result.skipped == 4 && !result.truncated)
        let filename = try WorkspaceSearch.search("old", in: workspace, options: .init())
        expect(filename.matches.isEmpty)
        let unreadable = try Data(contentsOf: bad)
        expect(unreadable == Data([0xFF]))
    }

    private static func capsMatches(in root: URL) throws {
        let content = String(repeating: "marker\n", count: WorkspaceSearch.maxMatchesPerNote + 1)
        for index in 1...12 { try write("Note \(index).md", in: root, text: content) }
        let workspace = try FolderWorkspace(opening: root)
        let result = try WorkspaceSearch.search("marker", in: workspace, options: .init(mode: .text))
        expect(result.truncated && result.matches.count == WorkspaceSearch.maxResults)
        expect(result.matches.filter { $0.noteIndex == 0 }.count == WorkspaceSearch.maxMatchesPerNote)
    }

    private static func cancelsLongRegex(in root: URL) throws {
        try write("Slow.md", in: root, text: String(repeating: "a", count: 4_000) + "!")
        let workspace = try FolderWorkspace(opening: root)
        var checks = 0
        do {
            _ = try WorkspaceSearch.search("^(a+)+$", in: workspace,
                options: .init(mode: .text, regularExpression: true), isCancelled: {
                    checks += 1
                    return checks >= 30
                })
            preconditionFailure("Expected cancellation during regular expression backtracking")
        } catch is CancellationError {
            expect(checks >= 30)
        }
    }

    @discardableResult
    private static func write(_ path: String, in root: URL, text: String) throws -> URL {
        try write(path, in: root, bytes: Data(text.utf8))
    }

    @discardableResult
    private static func write(_ path: String, in root: URL, bytes: Data) throws -> URL {
        let url = root.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try bytes.write(to: url)
        return url
    }

    private static func errorMessage(_ action: () throws -> Void) throws -> String {
        do { try action() }
        catch { return error.localizedDescription }
        preconditionFailure("Expected an invalid search error")
    }

    private static func expect(_ condition: @autoclosure () -> Bool, line: UInt = #line) {
        precondition(condition(), "Search assertion failed at line \(line)")
    }
}
