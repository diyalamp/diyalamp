import AppKit
import CoreText
import UniformTypeIdentifiers

enum NoteError: LocalizedError {
    case invalidUTF8, externalChange, recoveryNeedsNewFile

    var errorDescription: String? {
        switch self {
        case .invalidUTF8: "This note is not valid UTF-8 Markdown."
        case .externalChange: "The file changed on disk. Your editor text is still open."
        case .recoveryNeedsNewFile: "Choose a different file for the recovered changes."
        }
    }
}

final class NoteFile {
    private static let utf8BOM = Data([0xEF, 0xBB, 0xBF])
    let url: URL
    private let byteOrderMark: Data
    private let checkedAccess: (() throws -> URL)?
    private(set) var original: Data
    private(set) var text: String

    static func create(at url: URL) throws {
        try Data().write(to: url, options: .withoutOverwriting)
    }

    init(_ url: URL, checkedAccess: (() throws -> URL)? = nil) throws {
        self.url = url
        self.checkedAccess = checkedAccess
        original = try Data(contentsOf: url)
        byteOrderMark = original.starts(with: Self.utf8BOM) ? Self.utf8BOM : Data()
        guard let text = String(validating: original.dropFirst(byteOrderMark.count), as: UTF8.self) else {
            throw NoteError.invalidUTF8
        }
        self.text = text
    }

    func save(_ edited: String) throws {
        let url = try checkedAccess?() ?? self.url
        guard try Data(contentsOf: url) == original else { throw NoteError.externalChange }
        // Preserve the original bytes when opening and saving an untouched note.
        guard Data(edited.utf8) != Data(text.utf8) else { return }
        let bytes = byteOrderMark + Data(edited.utf8)
        try bytes.write(to: url, options: .atomic)
        original = bytes
        text = edited
    }

    static func recoveredBytes(_ draft: RecoverySnapshot) -> Data {
        let bom = draft.original.starts(with: utf8BOM) ? utf8BOM : Data()
        return bom + Data(draft.edited.utf8)
    }
}

struct RecoverySmoke {
    enum Phase: String { case edit, restore, conflict, saved }
    let phase: Phase
    let noteURL: URL
}

let foreground = NSColor(srgbRed: 0.82, green: 0.82, blue: 0.82, alpha: 1)
let surface = NSColor(srgbRed: 17.0 / 255, green: 18.0 / 255, blue: 19.0 / 255, alpha: 1)

func noteFont(_ size: CGFloat, strong: Bool = false, italic: Bool = false) -> NSFont {
    let face = strong ? (italic ? "Inter-SemiBoldItalic" : "Inter-SemiBold")
                      : (italic ? "Inter-Italic" : "Inter-Regular")
    return NSFont(name: face, size: size) ?? .systemFont(ofSize: size, weight: strong ? .semibold : .regular)
}

@MainActor
private final class WorkspaceFilesTable: NSTableView {
    var activateSelection: (() -> Bool)?

    override func keyDown(with event: NSEvent) {
        if (event.keyCode == 36 || event.keyCode == 76), activateSelection?() == true { return }
        super.keyDown(with: event)
    }
}

@MainActor
final class Workspace: NSObject, NSApplicationDelegate, NSTextViewDelegate, NSWindowDelegate,
    NSMenuItemValidation, NSTableViewDataSource, NSTableViewDelegate {
    private let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1160, height: 620),
        styleMask: [.titled, .closable, .miniaturizable, .resizable], backing: .buffered, defer: false)
    private let editorScroll = NSTextView.scrollableTextView()
    private let previewScroll = NSTextView.scrollableTextView()
    private let folderTable = WorkspaceFilesTable()
    private lazy var searchControls = WorkspaceSearchControls()
    private lazy var connectionsView = ConnectionsView()
    private let workspaceSplit = NSSplitView()
    private var connectionsPane: NSView?
    private var connectionsWidth: CGFloat = 230
    private var editor: NSTextView { editorScroll.documentView as! NSTextView }
    private var preview: NSTextView { previewScroll.documentView as! NSTextView }
    private let status = NSTextField(labelWithString: "Open a Markdown note")
    private let history = UndoManager()
    private struct Snapshot {
        let source: String
        let rawSelection: NSRange
        let renderedSelection: NSRange
    }

    private var source = ""
    private var projection = CmarkDocument(source: "")
    private var rawSelectionBeforeChange: NSRange?
    private var note: NoteFile?
    private var folder: FolderWorkspace?
    private var updatingFolderSelection = false
    private var searchTask: Task<Void, Never>?
    private var searchRows: [WorkspaceSearch.Match]?
    private var openWhenSearchCompletes = false
    private var linkSnapshot: NoteLinks.Snapshot?
    private var linkIndexTask: Task<Void, Never>?
    private var activeLinksTask: Task<Void, Never>?
    private var displayedConnectionsPath: String?
    private var windowIsClosing = false
    private let fixture: URL?
    private let recoveryStore: RecoveryStore?
    private let recoverySmoke: RecoverySmoke?
    private var recoveryLease: RecoveryLease?
    private var recoveryCopy: RecoverySnapshot?
    private var recoveryScheduled = false
    private enum EditDecision { case save, discard, cancel }

    init(fixture: URL?, recoveryStore: RecoveryStore?, recoverySmoke: RecoverySmoke?) {
        self.fixture = fixture
        self.recoveryStore = recoveryStore
        self.recoverySmoke = recoverySmoke
        super.init()
        history.groupsByEvent = false
        history.levelsOfUndo = 64
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.appearance = NSAppearance(named: .darkAqua)
        registerFonts()
        configureMenus()
        configureWindow()
        window.center()
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(preview)
        NSApp.activate()
        do {
            recoveryLease = try recoveryStore?.lock()
            try restoreRecovery()
        } catch {
            if recoverySmoke == nil { present(error) }
            else { fputs("FAIL recovery setup: \(error)\n", stderr) }
            exit(EXIT_FAILURE)
        }
        if let recoverySmoke {
            DispatchQueue.main.async { self.runRecoverySmoke(recoverySmoke) }
        } else if let fixture {
            DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                Task { await self.runSmoke(fixture) }
            }
        }
    }

    private func registerFonts() {
        for name in ["Inter-Regular", "Inter-Italic", "Inter-SemiBold", "Inter-SemiBoldItalic"] {
            if let url = Bundle.main.url(forResource: name, withExtension: "ttf", subdirectory: "Fonts") {
                CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
            }
        }
    }

    private func configureMenus() {
        let bar = NSMenu()
        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "About diyalamp", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)),
                        keyEquivalent: "")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Quit diyalamp", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        bar.addItem(appItem)
        let fileItem = NSMenuItem()
        let fileMenu = NSMenu(title: "File")
        fileMenu.addItem(withTitle: "New Note…", action: #selector(newNote), keyEquivalent: "n").target = self
        fileMenu.addItem(withTitle: "Open Note…", action: #selector(openNote), keyEquivalent: "o").target = self
        let openFolderItem = fileMenu.addItem(withTitle: "Open Folder…", action: #selector(openFolder), keyEquivalent: "o")
        openFolderItem.keyEquivalentModifierMask = [.command, .shift]
        openFolderItem.target = self
        fileMenu.addItem(withTitle: "Save", action: #selector(saveNote), keyEquivalent: "s").target = self
        fileItem.submenu = fileMenu
        bar.addItem(fileItem)
        let editItem = NSMenuItem()
        let editMenu = NSMenu(title: "Edit")
        editMenu.addItem(withTitle: "Undo", action: #selector(undoEdit), keyEquivalent: "z").target = self
        let redo = editMenu.addItem(withTitle: "Redo", action: #selector(redoEdit), keyEquivalent: "z")
        redo.keyEquivalentModifierMask = [.command, .shift]
        redo.target = self
        editMenu.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        editMenu.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        editMenu.addItem(.separator())
        editMenu.addItem(withTitle: "Find File in Folder…", action: #selector(focusFilenameSearch),
                         keyEquivalent: "p").target = self
        let textSearch = editMenu.addItem(withTitle: "Find Text in Folder…", action: #selector(focusTextSearch),
                                          keyEquivalent: "f")
        textSearch.keyEquivalentModifierMask = [.command, .option]
        textSearch.target = self
        editItem.submenu = editMenu
        bar.addItem(editItem)
        NSApp.mainMenu = bar
    }

    private func configureWindow() {
        window.title = "diyalamp"
        window.minSize = NSSize(width: 760, height: 380)
        window.isReleasedWhenClosed = false
        window.delegate = self
        for view in [editor, preview] {
            view.font = noteFont(16)
            view.backgroundColor = surface
            view.textColor = foreground
            view.textContainerInset = NSSize(width: 16, height: 16)
            view.isAutomaticQuoteSubstitutionEnabled = false
            view.isAutomaticDashSubstitutionEnabled = false
            view.isAutomaticLinkDetectionEnabled = false
            view.allowsUndo = false
        }
        editor.isRichText = false
        editor.isEditable = false
        editor.delegate = self
        editor.setAccessibilityLabel("Markdown source")
        preview.isEditable = false
        preview.delegate = self
        preview.setAccessibilityLabel("Rendered Markdown editor")

        let split = workspaceSplit
        if let bounds = window.contentView?.bounds { split.frame = bounds }
        split.isVertical = true
        split.dividerStyle = .thin
        searchControls.onChange = { [weak self] in self?.refreshSearch() }
        searchControls.onSubmit = { [weak self] in self?.openFirstSearchHit() }
        searchControls.setFolderAvailable(false)
        let files = pane("Files", makeFolderList(), controls: searchControls)
        let raw = pane("Markdown source", editorScroll)
        let rendered = pane("Rendered editor", previewScroll)
        connectionsView.onOpen = { [weak self] path in self?.openConnection(path) }
        let refresh = toolbarIcon("arrow.clockwise", label: "Refresh connections", action: #selector(rescanConnections))
        let connections = pane("Connections", connectionsView, headingAction: refresh)
        connectionsPane = connections
        let filesWidth: CGFloat = 220
        let editorWidth = (split.bounds.width - filesWidth - connectionsWidth - split.dividerThickness * 3) / 2
        var origin: CGFloat = 0
        for (pane, width) in [(files, filesWidth), (raw, editorWidth), (rendered, editorWidth),
                              (connections, connectionsWidth)] {
            pane.frame = NSRect(x: origin, y: 0, width: width, height: split.bounds.height)
            split.addArrangedSubview(pane)
            origin += width + split.dividerThickness
        }
        split.setHoldingPriority(.defaultHigh, forSubviewAt: 0)
        split.setHoldingPriority(.defaultHigh, forSubviewAt: 3)
        let new = NSButton(title: "New Note…", target: self, action: #selector(newNote))
        let open = NSButton(title: "Open Note…", target: self, action: #selector(openNote))
        let folderButton = NSButton(title: "Open Folder…", target: self, action: #selector(openFolder))
        let save = NSButton(title: "Save", target: self, action: #selector(saveNote))
        let spacer = NSView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let toggle = toolbarIcon("sidebar.right", label: "Hide Connections", action: #selector(toggleConnections(_:)))
        status.lineBreakMode = .byTruncatingMiddle
        status.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        toggle.setContentCompressionResistancePriority(.required, for: .horizontal)
        let toolbar = NSStackView(views: [folderButton, new, open, save, spacer, status, toggle])
        toolbar.edgeInsets = NSEdgeInsets(top: 8, left: 12, bottom: 8, right: 12)

        let content = NSView()
        toolbar.translatesAutoresizingMaskIntoConstraints = false
        split.translatesAutoresizingMaskIntoConstraints = false
        content.addSubview(toolbar)
        content.addSubview(split)
        NSLayoutConstraint.activate([
            toolbar.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            toolbar.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            toolbar.topAnchor.constraint(equalTo: content.topAnchor),
            split.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            split.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            split.topAnchor.constraint(equalTo: toolbar.bottomAnchor),
            split.bottomAnchor.constraint(equalTo: content.bottomAnchor),
        ])
        window.contentView = content
        window.layoutIfNeeded()
        split.adjustSubviews()
        updateConnections()
    }

    private func toolbarIcon(_ symbol: String, label: String, action: Selector) -> NSButton {
        let button = NSButton(image: NSImage(systemSymbolName: symbol, accessibilityDescription: nil) ?? NSImage(),
                              target: self, action: action)
        button.bezelStyle = .rounded
        button.toolTip = label
        button.setAccessibilityLabel(label)
        return button
    }

    private func pane(_ title: String, _ content: NSView, controls: NSView? = nil,
                      headingAction: NSView? = nil) -> NSView {
        let titleLabel = NSTextField(labelWithString: title)
        titleLabel.font = noteFont(13, strong: true)
        let heading = NSStackView(views: [titleLabel])
        if let headingAction {
            let spacer = NSView()
            spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
            heading.addArrangedSubview(spacer)
            heading.addArrangedSubview(headingAction)
        }
        heading.alignment = .centerY
        heading.edgeInsets = NSEdgeInsets(top: 0, left: 16, bottom: 0, right: 16)
        heading.heightAnchor.constraint(equalToConstant: 42).isActive = true
        let pane = NSStackView(views: [heading, controls, content].compactMap { $0 })
        pane.orientation = .vertical
        pane.alignment = .width
        pane.spacing = 0
        return pane
    }

    private func load(_ url: URL) throws {
        install(try NoteFile(url))
    }

    private func install(_ file: NoteFile) {
        let discardedOverlay = note != nil && hasUnsavedChanges
        note = file
        recoveryCopy = nil
        editor.isEditable = true
        replaceDocument(with: file.text)
        history.removeAllActions()
        rawSelectionBeforeChange = nil
        status.stringValue = file.url.lastPathComponent
        window.isDocumentEdited = false
        syncFolderSelection()
        if discardedOverlay { refreshTextSearch() }
        updateConnections()
    }

    @objc private func openNote() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "md"), UTType(filenameExtension: "markdown")].compactMap { $0 }
        panel.directoryURL = folder?.root
        guard panel.runModal() == .OK, let selection = panel.url else { return }
        do {
            let url = selection.standardizedFileURL.resolvingSymlinksInPath()
            if let folder, let row = folder.index(of: url) {
                try activateFolderNote(row, in: folder)
            } else if url != note?.url {
                let file = try NoteFile(url)
                if try resolvePendingEdits() { try finishSwitch(to: file) }
            }
        } catch { present(error) }
    }

    @objc private func openFolder() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.directoryURL = folder?.root ?? note?.url.deletingLastPathComponent()
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do { showFolder(try FolderWorkspace(opening: url)) } catch { present(error) }
    }

    private func makeFolderList() -> NSScrollView {
        folderTable.style = .sourceList
        folderTable.headerView = nil
        folderTable.rowHeight = 26
        folderTable.autoresizingMask = [.width]
        folderTable.columnAutoresizingStyle = .lastColumnOnlyAutoresizingStyle
        folderTable.addTableColumn(NSTableColumn(identifier: NSUserInterfaceItemIdentifier("note")))
        folderTable.dataSource = self
        folderTable.delegate = self
        folderTable.target = self
        folderTable.doubleAction = #selector(openSelectedSearchHit)
        folderTable.activateSelection = { [weak self] in
            guard let self, self.searchRows != nil else { return false }
            self.openSelectedSearchHit()
            return true
        }
        folderTable.backgroundColor = surface
        folderTable.setAccessibilityLabel("Markdown files")
        let scroll = NSScrollView()
        scroll.documentView = folderTable
        scroll.hasVerticalScroller = true
        return scroll
    }

    func numberOfRows(in tableView: NSTableView) -> Int { searchRows?.count ?? folder?.notes.count ?? 0 }

    func tableView(_ tableView: NSTableView, viewFor tableColumn: NSTableColumn?, row: Int) -> NSView? {
        if let searchRows, searchRows.indices.contains(row) {
            let match = searchRows[row]
            guard let lineNumber = match.lineNumber else { return pathLabel(match.relativePath) }
            let location = pathLabel("\(match.relativePath):\(lineNumber)")
            let preview = pathLabel(match.preview)
            preview.textColor = .secondaryLabelColor
            preview.lineBreakMode = .byTruncatingTail
            let result = NSStackView(views: [location, preview])
            result.orientation = .vertical
            result.alignment = .width
            result.spacing = 2
            return result
        }
        guard searchRows == nil, let folder, folder.notes.indices.contains(row) else { return nil }
        return pathLabel(folder.notes[row].relativePath)
    }

    private func pathLabel(_ path: String) -> NSTextField {
        let label = NSTextField(labelWithString: path)
        label.font = noteFont(13)
        label.lineBreakMode = .byTruncatingMiddle
        label.toolTip = path
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return label
    }

    func tableViewSelectionDidChange(_ notification: Notification) {
        guard searchRows == nil, !updatingFolderSelection, let folder,
              folder.notes.indices.contains(folderTable.selectedRow) else {
            return
        }
        do { try activateFolderNote(folderTable.selectedRow, in: folder) } catch { present(error) }
    }

    private func showFolder(_ folder: FolderWorkspace) {
        self.folder = folder
        folderTable.toolTip = folder.root.path
        searchControls.setFolderAvailable(true)
        refreshSearch()
        invalidateConnections()
        updateConnections()
    }

    private func syncFolderSelection() {
        guard searchRows == nil else { return }
        updatingFolderSelection = true
        defer { updatingFolderSelection = false }
        if let note, let row = folder?.index(of: note.url) {
            folderTable.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false)
            folderTable.scrollRowToVisible(row)
        } else {
            folderTable.deselectAll(nil)
        }
    }

    @objc private func toggleConnections(_ sender: NSButton) {
        guard let connectionsPane, workspaceSplit.arrangedSubviews.count >= 3 else { return }
        let rendered = workspaceSplit.arrangedSubviews[2]
        let rightEdge = workspaceSplit.bounds.maxX
        let wasOpen = connectionsPane.superview === workspaceSplit
        if wasOpen {
            if connectionsPane.frame.width > 0 { connectionsWidth = connectionsPane.frame.width }
            workspaceSplit.removeArrangedSubview(connectionsPane)
            connectionsPane.removeFromSuperview()
            rendered.frame.size.width = rightEdge - rendered.frame.minX
        } else {
            let divider = workspaceSplit.dividerThickness
            let available = rightEdge - rendered.frame.minX
            let width = min(connectionsWidth, max(0, available - divider - 100))
            rendered.frame.size.width = available - divider - width
            connectionsPane.frame = NSRect(x: rendered.frame.maxX + divider, y: 0, width: width,
                                           height: workspaceSplit.bounds.height)
            workspaceSplit.addArrangedSubview(connectionsPane)
            workspaceSplit.setHoldingPriority(.defaultHigh, forSubviewAt: 3)
        }
        workspaceSplit.adjustSubviews()
        let label = wasOpen ? "Show Connections" : "Hide Connections"
        sender.toolTip = label
        sender.setAccessibilityLabel(label)
        updateConnections()
    }

    @objc private func rescanConnections() {
        invalidateConnections()
        updateConnections(refreshFolder: true)
    }

    private func invalidateConnections() {
        linkIndexTask?.cancel()
        linkIndexTask = nil
        activeLinksTask?.cancel()
        activeLinksTask = nil
        linkSnapshot = nil
        displayedConnectionsPath = nil
    }

    private func updateConnections(refreshFolder: Bool = false) {
        activeLinksTask?.cancel()
        activeLinksTask = nil
        guard connectionsPane?.superview === workspaceSplit else { return }
        guard let folder, let note, let row = folder.index(of: note.url) else {
            displayedConnectionsPath = nil
            connectionsView.setMessage("Open a note in the folder to see connections")
            return
        }
        let path = folder.notes[row].relativePath
        guard let snapshot = linkSnapshot else {
            connectionsView.setMessage("Finding connected notes…")
            startLinkIndex(in: folder, refreshFolder: refreshFolder)
            return
        }
        let previousPath = displayedConnectionsPath
        displayedConnectionsPath = path
        guard hasUnsavedChanges else {
            connectionsView.show(incoming: snapshot.incoming(path), outgoing: snapshot.outgoing(path),
                                 unresolved: snapshot.unresolved(path), partial: snapshot.partial)
            return
        }
        if previousPath != path { connectionsView.setMessage("Updating connections…") }
        let overlay = NoteLinks.Overlay(url: note.url, text: source)
        activeLinksTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .milliseconds(240))
                let worker = Task.detached(priority: .utility) {
                    try NoteLinks.activeLinks(overlay, in: folder, isCancelled: { Task.isCancelled })
                }
                let result = try await withTaskCancellationHandler {
                    try await worker.value
                } onCancel: { worker.cancel() }
                try Task.checkCancellation()
                guard let self else { return }
                self.activeLinksTask = nil
                var incoming = snapshot.incoming(path).filter { $0 != path }
                if result.paths.contains(path) { incoming.append(path); incoming.sort() }
                self.connectionsView.show(incoming: incoming, outgoing: result.paths,
                                          unresolved: result.unresolved, partial: snapshot.partial || result.partial)
            } catch is CancellationError {
                return
            } catch {
                if !Task.isCancelled { self?.connectionsView.setMessage(error.localizedDescription) }
            }
        }
    }

    private func startLinkIndex(in folder: FolderWorkspace, refreshFolder: Bool) {
        guard linkIndexTask == nil else { return }
        linkIndexTask = Task { [weak self] in
            do {
                let worker = Task.detached(priority: .utility) {
                    let workspace = try refreshFolder ? folder.refreshed(isCancelled: { Task.isCancelled }) : folder
                    let snapshot = try NoteLinks.scan(workspace, isCancelled: { Task.isCancelled })
                    return (workspace, snapshot)
                }
                let (workspace, snapshot) = try await withTaskCancellationHandler {
                    try await worker.value
                } onCancel: { worker.cancel() }
                try Task.checkCancellation()
                guard let self else { return }
                self.linkIndexTask = nil
                self.linkSnapshot = snapshot
                if refreshFolder {
                    self.folder = workspace
                    if self.searchRows == nil { self.showSearchRows(nil, message: "") }
                    else { self.refreshSearch() }
                }
                self.updateConnections()
            } catch is CancellationError {
                return
            } catch {
                if !Task.isCancelled {
                    self?.linkIndexTask = nil
                    self?.connectionsView.setMessage(error.localizedDescription)
                }
            }
        }
    }

    private func openConnection(_ path: String) {
        do { try activateConnection(path) } catch { present(error) }
    }

    private func activateConnection(_ path: String, deciding: (() -> EditDecision)? = nil) throws {
        guard let folder, let index = folder.notes.firstIndex(where: { $0.relativePath == path }) else {
            status.stringValue = "Linked note is no longer in the folder"
            return
        }
        let destination = try folder.checkedURL(for: folder.notes[index])
        try activateFolderNote(index, in: folder, deciding: deciding)
        if note?.url == destination { window.makeFirstResponder(preview.isEditable ? preview : editor) }
    }

    func textView(_ textView: NSTextView, clickedOnLink link: Any, at charIndex: Int) -> Bool {
        guard textView === preview, let destination = RenderedWikiLink.destination(from: link) else { return false }
        if let folder, let note,
           let entry = NoteLinks.resolve(.init(destination: destination, wiki: true), from: note.url, in: folder) {
            openConnection(entry.relativePath)
        } else {
            status.stringValue = "Linked note not found in this folder"
        }
        return true
    }

    @objc private func focusFilenameSearch() { focusSearch(.filenames) }
    @objc private func focusTextSearch() { focusSearch(.text) }

    private func focusSearch(_ mode: WorkspaceSearch.Mode) {
        guard folder != nil else { return }
        searchControls.setMode(mode)
        window.makeFirstResponder(searchControls.field)
        searchControls.field.selectText(nil)
    }

    private func refreshTextSearch() {
        if searchControls.options.mode == .text && !searchControls.field.stringValue.isEmpty { refreshSearch() }
    }

    private func refreshSearch() {
        searchTask?.cancel()
        searchTask = nil
        openWhenSearchCompletes = false
        let query = searchControls.field.stringValue
        guard let folder, !query.isEmpty else {
            showSearchRows(nil, message: "")
            return
        }
        let options = searchControls.options
        let overlay = hasUnsavedChanges ? note.map { WorkspaceSearch.Overlay(url: $0.url, text: source) } : nil
        showSearchRows([], message: "Searching…")
        searchTask = Task { [weak self] in
            do {
                try await Task.sleep(for: .milliseconds(180))
                let worker = Task.detached(priority: .userInitiated) {
                    let refreshed = try folder.refreshed(isCancelled: { Task.isCancelled })
                    let result = try WorkspaceSearch.search(query, in: refreshed, options: options,
                        overlay: overlay, isCancelled: { Task.isCancelled })
                    return (refreshed, result)
                }
                let (refreshed, result) = try await withTaskCancellationHandler {
                    try await worker.value
                } onCancel: { worker.cancel() }
                try Task.checkCancellation()
                guard let self else { return }
                self.searchTask = nil
                let folderChanged = self.folder?.notes.map(\.relativePath) != refreshed.notes.map(\.relativePath)
                self.folder = refreshed
                if folderChanged {
                    self.invalidateConnections()
                    self.updateConnections()
                }
                let count = result.matches.count
                var message = count == 0 ? "No matches" : "\(count) match\(count == 1 ? "" : "es")"
                if result.truncated { message += " · Refine search for more" }
                if result.skipped > 0 { message += " · \(result.skipped) skipped" }
                self.showSearchRows(result.matches, message: message)
                if self.openWhenSearchCompletes {
                    self.openWhenSearchCompletes = false
                    self.openFirstSearchHit()
                }
            } catch is CancellationError {
                return
            } catch {
                if !Task.isCancelled {
                    self?.searchTask = nil
                    self?.openWhenSearchCompletes = false
                    self?.showSearchRows([], message: error.localizedDescription)
                }
            }
        }
    }

    private func showSearchRows(_ rows: [WorkspaceSearch.Match]?, message: String) {
        updatingFolderSelection = true
        searchRows = rows
        folderTable.deselectAll(nil)
        folderTable.rowHeight = rows?.first?.lineNumber == nil ? 26 : 44
        folderTable.reloadData()
        updatingFolderSelection = false
        searchControls.setMessage(message)
        syncFolderSelection()
    }

    private func openFirstSearchHit() {
        if searchTask != nil {
            openWhenSearchCompletes = true
            return
        }
        let row = folderTable.selectedRow
        openSearchHit(at: searchRows?.indices.contains(row) == true ? row : 0)
    }

    @objc private func openSelectedSearchHit() { openSearchHit(at: folderTable.selectedRow) }

    private func openSearchHit(at row: Int) {
        do { try activateSearchHit(at: row) } catch { present(error) }
    }

    private func activateSearchHit(at row: Int, deciding: (() -> EditDecision)? = nil) throws {
        guard let searchRows, searchRows.indices.contains(row), let folder else { return }
        let match = searchRows[row]
        guard let index = folder.notes.firstIndex(where: { $0.relativePath == match.relativePath }) else {
            refreshSearch()
            return
        }
        let url = try folder.checkedURL(for: folder.notes[index])
        try activateFolderNote(index, in: folder, refreshCleanNote: match.range != nil, deciding: deciding)
        guard note?.url == url else {
            folderTable.deselectAll(nil)
            return
        }
        if let range = match.range {
            let text = source as NSString
            if range.location <= text.length, range.length <= text.length - range.location,
               let matchedText = match.matchedText,
               text.substring(with: range).utf8.elementsEqual(matchedText.utf8) {
                let selection: NSRange
                if range.length > 0 { selection = text.rangeOfComposedCharacterSequences(for: range) }
                else if range.location < text.length {
                    selection = NSRange(location: text.rangeOfComposedCharacterSequence(at: range.location).location, length: 0)
                } else { selection = range }
                window.makeFirstResponder(editor)
                editor.setSelectedRange(selection)
                editor.scrollRangeToVisible(selection)
            } else {
                refreshSearch()
            }
        } else {
            window.makeFirstResponder(preview.isEditable ? preview : editor)
        }
    }

    private func activateFolderNote(_ row: Int, in folder: FolderWorkspace,
        refreshCleanNote: Bool = false,
        deciding: (() -> EditDecision)? = nil) throws {
        defer { syncFolderSelection() }
        let entry = folder.notes[row]
        let checkedAccess = { try folder.checkedURL(for: entry) }
        let url = try checkedAccess()
        if let note, note.url == url {
            guard refreshCleanNote, !hasUnsavedChanges else { return }
            let refreshed = try NoteFile(url, checkedAccess: checkedAccess)
            if refreshed.original != note.original { install(refreshed) }
            return
        }
        let file = try NoteFile(url, checkedAccess: checkedAccess)
        guard try resolvePendingEdits(deciding: deciding) else { return }
        try finishSwitch(to: file)
    }

    private func finishSwitch(to file: NoteFile) throws {
        if hasUnsavedChanges { try recoveryStore?.clear() }
        install(file)
    }

    @objc private func newNote() {
        let panel = NSSavePanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "md") ?? .plainText]
        panel.nameFieldStringValue = "Untitled.md"
        panel.canCreateDirectories = true
        panel.directoryURL = folder?.root
        guard panel.runModal() == .OK, let selection = panel.url else { return }
        do {
            guard try resolvePendingEdits() else { return }
            let destination = selection.standardizedFileURL.resolvingSymlinksInPath()
            try NoteFile.create(at: destination)
            let url = destination.resolvingSymlinksInPath()
            if let folder, let refreshed = try? folder.refreshed(), let row = refreshed.index(of: url) {
                showFolder(refreshed)
                try activateFolderNote(row, in: refreshed, deciding: { .discard })
            } else {
                try finishSwitch(to: NoteFile(url))
            }
            window.makeFirstResponder(preview)
        } catch { present(error) }
    }

    @objc private func saveNote() {
        do { _ = try saveCurrentNote() }
        catch { present(error) }
    }

    private func saveCurrentNote(selectingCopy: (() -> URL?)? = nil) throws -> Bool {
        let changed = hasUnsavedChanges
        if let note {
            try note.save(source)
        } else if let draft = recoveryCopy {
            let selection: URL?
            if let selectingCopy { selection = selectingCopy() }
            else { selection = chooseRecoveryDestination(draft) }
            guard let selection else { return false }
            let path = selection.standardizedFileURL
            let folder = path.deletingLastPathComponent().resolvingSymlinksInPath()
            let url = folder.appendingPathComponent(path.lastPathComponent)
            guard url.path != draft.noteURL.path else { throw NoteError.recoveryNeedsNewFile }
            let edited = RecoverySnapshot(noteURL: draft.noteURL, original: draft.original, edited: source)
            try NoteFile.recoveredBytes(edited).write(to: url, options: .withoutOverwriting)
            install(try NoteFile(url))
        } else {
            return true
        }
        status.stringValue = "Saved \(note?.url.lastPathComponent ?? "note")"
        window.isDocumentEdited = false
        do { try recoveryStore?.clear() }
        catch { status.stringValue = "Saved · recovery copy could not be cleared" }
        if changed {
            invalidateConnections()
            updateConnections()
        }
        return true
    }

    private func chooseRecoveryDestination(_ draft: RecoverySnapshot) -> URL? {
        let panel = NSSavePanel()
        panel.title = "Save Recovered Copy"
        panel.allowedContentTypes = [UTType(filenameExtension: "md") ?? .plainText]
        panel.nameFieldStringValue = "\(draft.noteURL.deletingPathExtension().lastPathComponent) Recovered.md"
        return panel.runModal() == .OK ? panel.url : nil
    }

    private func replaceDocument(with text: String) {
        source = text
        editor.string = text
        updateProjection()
    }

    private func updateProjection() {
        projection = CmarkDocument(source: source)
        if let projection {
            preview.textStorage?.setAttributedString(renderedNote(projection))
        } else {
            preview.textStorage?.setAttributedString(NSAttributedString(string: "Use Markdown source for this note.",
                attributes: renderedAttributes(MarkdownStyle())))
        }
        preview.isEditable = projection != nil
    }

    func textDidChange(_ notification: Notification) {
        guard let view = notification.object as? NSTextView, view === editor else { return }
        defer { rawSelectionBeforeChange = nil }
        let edited = editor.string
        guard !source.utf8.elementsEqual(edited.utf8) else { return }
        recordUndo(snapshot(rawSelection: rawSelectionBeforeChange))
        source = edited
        updateProjection()
        showEditedStatus()
    }

    func textView(_ textView: NSTextView, shouldChangeTextIn range: NSRange, replacementString: String?) -> Bool {
        guard textView === preview else {
            rawSelectionBeforeChange = editor.selectedRange()
            return true
        }
        guard let replacementString, !preview.hasMarkedText(),
              let edit = projection?.verifiedEdit(range, with: replacementString),
              let rawStorage = editor.textStorage, let renderedStorage = preview.textStorage else {
            status.stringValue = "Use Markdown source for this edit"
            return false
        }

        recordUndo(snapshot())
        // The AST check has already proved the surrounding text and style are unchanged.
        source = edit.document.source
        projection = edit.document
        rawStorage.replaceCharacters(in: edit.sourceRange, with: edit.sourceReplacement)
        renderedStorage.replaceCharacters(in: range, with:
            NSAttributedString(string: edit.renderedReplacement, attributes: renderedAttributes(edit.style)))
        editor.setSelectedRange(NSRange(location: edit.sourceRange.location + edit.sourceReplacement.utf16.count, length: 0))
        preview.setSelectedRange(NSRange(location: range.location + edit.renderedReplacement.utf16.count, length: 0))
        showEditedStatus()
        return false
    }

    private func snapshot(rawSelection: NSRange? = nil) -> Snapshot {
        Snapshot(source: source, rawSelection: rawSelection ?? editor.selectedRange(),
                 renderedSelection: preview.selectedRange())
    }

    private func recordUndo(_ snapshot: Snapshot) {
        let isNewEdit = !history.isUndoing && !history.isRedoing
        if isNewEdit { history.beginUndoGrouping() }
        history.registerUndo(withTarget: self) { target in target.restore(snapshot) }
        history.setActionName("Edit Markdown")
        if isNewEdit { history.endUndoGrouping() }
    }

    private func restore(_ previous: Snapshot) {
        recordUndo(snapshot())
        replaceDocument(with: previous.source)
        restoreSelection(previous.rawSelection, in: editor)
        restoreSelection(previous.renderedSelection, in: preview)
        showEditedStatus()
    }

    private func restoreSelection(_ range: NSRange, in view: NSTextView) {
        let count = view.string.utf16.count
        guard range.location <= count, range.length <= count - range.location else { return }
        view.setSelectedRange(range)
    }

    private func showEditedStatus() {
        status.stringValue = recoveryCopy.map { "Recovered copy · \($0.noteURL.lastPathComponent)" }
            ?? note?.url.lastPathComponent ?? "Markdown edited"
        let dirty = hasUnsavedChanges
        window.isDocumentEdited = dirty
        if dirty { scheduleRecovery() }
        else {
            do { try recoveryStore?.clear() }
            catch { status.stringValue = "Recovery copy could not be cleared" }
        }
        refreshTextSearch()
        updateConnections()
    }

    private func scheduleRecovery() {
        guard recoveryStore != nil, !recoveryScheduled else { return }
        recoveryScheduled = true
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(250)) { [weak self] in
            guard let self else { return }
            self.recoveryScheduled = false
            self.checkpointRecovery()
        }
    }

    private func checkpointRecovery() {
        guard hasUnsavedChanges, let url = note?.url ?? recoveryCopy?.noteURL,
              let original = note?.original ?? recoveryCopy?.original else { return }
        do { try recoveryStore?.save(RecoverySnapshot(noteURL: url, original: original, edited: source)) }
        catch { status.stringValue = "Unsaved changes · recovery copy unavailable" }
    }

    private func restoreRecovery() throws {
        guard let draft = try recoveryStore?.load() else { return }
        let checkedAccess = {
            let url = draft.noteURL
            let attributes = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey])
            guard url.resolvingSymlinksInPath().path == url.path,
                  attributes.isRegularFile == true, attributes.isSymbolicLink == false
            else { throw NoteError.externalChange }
            return url
        }
        if let url = try? checkedAccess(), let file = try? NoteFile(url, checkedAccess: checkedAccess) {
            if file.original == NoteFile.recoveredBytes(draft) {
                try recoveryStore?.clear()
                return
            }
            if file.original == draft.original {
                install(file)
                replaceDocument(with: draft.edited)
                window.isDocumentEdited = true
                status.stringValue = "Recovered unsaved changes · \(file.url.lastPathComponent)"
                return
            }
        }
        recoveryCopy = draft
        editor.isEditable = true
        replaceDocument(with: draft.edited)
        window.isDocumentEdited = true
        status.stringValue = "Recovered copy · \(draft.noteURL.lastPathComponent)"
    }

    @objc private func undoEdit() { if history.canUndo { history.undo() } }
    @objc private func redoEdit() { if history.canRedo { history.redo() } }

    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        switch menuItem.action {
        case #selector(undoEdit): history.canUndo
        case #selector(redoEdit): history.canRedo
        case #selector(focusFilenameSearch), #selector(focusTextSearch): folder != nil
        default: true
        }
    }

    private var hasUnsavedChanges: Bool {
        if recoveryCopy != nil { return true }
        guard let note else { return false }
        return !source.utf8.elementsEqual(note.text.utf8)
    }

    private func promptForPendingEdits() -> EditDecision {
        let alert = NSAlert()
        if let draft = recoveryCopy {
            alert.messageText = "Save a recovered copy of \(draft.noteURL.lastPathComponent)?"
            alert.informativeText = "The original file changed or is unavailable."
        } else {
            alert.messageText = "Save changes to \(note?.url.lastPathComponent ?? "this note")?"
        }
        alert.addButton(withTitle: recoveryCopy == nil ? "Save" : "Save a Copy…")
        alert.addButton(withTitle: "Cancel")
        let discard = alert.addButton(withTitle: "Discard Changes")
        discard.hasDestructiveAction = true
        switch alert.runModal() {
        case .alertFirstButtonReturn: return .save
        case .alertThirdButtonReturn: return .discard
        default: return .cancel
        }
    }

    private func resolvePendingEdits(deciding: (() -> EditDecision)? = nil) throws -> Bool {
        guard hasUnsavedChanges else { return true }
        switch deciding?() ?? promptForPendingEdits() {
        case .save: return try saveCurrentNote()
        case .discard: return true
        case .cancel: return false
        }
    }

    private func canCloseNote() -> Bool {
        do {
            guard try resolvePendingEdits() else { return false }
            if hasUnsavedChanges { try recoveryStore?.clear() }
            return true
        }
        catch { present(error); return false }
    }

    private func present(_ error: Error) { NSAlert(error: error).runModal() }

    func windowShouldClose(_ sender: NSWindow) -> Bool { canCloseNote() }
    func windowWillClose(_ notification: Notification) { windowIsClosing = true }
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        windowIsClosing || canCloseNote() ? .terminateNow : .terminateCancel
    }

    private func runRecoverySmoke(_ test: RecoverySmoke) {
        do {
            guard let recoveryStore else { throw CocoaError(.fileReadUnknown) }
            let addition = "\r\nRecovered Cafe\u{301} 日本語 🪔.\r\n"
            switch test.phase {
            case .edit:
                expect(note == nil && recoveryCopy == nil, "A new test must start without a recovered note")
                try load(test.noteURL.resolvingSymlinksInPath())
                guard let note else { throw CocoaError(.fileReadUnknown) }
                window.makeFirstResponder(editor)
                let end = NSRange(location: editor.string.utf16.count, length: 0)
                editor.insertText(addition, replacementRange: end)
                verifyEditorBytes(note.text + addition)
                expect(window.isDocumentEdited)
                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(400)) {
                    let saved = try? recoveryStore.load()
                    self.expect(saved?.original == note.original && saved?.edited.utf8.elementsEqual(self.source.utf8) == true,
                                "The editor did not checkpoint the latest Markdown")
                    print("READY Native recovery checkpoint")
                    fflush(stdout)
                }
                return
            case .restore:
                guard let draft = try recoveryStore.load() else { throw CocoaError(.fileReadUnknown) }
                expect(note?.url == draft.noteURL && recoveryCopy == nil && window.isDocumentEdited)
                verifyEditorBytes(draft.edited)
                expect(tryBytes(test.noteURL) == draft.original, "Recovery wrote to the original on launch")
                let saved = try saveCurrentNote()
                expect(saved && tryBytes(test.noteURL) == NoteFile.recoveredBytes(draft))
            case .conflict:
                guard let draft = try recoveryStore.load() else { throw CocoaError(.fileReadUnknown) }
                expect(note == nil && recoveryCopy?.noteURL == draft.noteURL && window.isDocumentEdited)
                verifyEditorBytes(draft.edited)
                let originalOnDisk = tryBytes(test.noteURL)
                let cancelled = try saveCurrentNote(selectingCopy: { nil })
                expect(!cancelled && window.isDocumentEdited)
                do {
                    _ = try saveCurrentNote(selectingCopy: { draft.noteURL })
                    preconditionFailure("Recovery overwrote the source")
                } catch NoteError.recoveryNeedsNewFile { }
                let copy = test.noteURL.deletingLastPathComponent().appendingPathComponent("Recovered.md")
                let existing = Data("An existing copy".utf8)
                try existing.write(to: copy, options: .withoutOverwriting)
                do {
                    _ = try saveCurrentNote(selectingCopy: { copy })
                    preconditionFailure("Recovery replaced an existing copy")
                } catch CocoaError.fileWriteFileExists {
                    expect(tryBytes(copy) == existing && window.isDocumentEdited)
                }
                try FileManager.default.removeItem(at: copy)
                let saved = try saveCurrentNote(selectingCopy: { copy })
                expect(saved && tryBytes(copy) == NoteFile.recoveredBytes(draft))
                expect(tryBytes(test.noteURL) == originalOnDisk, "Save a Copy changed the original")
            case .saved:
                expect(note == nil && recoveryCopy == nil && !window.isDocumentEdited)
            }
            let pending = try recoveryStore.load()
            expect(pending == nil, "Completed recovery left a stale snapshot")
            print("PASS Native recovery \(test.phase.rawValue)")
            window.performClose(nil)
        } catch {
            fputs("FAIL recovery: \(error)\n", stderr)
            exit(EXIT_FAILURE)
        }
    }

    private func runSmoke(_ url: URL) async {
        do {
            try load(url)
            guard window.isVisible, let note else { throw CocoaError(.validationMissingMandatoryProperty) }
            let original = note.text
            let fileBytes = note.original
            let bom = Data([0xEF, 0xBB, 0xBF])
            expect(fileBytes.starts(with: bom), "Fixture must have a UTF-8 BOM")
            try note.save(original)
            expect(tryBytes(url) == fileBytes, "Untouched source bytes changed")
            expect(preview.string.contains("Heading") && preview.string.contains("日本語"))
            expect(!preview.string.contains("**") && !preview.string.contains("# Heading"))
            expect(noteFont(16).fontName == "Inter-Regular", "Bundled font was not loaded")
            verifyStyles()
            window.makeFirstResponder(preview)
            let boldRange = range(of: "bold", in: preview)
            preview.setSelectedRange(boldRange)
            preview.insertText("bright", replacementRange: boldRange)
            let firstEdit = original.replacingOccurrences(of: "**bold**", with: "**bright**")
            verifyEditorBytes(firstEdit)

            let label = range(of: "label", in: preview)
            preview.setSelectedRange(label)
            preview.insertText("édition 🪔", replacementRange: label)
            let secondEdit = firstEdit.replacingOccurrences(of: "[label]", with: "[édition 🪔]")
            verifyEditorBytes(secondEdit)
            expect(attributes(of: "édition 🪔")[.underlineStyle] != nil)

            let punctuation = range(of: "日本語.", in: preview)
            let afterParagraph = NSRange(location: NSMaxRange(punctuation), length: 0)
            preview.setSelectedRange(afterParagraph)
            preview.insertText("!", replacementRange: afterParagraph)
            let thirdEdit = secondEdit.replacingOccurrences(of: "日本語.", with: "日本語.!")
            verifyEditorBytes(thirdEdit)
            let crossing = range(of: "A bright", in: preview)
            preview.insertText("Unsupported", replacementRange: crossing)
            let wiki = range(of: "Reference note", in: preview)
            preview.insertText("Different note", replacementRange: wiki)
            verifyEditorBytes(thirdEdit)

            window.makeFirstResponder(editor)
            let append = NSRange(location: editor.string.utf16.count, length: 0)
            editor.setSelectedRange(append)
            let addition = "\r\nSaved in the native editor.\r\n"
            editor.insertText(addition, replacementRange: append)
            let saved = thirdEdit + addition
            verifyEditorBytes(saved)
            expect(preview.string.contains("Saved in the native editor."), "Source edit was not projected")

            for expected in [thirdEdit, secondEdit, firstEdit, original] {
                history.undo()
                verifyEditorBytes(expected)
            }
            for expected in [firstEdit, secondEdit, thirdEdit, saved] {
                history.redo()
                verifyEditorBytes(expected)
            }
            try note.save(source)
            expect(tryBytes(url) == bom + Data(saved.utf8), "Saved Markdown changed")
            try load(url)
            verifyEditorBytes(saved)
            verifyStyles(label: "édition 🪔")
            runTypingBenchmark()
            replaceDocument(with: saved)
            let external = bom + Data((editor.string + "\nExternal change\n").utf8)
            try external.write(to: url, options: .atomic)
            do {
                try self.note!.save(editor.string + "\nLocal change\n")
                preconditionFailure("External edit was overwritten")
            } catch NoteError.externalChange {
                expect(tryBytes(url) == external, "External file changed after conflict")
            }
            try verifyContinuousWriting(in: url.deletingLastPathComponent())
            try verifyFolderNavigation(in: url.deletingLastPathComponent())
            try await verifyWorkspaceSearch(in: url.deletingLastPathComponent())
            try await verifyConnections(in: url.deletingLastPathComponent())
            print("PASS Native editor styled rendering, exact rendered and source edits, mixed undo/redo, save/reopen, and external-edit rejection.")
            window.performClose(nil)
        } catch {
            fputs("FAIL \(error)\n", stderr)
            exit(1)
        }
    }

    private func range(of text: String, in view: NSTextView, backwards: Bool = false) -> NSRange {
        let range = (view.string as NSString).range(of: text, options: backwards ? [.literal, .backwards] : .literal)
        expect(range.location != NSNotFound, "Missing rendered text: \(text)")
        return range
    }

    private func attributes(of text: String) -> [NSAttributedString.Key: Any] {
        guard let storage = preview.textStorage else { preconditionFailure("Missing text storage") }
        return storage.attributes(at: range(of: text, in: preview).location, effectiveRange: nil)
    }

    private func verifyStyles(label: String = "label") {
        expect(noteFont(16).fontName == "Inter-Regular", "Bundled font was not loaded")
        let heading = attributes(of: "Heading")[.font] as? NSFont
        expect(heading?.pointSize == 28 && heading?.fontName == "Inter-SemiBold", "Heading style")
        let bold = attributes(of: label == "label" ? "bold" : "bright")[.font] as? NSFont
        expect(bold?.fontName == "Inter-SemiBold", "Bold style")
        expect((attributes(of: "italic")[.font] as? NSFont)?.fontName == "Inter-Italic", "Italic style")
        expect((attributes(of: "code")[.font] as? NSFont)?.isFixedPitch == true, "Code style")
        let link = attributes(of: label)
        expect(link[.underlineStyle] != nil && link[.link] == nil, "Inert link style")
        expect(attributes(of: "old work")[.strikethroughStyle] != nil, "GFM strike style")
        expect((attributes(of: "Quoted note.")[.paragraphStyle] as? NSParagraphStyle)?.headIndent == 16, "Quote style")
        expect(preview.string.contains("☑ Finished") && !preview.string.contains("[x]"), "Checklist marker")
    }

    private func verifyEditorBytes(_ expected: String) {
        expect(source.utf8.elementsEqual(expected.utf8), "Workspace changed unexpected bytes")
        expect(editor.string.utf8.elementsEqual(expected.utf8), "Source view changed unexpected bytes")
        guard let projection else { preconditionFailure("No projection for fixture") }
        expect(preview.string.utf8.elementsEqual(projection.visible.utf8), "Rendered view no longer matches its projection")
    }

    private func runTypingBenchmark() {
        let paragraph = "A paragraph with **strong** and *emphasized* text, a [link](https://example.com), and 日本語.\r\n\r\n"
        let source = String(repeating: paragraph, count: 384) + "Last **target** here.\r\n"
        let expected = source.replacingOccurrences(of: "**target**", with: "**changed**")
        var samples: [Double] = []
        for index in 0..<13 {
            replaceDocument(with: source)
            history.removeAllActions()
            window.makeFirstResponder(preview)
            let target = range(of: "target", in: preview, backwards: true)
            preview.setSelectedRange(target)
            preview.scrollRangeToVisible(target)
            window.displayIfNeeded()
            let started = DispatchTime.now().uptimeNanoseconds
            preview.insertText("changed", replacementRange: target)
            let accepted = DispatchTime.now().uptimeNanoseconds
            window.displayIfNeeded()
            let drawn = DispatchTime.now().uptimeNanoseconds
            if index >= 2 { samples.append(Double(drawn - started) / 1_000_000) }
            verifyEditorBytes(expected)
            expect(drawn >= accepted)
        }
        samples.sort()
        print(String(format: "Native insertText + displayIfNeeded, %d bytes, median %.3f ms, max %.3f ms",
                     source.utf8.count, samples[samples.count / 2], samples.last ?? 0))
    }

    private func verifyContinuousWriting(in folder: URL) throws {
        let empty = folder.appendingPathComponent("Created note.md")
        try NoteFile.create(at: empty)
        try load(empty)
        window.makeFirstResponder(preview)
        preview.insertText("Started from an empty file.", replacementRange: preview.selectedRange())
        let created = "Started from an empty file."
        verifyEditorBytes(created)
        try note?.save(source)
        do {
            try NoteFile.create(at: empty)
            expect(false, "Create should not overwrite an existing note")
        } catch let error as CocoaError where error.code == .fileWriteFileExists {
            expect(tryBytes(empty) == Data(created.utf8), "Create overwrote the existing note")
        }

        let plain = folder.appendingPathComponent("Continuous writing.md")
        try Data("\r\n".utf8).write(to: plain)
        try load(plain)
        window.makeFirstResponder(preview)
        preview.setSelectedRange(NSRange(location: 0, length: 0))
        for part in ["Every", " ", "note", "."] {
            preview.insertText(part, replacementRange: preview.selectedRange())
        }
        verifyEditorBytes("Every note.\r\n")
        preview.insertNewline(nil)
        verifyEditorBytes("Every note.\r\n\r\n\r\n")
        preview.insertText("次の段落 🪔", replacementRange: preview.selectedRange())
        let saved = "Every note.\r\n\r\n次の段落 🪔\r\n"
        verifyEditorBytes(saved)
        history.undo()
        verifyEditorBytes("Every note.\r\n\r\n\r\n")
        history.redo()
        verifyEditorBytes(saved)
        try note?.save(source)
        expect(tryBytes(plain) == Data(saved.utf8), "Plain prose save changed CRLF")
        try load(plain)
        verifyEditorBytes(saved)

        let mixed = folder.appendingPathComponent("Mixed note.md")
        let rich = "# Heading\r\n\r\n- [x] Keep this\r\n   "
        try Data(rich.utf8).write(to: mixed)
        try load(mixed)
        window.makeFirstResponder(preview)
        preview.setSelectedRange(NSRange(location: preview.string.utf16.count, length: 0))
        preview.insertNewline(nil)
        verifyEditorBytes(rich + "\r\n")
        for part in ["New", " ", "paragraph"] {
            preview.insertText(part, replacementRange: preview.selectedRange())
        }
        let mixedSaved = rich + "\r\nNew paragraph"
        verifyEditorBytes(mixedSaved)
        expect(attributes(of: "Heading")[.font] as? NSFont == noteFont(28, strong: true), "Earlier heading lost styling")
        expect(preview.string.contains("☑ Keep this"), "Earlier checklist lost formatting")
        try note?.save(source)
        expect(tryBytes(mixed) == Data(mixedSaved.utf8), "Mixed note save changed existing bytes")
        print("PASS Continuous writing from blank and styled notes, Return, Unicode, and CRLF save/reopen.")
    }

    private func verifyFolderNavigation(in directory: URL) throws {
        let directory = directory.appendingPathComponent("Workspace", isDirectory: true)
        let nested = directory.appendingPathComponent("Nested", isDirectory: true)
        try FileManager.default.createDirectory(at: nested, withIntermediateDirectories: true)
        let first = directory.appendingPathComponent("A.md")
        let second = nested.appendingPathComponent("B.md")
        let firstOriginal = Data("First.\r\n".utf8)
        let secondOriginal = Data("Second.\n".utf8)
        try firstOriginal.write(to: first)
        try secondOriginal.write(to: second)
        let folder = try FolderWorkspace(opening: directory)
        showFolder(folder)
        expect(folderTable.numberOfRows == 2, "Native sidebar must list the nested note")
        folderTable.selectRowIndexes(IndexSet(integer: 0), byExtendingSelection: false)
        expect(note?.url.lastPathComponent == "A.md", "Sidebar selection did not open the note")

        let editedFirst = "First, edited.\r\n"
        replaceDocument(with: editedFirst)
        showEditedStatus()
        showFolder(try FolderWorkspace(opening: nested))
        expect(folderTable.selectedRow == -1 && window.isDocumentEdited)
        verifyEditorBytes(editedFirst)
        showFolder(folder)
        expect(folderTable.selectedRow == 0)

        try Data([0xff]).write(to: second)
        do {
            try activateFolderNote(1, in: folder, deciding: {
                preconditionFailure("The app prompted to replace a note with invalid UTF-8")
            })
            preconditionFailure("The app loaded invalid UTF-8")
        } catch NoteError.invalidUTF8 {
            expect(folderTable.selectedRow == 0 && window.isDocumentEdited)
            verifyEditorBytes(editedFirst)
        }
        try secondOriginal.write(to: second)
        updatingFolderSelection = true
        folderTable.selectRowIndexes(IndexSet(integer: 1), byExtendingSelection: false)
        updatingFolderSelection = false
        try activateFolderNote(1, in: folder, deciding: { .cancel })
        expect(folderTable.selectedRow == 0 && window.isDocumentEdited)
        verifyEditorBytes(editedFirst)
        expect(tryBytes(first) == firstOriginal)
        try activateFolderNote(1, in: folder, deciding: { .save })
        expect(folderTable.selectedRow == 1 && !window.isDocumentEdited)
        verifyEditorBytes("Second.\n")
        expect(tryBytes(first) == Data(editedFirst.utf8))

        let editedSecond = "Local changes.\n"
        replaceDocument(with: editedSecond)
        showEditedStatus()
        let external = Data("Changed outside the editor.\n".utf8)
        try external.write(to: second)
        do {
            try activateFolderNote(0, in: folder, deciding: { .save })
            preconditionFailure("A failed save allowed the selected note to switch")
        } catch NoteError.externalChange {
            verifyEditorBytes(editedSecond)
            expect(folderTable.selectedRow == 1 && window.isDocumentEdited)
            expect(tryBytes(second) == external)
        }
        try activateFolderNote(0, in: folder, deciding: { .discard })
        expect(folderTable.selectedRow == 0 && !window.isDocumentEdited)
        verifyEditorBytes(editedFirst)
        expect(tryBytes(second) == external)

        let outside = directory.deletingLastPathComponent().appendingPathComponent("Outside.md")
        try Data(editedFirst.utf8).write(to: outside)
        try FileManager.default.removeItem(at: first)
        try FileManager.default.createSymbolicLink(at: first, withDestinationURL: outside)
        replaceDocument(with: "Pending local text.\n")
        showEditedStatus()
        do {
            try activateFolderNote(1, in: folder, deciding: { .save })
            preconditionFailure("The app saved through a symbolic link that replaced an open note")
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
            expect(folderTable.selectedRow == 0 && window.isDocumentEdited)
            expect(tryBytes(outside) == Data(editedFirst.utf8))
            verifyEditorBytes("Pending local text.\n")
        }
        try activateFolderNote(1, in: folder, deciding: { .discard })
        expect(folderTable.selectedRow == 1 && !window.isDocumentEdited)
        print("PASS Native folder sidebar selection, Save / Discard / Cancel, and blocked switch on external conflict.")
    }

    private func verifyWorkspaceSearch(in directory: URL) async throws {
        let root = directory.appendingPathComponent("Search Notes", isDirectory: true)
        let empty = directory.appendingPathComponent("No Notes", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        try FileManager.default.createDirectory(at: empty, withIntermediateDirectories: false)
        let draftPath = root.appendingPathComponent("Draft.md")
        let referencePath = root.appendingPathComponent("Reference.md")
        let bom = Data([0xEF, 0xBB, 0xBF])
        let draftText = "First line\r\n"
        let referenceText = "Intro\r\nLamp 🪔 Needle found\r\n"
        try Data(draftText.utf8).write(to: draftPath)
        try (bom + Data(referenceText.utf8)).write(to: referencePath)
        let draft = draftPath.resolvingSymlinksInPath()
        let reference = referencePath.resolvingSymlinksInPath()

        showFolder(try FolderWorkspace(opening: root))
        searchControls.field.stringValue = "Refer"
        searchControls.onChange?()
        searchControls.onSubmit?()
        await searchTask?.value
        expect(searchRows?.map(\.relativePath) == ["Reference.md"], "Filename search did not filter the sidebar")
        expect(note?.url.path == reference.path,
               "Submitting before results arrived did not open the first match (active: \(note?.url.path ?? "none"))")

        try load(draft)
        searchControls.field.stringValue = "Needle"
        searchControls.setMode(.text)
        await searchTask?.value
        expect(searchRows?.count == 1 && searchRows?.first?.lineNumber == 2)
        window.makeFirstResponder(editor)
        let end = NSRange(location: editor.string.utf16.count, length: 0)
        editor.insertText("Needle in unsaved draft\r\n", replacementRange: end)
        await searchTask?.value
        expect(searchRows?.count == 2 && searchRows?.first?.relativePath == "Draft.md",
               "Search did not include the active unsaved note")
        expect(tryBytes(draft) == Data(draftText.utf8), "Search saved an unsaved note")
        guard let referenceRow = searchRows?.firstIndex(where: { $0.relativePath == "Reference.md" }) else {
            throw CocoaError(.fileReadUnknown)
        }
        folderTable.selectRowIndexes(IndexSet(integer: referenceRow), byExtendingSelection: false)
        expect(note?.url.path == draft.path, "Selecting a result should let the user browse first")
        try activateSearchHit(at: referenceRow, deciding: { .cancel })
        expect(note?.url.path == draft.path && hasUnsavedChanges && folderTable.selectedRow == -1)
        try activateSearchHit(at: referenceRow, deciding: { .save })
        expect(note?.url.path == reference.path && tryBytes(draft) == Data((draftText + "Needle in unsaved draft\r\n").utf8))
        expect(editor.selectedRange() == (referenceText as NSString).range(of: "Needle"),
               "Search selection did not preserve UTF-16 offsets after emoji and CRLF")

        try load(draft)
        try (bom + Data("Intro rewritten externally\r\nNeedle moved\r\n".utf8)).write(to: reference, options: .atomic)
        try activateSearchHit(at: referenceRow)
        expect(note?.url.path == reference.path && editor.selectedRange().length == 0,
               "An externally moved match should refresh without selecting stale text")
        await searchTask?.value
        guard let movedRow = searchRows?.firstIndex(where: { $0.relativePath == "Reference.md" }) else {
            throw CocoaError(.fileReadUnknown)
        }
        try activateSearchHit(at: movedRow)
        expect(editor.selectedRange() == (source as NSString).range(of: "Needle"))

        try (bom + Data("Intro changed\r\nScope after an external edit\r\n".utf8)).write(to: reference, options: .atomic)
        searchControls.field.stringValue = "Scope"
        searchControls.onChange?()
        await searchTask?.value
        expect(searchRows?.map(\.relativePath) == ["Reference.md"], "A clean editor shadowed newer text on disk")
        try activateSearchHit(at: 0)
        expect(source.contains("Scope after an external edit")
            && editor.selectedRange() == (source as NSString).range(of: "Scope"))

        searchControls.onChange?()
        showFolder(try FolderWorkspace(opening: empty))
        await searchTask?.value
        expect(searchRows?.isEmpty == true, "An earlier search leaked matches into a different folder")
        searchControls.field.stringValue = ""
        searchControls.onChange?()
        expect(searchRows == nil && folderTable.numberOfRows == 0)
        print("PASS Native workspace search, unsaved note matching, safe navigation, and stale result cancellation.")
    }

    private func verifyConnections(in directory: URL) async throws {
        let root = directory.appendingPathComponent("Linked Notes", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false)
        let origin = root.appendingPathComponent("Origin.md")
        let reference = root.appendingPathComponent("Reference.md")
        let alternate = root.appendingPathComponent("Alternate.md")
        let backlink = root.appendingPathComponent("Backlink.md")
        let wiki = "[[Reference#heading|read it]]"
        let original = "# Origin\n\n\(wiki)\n\n`[[Alternate]]` is code.\n"
        try Data(original.utf8).write(to: origin)
        try Data("# Reference\n".utf8).write(to: reference)
        try Data("# Alternate\n".utf8).write(to: alternate)
        try Data("[Open the origin](Origin.md)\n".utf8).write(to: backlink)

        showFolder(try FolderWorkspace(opening: root))
        try load(origin.resolvingSymlinksInPath())
        await linkIndexTask?.value
        guard let scroll = connectionsView.arrangedSubviews.first(where: { $0 is NSScrollView }) as? NSScrollView,
              let outline = scroll.documentView as? NSOutlineView else { throw CocoaError(.fileReadUnknown) }
        func rowLabels() -> [String] {
            (0..<outline.numberOfRows).compactMap { row in
                (outline.view(atColumn: 0, row: row, makeIfNecessary: true) as? NSTextField)?.stringValue
            }
        }
        expect(rowLabels() == ["Incoming · 1", "Backlink.md", "Outgoing · 1", "Reference.md"],
               "Connections must show the local Markdown backlink and real wiki destination")
        let linkedText = range(of: wiki, in: preview)
        guard let token = preview.textStorage?.attribute(.link, at: linkedText.location, effectiveRange: nil) else {
            throw CocoaError(.fileReadUnknown)
        }
        preview.clicked(onLink: token, at: linkedText.location)
        expect(note?.url.lastPathComponent == "Reference.md", "Clicking a rendered wiki link did not open its note")
        guard let returnRow = rowLabels().firstIndex(of: "Origin.md") else { throw CocoaError(.fileReadUnknown) }
        outline.selectRowIndexes(IndexSet(integer: returnRow), byExtendingSelection: false)
        expect(connectionsView.openSelected() && note?.url.lastPathComponent == "Origin.md",
               "Activating a backlink did not reopen its source")

        window.makeFirstResponder(editor)
        let wikiSource = (editor.string as NSString).range(of: wiki)
        editor.insertText("[[Alternate]]", replacementRange: wikiSource)
        expect(linkIndexTask == nil && linkSnapshot != nil, "An unsaved edit should reuse the folder index")
        await activeLinksTask?.value
        expect(rowLabels() == ["Incoming · 1", "Backlink.md", "Outgoing · 1", "Alternate.md"],
               "Connections must reflect the unsaved active note")
        expect(tryBytes(origin) == Data(original.utf8), "Indexing modified the source file")
        try activateConnection("Alternate.md", deciding: { .cancel })
        expect(note?.url.lastPathComponent == "Origin.md" && hasUnsavedChanges)
        try activateConnection("Alternate.md", deciding: { .save })
        await linkIndexTask?.value
        expect(note?.url.lastPathComponent == "Alternate.md" && !hasUnsavedChanges)
        expect(rowLabels().contains("Origin.md"), "Saving a linked note did not update backlinks")
        let saved = original.replacingOccurrences(of: wiki, with: "[[Alternate]]")
        expect(tryBytes(origin) == Data(saved.utf8))

        try FileManager.default.removeItem(at: origin)
        rescanConnections()
        await linkIndexTask?.value
        expect(rowLabels() == ["Incoming · 0", "Outgoing · 0"], "Refreshing retained a deleted note")
        let button = NSButton()
        let renderedPane = workspaceSplit.arrangedSubviews[2]
        let openedRightEdge = renderedPane.frame.maxX
        let openedSplitWidth = workspaceSplit.bounds.width
        expect((connectionsPane?.frame.width ?? 0) > 100, "Connections must have visible space when open")
        toggleConnections(button)
        window.layoutIfNeeded()
        expect(connectionsPane?.superview == nil && renderedPane.frame.maxX > openedRightEdge
            && workspaceSplit.bounds.width == openedSplitWidth,
               "Closing Connections must return space to the editor (\(openedRightEdge) → \(renderedPane.frame.maxX), split \(workspaceSplit.bounds.width))")
        toggleConnections(button)
        window.layoutIfNeeded()
        expect(connectionsPane?.superview === workspaceSplit && rowLabels() == ["Incoming · 0", "Outgoing · 0"])
        expect(abs(renderedPane.frame.maxX - openedRightEdge) <= 2,
               "Reopening Connections must restore its previous width")
        print("PASS Native linked notes, rendered navigation, live backlinks, and safe unsaved switching.")
    }

    private func expect(_ condition: @autoclosure () -> Bool, _ message: String = "Assertion failed", line: Int = #line) {
        guard condition() else {
            fputs("FAIL smoke line \(line): \(message)\n", stderr)
            exit(1)
        }
    }

    private func tryBytes(_ url: URL) -> Data? { try? Data(contentsOf: url) }
}

@main
enum ProbeMain {
    @MainActor
    static func main() {
        let args = CommandLine.arguments
        let fixture = args.count == 3 && args[1] == "--smoke" ? URL(fileURLWithPath: args[2]) : nil
        let app = NSApplication.shared
        do {
            let smoke: RecoverySmoke?
            let store: RecoveryStore?
            if args.dropFirst().first == "--recovery-smoke" {
                guard args.count == 5, let phase = RecoverySmoke.Phase(rawValue: args[2]) else {
                    fputs("Invalid recovery smoke arguments.\n", stderr)
                    exit(EXIT_FAILURE)
                }
                smoke = RecoverySmoke(phase: phase, noteURL: URL(fileURLWithPath: args[3]))
                store = RecoveryStore(fileURL: URL(fileURLWithPath: args[4]))
            } else {
                smoke = nil
                store = fixture == nil ? try RecoveryStore.applicationSupport() : nil
            }
            let workspace = Workspace(fixture: fixture, recoveryStore: store, recoverySmoke: smoke)
            app.setActivationPolicy(.regular)
            app.delegate = workspace
            withExtendedLifetime(workspace) { app.run() }
        } catch {
            NSAlert(error: error).runModal()
        }
    }
}
