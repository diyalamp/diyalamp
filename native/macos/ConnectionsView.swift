import AppKit

@MainActor
final class ConnectionsView: NSStackView, NSOutlineViewDataSource, NSOutlineViewDelegate {
    var onOpen: ((String) -> Void)?

    private final class Group: NSObject {
        let title: String
        var rows: [NoteRow] = []
        var expanded = true

        init(_ title: String) { self.title = title }
    }

    private final class NoteRow: NSObject {
        let path: String

        init(_ path: String) { self.path = path }
    }

    private final class ConnectionsOutline: NSOutlineView {
        var activateSelection: (() -> Bool)?

        override func keyDown(with event: NSEvent) {
            if (event.keyCode == 36 || event.keyCode == 76), activateSelection?() == true { return }
            super.keyDown(with: event)
        }
    }

    private let incoming = Group("Incoming")
    private let outgoing = Group("Outgoing")
    private let outline = ConnectionsOutline()
    private let scroll = NSScrollView()
    private let summary = NSStackView()
    private let message = NSTextField(wrappingLabelWithString: "")
    private var groups: [Group] { [incoming, outgoing] }

    init() {
        super.init(frame: .zero)
        orientation = .vertical
        alignment = .width
        spacing = 8

        message.font = noteFont(13)
        message.textColor = .secondaryLabelColor
        message.setContentHuggingPriority(.required, for: .vertical)
        summary.addArrangedSubview(message)
        summary.edgeInsets = NSEdgeInsets(top: 0, left: 16, bottom: 8, right: 16)
        addArrangedSubview(summary)

        outline.style = .sourceList
        outline.headerView = nil
        outline.rowHeight = 26
        outline.autoresizingMask = [.width]
        outline.columnAutoresizingStyle = .lastColumnOnlyAutoresizingStyle
        outline.setAccessibilityLabel("Note connections")
        let column = NSTableColumn(identifier: NSUserInterfaceItemIdentifier("Connection"))
        outline.addTableColumn(column)
        outline.outlineTableColumn = column
        outline.dataSource = self
        outline.delegate = self
        outline.target = self
        outline.doubleAction = #selector(openDoubleClicked)
        outline.activateSelection = { [weak self] in self?.openSelected() ?? false }
        scroll.documentView = outline
        scroll.hasVerticalScroller = true
        scroll.drawsBackground = false
        addArrangedSubview(scroll)
        setMessage("Open a note to see connections")
    }

    required init?(coder: NSCoder) { nil }

    func show(incoming: [String], outgoing: [String], unresolved: Int, partial: Bool) {
        var status: [String] = []
        if unresolved > 0 { status.append("\(unresolved) unresolved link\(unresolved == 1 ? "" : "s")") }
        if partial { status.append("Some notes were skipped") }
        showStatus(status.joined(separator: " · "))
        if !scroll.isHidden, self.incoming.rows.map(\.path) == incoming,
           self.outgoing.rows.map(\.path) == outgoing { return }
        let selection = selectedConnection()
        rememberExpansion()
        self.incoming.rows = incoming.map(NoteRow.init)
        self.outgoing.rows = outgoing.map(NoteRow.init)
        scroll.isHidden = false
        outline.reloadData()
        for group in groups {
            if !group.rows.isEmpty && group.expanded { outline.expandItem(group) }
            else { outline.collapseItem(group) }
        }
        if let (group, path) = selection, let item = group.rows.first(where: { $0.path == path }) {
            let row = outline.row(forItem: item)
            if row >= 0 { outline.selectRowIndexes(IndexSet(integer: row), byExtendingSelection: false) }
            else { outline.deselectAll(nil) }
        } else {
            outline.deselectAll(nil)
        }
    }

    func setMessage(_ text: String) {
        if scroll.isHidden, message.stringValue == text { return }
        rememberExpansion()
        for group in groups { group.rows = [] }
        outline.reloadData()
        scroll.isHidden = true
        showStatus(text)
    }

    @discardableResult
    func openSelected() -> Bool {
        openRow(outline.selectedRow)
    }

    func outlineView(_ outlineView: NSOutlineView, numberOfChildrenOfItem item: Any?) -> Int {
        if item == nil { return groups.count }
        return (item as? Group)?.rows.count ?? 0
    }

    func outlineView(_ outlineView: NSOutlineView, child index: Int, ofItem item: Any?) -> Any {
        if let group = item as? Group { return group.rows[index] }
        return groups[index]
    }

    func outlineView(_ outlineView: NSOutlineView, isItemExpandable item: Any) -> Bool {
        guard let group = item as? Group else { return false }
        return !group.rows.isEmpty
    }

    func outlineView(_ outlineView: NSOutlineView, isGroupItem item: Any) -> Bool { item is Group }

    func outlineView(_ outlineView: NSOutlineView, shouldSelectItem item: Any) -> Bool { item is NoteRow }

    func outlineView(_ outlineView: NSOutlineView, viewFor tableColumn: NSTableColumn?, item: Any) -> NSView? {
        let label: NSTextField
        if let group = item as? Group {
            label = NSTextField(labelWithString: "\(group.title) · \(group.rows.count)")
            label.font = noteFont(13, strong: true)
            label.textColor = .secondaryLabelColor
        } else if let row = item as? NoteRow {
            label = NSTextField(labelWithString: row.path)
            label.font = noteFont(13)
            label.lineBreakMode = .byTruncatingMiddle
            label.toolTip = row.path
        } else {
            return nil
        }
        label.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        return label
    }

    private func rememberExpansion() {
        for group in groups where !group.rows.isEmpty { group.expanded = outline.isItemExpanded(group) }
    }

    private func selectedConnection() -> (Group, String)? {
        let row = outline.selectedRow
        guard row >= 0, let item = outline.item(atRow: row) as? NoteRow,
              let group = outline.parent(forItem: item) as? Group else { return nil }
        return (group, item.path)
    }

    private func showStatus(_ text: String) {
        if message.stringValue == text, summary.isHidden == text.isEmpty { return }
        message.stringValue = text
        message.toolTip = text.isEmpty ? nil : text
        summary.isHidden = text.isEmpty
    }

    private func openRow(_ row: Int) -> Bool {
        guard row >= 0, let item = outline.item(atRow: row) as? NoteRow,
              let onOpen else { return false }
        onOpen(item.path)
        return true
    }

    @objc private func openDoubleClicked() { _ = openRow(outline.clickedRow) }
}
