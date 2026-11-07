import Foundation

@main
enum FolderWorkspaceTests {
    static func main() throws {
        let fm = FileManager.default
        let scratch = fm.temporaryDirectory.appendingPathComponent("diyalamp-folder-\(UUID())", isDirectory: true)
        let root = scratch.appendingPathComponent("Workspace", isDirectory: true)
        let nested = root.appendingPathComponent("Nested", isDirectory: true)
        let outside = scratch.appendingPathComponent("Outside", isDirectory: true)
        try fm.createDirectory(at: nested, withIntermediateDirectories: true)
        defer { try? fm.removeItem(at: scratch) }
        try fm.createDirectory(at: outside, withIntermediateDirectories: false)
        for path in ["A 10.md", "A 2.md", "Nested/Note.MARKDOWN", ".Hidden.md", "Ignore.txt"] {
            try Data(path.utf8).write(to: root.appendingPathComponent(path))
        }
        let outsideNote = outside.appendingPathComponent("Note.MARKDOWN")
        try Data("external".utf8).write(to: outsideNote)
        try fm.createSymbolicLink(at: root.appendingPathComponent("Outside"), withDestinationURL: outside)
        try fm.createSymbolicLink(at: root.appendingPathComponent("Shortcut.md"), withDestinationURL: outsideNote)
        let workspace = try FolderWorkspace(opening: root)
        precondition(workspace.notes.map(\.relativePath) == ["A 2.md", "A 10.md", "Nested/Note.MARKDOWN"])
        let row = workspace.notes[2]
        let nestedNote = try workspace.checkedURL(for: row)
        precondition(workspace.index(of: nestedNote) == 2)
        precondition(workspace.index(of: outsideNote) == nil)
        let newNote = root.appendingPathComponent("Later.md")
        try Data().write(to: newNote)
        let refreshed = try workspace.refreshed()
        precondition(refreshed.index(of: newNote.resolvingSymlinksInPath()) == 2)

        try fm.removeItem(at: nested)
        try fm.createSymbolicLink(at: nested, withDestinationURL: outside)
        try expectFailure { _ = try workspace.checkedURL(for: row) }
        let safeRows = try workspace.refreshed()
        precondition(!safeRows.notes.contains { $0.relativePath.hasPrefix("Nested/") })

        let originalRoot = scratch.appendingPathComponent("Moved", isDirectory: true)
        try fm.moveItem(at: root, to: originalRoot)
        try fm.createSymbolicLink(at: root, withDestinationURL: outside)
        try expectFailure { _ = try workspace.refreshed() }
        try expectFailure { _ = try workspace.checkedURL(for: workspace.notes[0]) }
        print("PASS Native folder enumeration, refresh, and containment")
    }

    private static func expectFailure(_ body: () throws -> Void) throws {
        do { try body() } catch { return }
        preconditionFailure("The workspace accepted a path that now leads outside its root")
    }
}
