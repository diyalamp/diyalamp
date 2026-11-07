import AppKit

@MainActor
final class WorkspaceSearchControls: NSStackView, NSSearchFieldDelegate {
    let field = NSSearchField()
    var onChange: (() -> Void)?
    var onSubmit: (() -> Void)?

    private let optionsButton = NSButton()
    private let popover = NSPopover()
    private let scope = NSSegmentedControl(labels: ["Files", "Text"], trackingMode: .selectOne,
                                          target: nil, action: nil)
    private let matchCase = NSButton(checkboxWithTitle: "Match case", target: nil, action: nil)
    private let wholeWord = NSButton(checkboxWithTitle: "Whole word", target: nil, action: nil)
    private let regex = NSButton(checkboxWithTitle: "Regular expression", target: nil, action: nil)
    private let includeField = NSTextField()
    private let excludeField = NSTextField()
    private let message = NSTextField(wrappingLabelWithString: "")

    var options: WorkspaceSearch.Options {
        var options = WorkspaceSearch.Options()
        options.mode = scope.selectedSegment == 1 ? .text : .filenames
        options.caseSensitive = matchCase.state == .on
        options.wholeWord = wholeWord.state == .on
        options.regularExpression = regex.state == .on
        options.include = includeField.stringValue
        options.exclude = excludeField.stringValue
        return options
    }

    init() {
        super.init(frame: .zero)
        orientation = .vertical
        alignment = .width
        spacing = 8
        edgeInsets = NSEdgeInsets(top: 0, left: 16, bottom: 8, right: 16)

        field.font = noteFont(13)
        field.setAccessibilityLabel("Search workspace")
        field.sendsWholeSearchString = true
        field.delegate = self
        field.target = self
        field.action = #selector(submitSearch)
        field.setContentHuggingPriority(.defaultLow, for: .horizontal)
        field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        optionsButton.title = ""
        optionsButton.image = NSImage(systemSymbolName: "slider.horizontal.3", accessibilityDescription: nil)
        optionsButton.imagePosition = .imageOnly
        optionsButton.bezelStyle = .rounded
        optionsButton.target = self
        optionsButton.action = #selector(toggleOptions)
        optionsButton.setContentHuggingPriority(.required, for: .horizontal)
        optionsButton.setContentCompressionResistancePriority(.required, for: .horizontal)

        let row = NSStackView(views: [field, optionsButton])
        row.spacing = 8
        row.alignment = .centerY
        addArrangedSubview(row)

        message.font = noteFont(13)
        message.textColor = .secondaryLabelColor
        message.maximumNumberOfLines = 3
        addArrangedSubview(message)
        setMessage("")

        scope.selectedSegment = 0
        scope.font = noteFont(13)
        scope.setAccessibilityLabel("Search scope")
        scope.setToolTip("Search note filenames", forSegment: 0)
        scope.setToolTip("Search text inside notes", forSegment: 1)
        scope.target = self
        scope.action = #selector(optionsChanged)
        for toggle in [matchCase, wholeWord, regex] {
            toggle.font = noteFont(13)
            toggle.target = self
            toggle.action = #selector(optionsChanged)
        }
        matchCase.toolTip = "Distinguish uppercase and lowercase letters"
        wholeWord.toolTip = "Match complete words"
        regex.toolTip = "Interpret the search as a regular expression"
        configureFilter(includeField, label: "Include paths", example: "e.g. notes/**")
        configureFilter(excludeField, label: "Exclude paths", example: "e.g. archive/**")
        refreshOptionsState()
    }

    required init?(coder: NSCoder) { nil }

    func setMode(_ mode: WorkspaceSearch.Mode) {
        let segment = mode == .text ? 1 : 0
        guard scope.selectedSegment != segment else { return }
        scope.selectedSegment = segment
        optionsChanged()
    }

    func setMessage(_ text: String) {
        message.stringValue = text
        message.toolTip = text.isEmpty ? nil : text
        message.isHidden = text.isEmpty
    }

    func setFolderAvailable(_ available: Bool) {
        field.isEnabled = available
        optionsButton.isEnabled = available
        if !available { popover.performClose(nil) }
    }

    func controlTextDidChange(_ notification: Notification) {
        refreshOptionsState()
        onChange?()
    }

    @objc private func submitSearch() {
        if field.stringValue.isEmpty { onChange?() }
        else { onSubmit?() }
    }

    @objc private func toggleOptions() {
        if popover.isShown { popover.performClose(nil); return }
        if popover.contentViewController == nil { configurePopover() }
        let below: NSRectEdge = optionsButton.isFlipped ? .maxY : .minY
        popover.show(relativeTo: optionsButton.bounds, of: optionsButton, preferredEdge: below)
    }

    @objc private func optionsChanged() {
        refreshOptionsState()
        onChange?()
    }

    private func refreshOptionsState() {
        let settings = options
        field.placeholderString = settings.mode == .text ? "Find text…" : "Find a note…"
        let active = settings.mode == .text || settings.caseSensitive || settings.wholeWord
            || settings.regularExpression || !settings.include.isEmpty || !settings.exclude.isEmpty
        let label = active ? "Search options (active)" : "Search options"
        optionsButton.setAccessibilityLabel(label)
        optionsButton.toolTip = label
        optionsButton.contentTintColor = active ? .controlAccentColor : nil
    }

    private func configureFilter(_ field: NSTextField, label: String, example: String) {
        field.font = noteFont(13)
        field.placeholderString = example
        field.setAccessibilityLabel(label)
        field.delegate = self
    }

    private func configurePopover() {
        let title = NSTextField(labelWithString: "Search options")
        title.font = noteFont(13, strong: true)
        let toggles = NSStackView(views: [matchCase, wholeWord, regex])
        toggles.orientation = .vertical
        toggles.alignment = .leading
        toggles.spacing = 6
        let content = NSStackView(views: [title, scope, toggles,
            filterGroup("Include paths", field: includeField), filterGroup("Exclude paths", field: excludeField)])
        content.orientation = .vertical
        content.alignment = .width
        content.spacing = 12
        content.edgeInsets = NSEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        content.translatesAutoresizingMaskIntoConstraints = false

        let view = NSView()
        view.addSubview(content)
        NSLayoutConstraint.activate([
            content.topAnchor.constraint(equalTo: view.topAnchor),
            content.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            content.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            content.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            content.widthAnchor.constraint(equalToConstant: 244),
        ])
        let controller = NSViewController()
        controller.view = view
        popover.contentSize = view.fittingSize
        popover.contentViewController = controller
        popover.behavior = .transient
    }

    private func filterGroup(_ title: String, field: NSTextField) -> NSStackView {
        let label = NSTextField(labelWithString: title)
        label.font = noteFont(13)
        let group = NSStackView(views: [label, field])
        group.orientation = .vertical
        group.alignment = .width
        group.spacing = 4
        return group
    }
}
