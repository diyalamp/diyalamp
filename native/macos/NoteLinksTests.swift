import Foundation

@main
enum NoteLinksTests {
    static func main() throws {
        parseLinks()
        try indexWorkspace()
        try limitsAndCancellation()
        print("PASS Native linked-note syntax, resolution, backlinks, overlays, and safety.")
    }

    private static func parseLinks() {
        let raw = #"""
        日本語 🪔 [[First]] [[Second#Section|label]] [Markdown](nested/Third.md)
        `[[Inline]]` \[[Escaped]] and ![picture](Photo.md)

        ```md
        [[Fenced]] [No](Fenced.md)
        ```

            [[Indented]]

        <!-- [[Comment]] -->

        \\[[Even]] [[Last]]
        [**Formatted**](Target.md) [reference][id] [[Last]]

        [id]: Referenced.md
        """#
        let markdown = raw.replacingOccurrences(of: "\n", with: "\r\n")
        let links = NoteLinks.occurrences(markdown)
        let wiki = links.filter { $0.target.wiki }
        let local = links.filter { !$0.target.wiki }
        precondition(wiki.map(\.target.destination) == ["First", "Second#Section|label", "Even", "Last", "Last"],
                     "Unexpected wiki tokens: \(wiki.map(\.target.destination))")
        precondition(local.map(\.target.destination) == ["nested/Third.md", "Target.md", "Referenced.md"])
        for link in wiki {
            guard let range = link.sourceRange else { preconditionFailure("Wiki source range missing") }
            precondition((markdown as NSString).substring(with: range) == "[[\(link.target.destination)]]")
        }
        for (link, token) in zip(local, ["[Markdown](nested/Third.md)", "[**Formatted**](Target.md)", "[reference][id]"]) {
            guard let range = link.sourceRange else { preconditionFailure("Markdown source range missing") }
            precondition((markdown as NSString).substring(with: range) == token)
        }
        let bom = "\u{feff}[[First]]"
        precondition(NoteLinks.occurrences(bom).first?.sourceRange == NSRange(location: 1, length: 9))
        precondition(NoteLinks.targets("> > [[Quoted]]\n\n- [[Listed]]").map(\.destination) == ["Quoted", "Listed"])
        precondition(NoteLinks.targets("[[Prefix]] and `[[Literal]]` and [[Suffix]]").map(\.destination)
                     == ["Prefix", "Suffix"])
    }

    private static func indexWorkspace() throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        let notes = try directory("notes", inside: root)
        let nested = try directory("nested", inside: notes)
        let other = try directory("other", inside: notes)
        let secret = try directory(".secret", inside: notes)
        let markdown = "[[Target]] [[nested/Child#section|label]] [Space](Space%20name.md#part) [Plus](A+B.md)\n"
            + "[external](https://example.com) [[Missing]] [[Duplicate]] [[Target]]"
        let source = try file("Origin.md", markdown, inside: notes)
        let target = try file("Target.md", "[[Origin]]", inside: notes)
        let child = try file("Child.md", "[[Target]] [up](../Origin.md)", inside: nested)
        let space = try file("Space name.md", "", inside: notes)
        _ = try file("A+B.md", "", inside: notes)
        _ = try file("Duplicate.md", "", inside: notes)
        _ = try file("Duplicate.md", "", inside: nested)
        let ambiguous = try file("Ambiguous.md", "[[Duplicate]]", inside: other)
        let hidden = try file("Hidden.md", "[[Origin]]", inside: secret)
        let outside = try file("Outside.md", "[[Origin]]", inside: root)
        try FileManager.default.createSymbolicLink(at: notes.appendingPathComponent("Escape.md"), withDestinationURL: outside)
        try FileManager.default.createSymbolicLink(at: notes.appendingPathComponent("alias"), withDestinationURL: nested)
        let workspace = try FolderWorkspace(opening: notes)
        let snapshot = try NoteLinks.scan(workspace)
        precondition(snapshot.outgoing("Origin.md") == ["A+B.md", "Duplicate.md", "Space name.md", "Target.md", "nested/Child.md"])
        precondition(snapshot.incoming("Target.md") == ["Origin.md", "nested/Child.md"])
        precondition(snapshot.outgoing("Target.md") == ["Origin.md"])
        precondition(snapshot.outgoing("nested/Child.md") == ["Origin.md", "Target.md"])
        precondition(snapshot.unresolved("Origin.md") == 1 && !snapshot.partial)
        precondition(snapshot.outgoing("other/Ambiguous.md").isEmpty && snapshot.unresolved("other/Ambiguous.md") == 1)
        let sourceBytes = try Data(contentsOf: source)
        precondition(sourceBytes == Data(markdown.utf8))

        func resolve(_ destination: String, from note: URL = source, wiki: Bool = true) -> String? {
            NoteLinks.resolve(.init(destination: destination, wiki: wiki), from: note, in: workspace)?.relativePath
        }
        precondition(resolve("Duplicate", from: child) == "nested/Duplicate.md")
        precondition(resolve("Target#Heading|Title", from: child) == "Target.md")
        precondition(resolve("nested/Child", from: ambiguous) == "nested/Child.md")
        precondition(resolve("Space%20name.md?test#part", wiki: false) == "Space name.md")
        precondition(resolve("A+B.md", wiki: false) == "A+B.md")
        precondition(resolve("Target", wiki: false) == nil)
        for unsafe in ["../Outside.md", "https://example.com/A.md", "file:///etc/passwd", "/etc/passwd",
                       ".secret/Hidden", "Escape.md", "alias/Child.md", "nested/../../Outside.md"] {
            precondition(resolve(unsafe) == nil, "Unsafe target \(unsafe) resolved")
        }
        precondition(resolve("%2E%2E/Outside.md", wiki: false) == nil)
        precondition(resolve("Target", from: hidden) == nil && resolve("notes/Target", from: outside) == nil)

        let overlay = NoteLinks.Overlay(url: source, text: "[[Origin]] [[Target]] [[New draft]]")
        let active = try NoteLinks.activeLinks(overlay, in: workspace)
        precondition(active.paths == ["Origin.md", "Target.md"] && active.unresolved == 1 && !active.partial)
        let live = try NoteLinks.scan(workspace, overlay: overlay)
        precondition(live.outgoing("Origin.md") == active.paths)
        precondition(live.incoming("Origin.md") == ["Origin.md", "Target.md", "nested/Child.md"])
        precondition(live.incoming("Duplicate.md").isEmpty)
        let stillOnDisk = try Data(contentsOf: source)
        precondition(stillOnDisk == sourceBytes)

        try Data("[[Target]]".utf8).write(to: target)
        let refreshed = try NoteLinks.scan(workspace)
        precondition(refreshed.incoming("Origin.md") == ["nested/Child.md"])
        precondition(refreshed.incoming("Target.md") == ["Origin.md", "Target.md", "nested/Child.md"])

        try FileManager.default.removeItem(at: space)
        try FileManager.default.createSymbolicLink(at: space, withDestinationURL: outside)
        precondition(resolve("Space%20name.md", wiki: false) == nil)
        let afterReplacement = try NoteLinks.scan(workspace)
        precondition(afterReplacement.partial && !afterReplacement.outgoing("Origin.md").contains("Space name.md"))
        precondition(afterReplacement.outgoing("Space name.md").isEmpty)
    }

    private static func limitsAndCancellation() throws {
        let root = try temporaryFolder()
        defer { try? FileManager.default.removeItem(at: root) }
        let small = try file("Good.md", "[[Large]]", inside: root)
        _ = try file("Large.md", String(repeating: "x", count: NoteLinks.maxNoteBytes + 1), inside: root)
        try Data([0xc3, 0x28]).write(to: root.appendingPathComponent("Invalid.md"))
        let workspace = try FolderWorkspace(opening: root)
        let result = try NoteLinks.scan(workspace)
        precondition(result.partial)
        precondition(result.outgoing("Good.md") == ["Large.md"])
        let huge = NoteLinks.Overlay(url: small, text: String(repeating: "x", count: NoteLinks.maxNoteBytes + 1))
        let active = try NoteLinks.activeLinks(huge, in: workspace)
        precondition(active.partial && active.paths.isEmpty)
        do {
            _ = try NoteLinks.scan(workspace, isCancelled: { true })
            preconditionFailure("Expected cancelled scan")
        } catch is CancellationError { }
        do {
            _ = try NoteLinks.activeLinks(huge, in: workspace, isCancelled: { true })
            preconditionFailure("Expected cancelled overlay")
        } catch is CancellationError { }
    }

    private static func temporaryFolder() throws -> URL {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("diyalamp-link-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        return root.resolvingSymlinksInPath()
    }

    private static func directory(_ name: String, inside parent: URL) throws -> URL {
        let url = parent.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false)
        return url
    }

    private static func file(_ name: String, _ markdown: String, inside parent: URL) throws -> URL {
        let url = parent.appendingPathComponent(name, isDirectory: false)
        try Data(markdown.utf8).write(to: url)
        return url.resolvingSymlinksInPath()
    }
}
