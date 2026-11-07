import CMarkGFM
import Foundation

struct MarkdownStyle: Hashable {
    var heading = 0
    var code = false
    var strong = false
    var italic = false
    var link = false
    var strike = false
    var quote = false
}

struct MarkdownRun {
    let range: NSRange
    let style: MarkdownStyle
}

struct CmarkDocument {
    struct VerifiedEdit {
        let document: CmarkDocument
        let sourceRange: NSRange
        let sourceReplacement: String
        let renderedReplacement: String
        let style: MarkdownStyle
    }

    private struct Event: Equatable {
        let type: cmark_node_type
        let phase: cmark_event_type
        var literal: Data?
        var destination: Data?
        var title: Data?
        var extensionName: String?
        var listType: cmark_list_type?
        var listDelimiter: cmark_delim_type?
        var number: Int32 = 0
        var tight: Bool = false
    }

    private struct Span {
        let event: Int
        let run: MarkdownRun
        let source: Range<Int>?
        let synthetic: Bool

        var display: NSRange { run.range }
    }

    private struct Line {
        let bytes: Range<Int>
        let firstTab: Int?
        let hasWikiOpening: Bool
    }

    private struct Prose {
        let rawStart: Int
        let displayStart: Int
        let eventStart: Int
    }

    let source: String
    let visible: String
    private let events: [Event]
    private let spans: [Span]
    private let prose: Prose?

    var renderedRuns: [MarkdownRun] { spans.map(\.run) }

    init?(source: String) {
        // GFM extension parsers update a shared inline scanner table.
        Self.parserLock.lock()
        defer { Self.parserLock.unlock() }
        var source = source
        source.makeContiguousUTF8()
        let bytes = Array(source.utf8)
        guard let root = Self.parse(bytes) else { return nil }
        defer { cmark_node_free(root) }
        guard let iterator = cmark_iter_new(root) else { return nil }
        defer { cmark_iter_free(iterator) }

        let lines = Self.lineRanges(in: bytes)
        var events: [Event] = []
        var spans: [Span] = []
        var visible = ""
        var displayOffset = 0
        var previousSourceEnd = 0
        var listOrdinals: [OpaquePointer: Int] = [:]
        var proseStart: (raw: Int, display: Int, events: Int, spans: Int)?
        var lastProseEnd = 0

        func append(_ text: String, from range: Range<Int>? = nil, style: MarkdownStyle = .init(), synthetic: Bool = true) {
            let count = text.utf16.count
            guard count > 0 else { return }
            let run = MarkdownRun(range: NSRange(location: displayOffset, length: count), style: style)
            spans.append(Span(event: events.count, run: run, source: range, synthetic: synthetic))
            visible.append(text)
            displayOffset += count
        }

        while true {
            let phase = cmark_iter_next(iterator)
            if phase == CMARK_EVENT_DONE { break }
            guard let node = cmark_iter_get_node(iterator) else { return nil }
            let type = cmark_node_get_type(node)
            var event = Event(type: type, phase: phase)
            if phase == CMARK_EVENT_ENTER {
                if cmark_node_parent(node) == root {
                    if let body = Self.plainParagraph(node, lines: lines, bytes: bytes) {
                        if proseStart == nil || lastProseEnd > body.lowerBound
                            || !Self.onlyProseWhitespace(bytes[lastProseEnd..<body.lowerBound]) {
                            let raw = events.count == 1 && Self.onlyProseWhitespace(bytes[..<body.lowerBound])
                                ? 0 : body.lowerBound
                            proseStart = (raw, displayOffset, events.count, spans.count)
                        }
                        lastProseEnd = body.upperBound
                    } else {
                        proseStart = nil
                    }
                }
                if cmark_node_get_syntax_extension(node) != nil {
                    guard let name = cmark_node_get_type_string(node) else { return nil }
                    event.extensionName = String(cString: name)
                    switch event.extensionName {
                    case "tasklist":
                        event.tight = cmark_gfm_extensions_get_tasklist_item_checked(node)
                    case "table":
                        let columns = Int(cmark_gfm_extensions_get_table_columns(node))
                        event.number = Int32(columns)
                        guard let alignments = cmark_gfm_extensions_get_table_alignments(node) else { return nil }
                        event.destination = Data(bytes: alignments, count: columns)
                    case "table_header", "table_row":
                        if displayOffset > 0 { append("\n") }
                    case "table_cell":
                        append("\t")
                    case "strikethrough", "link":
                        break
                    default:
                        return nil
                    }
                }
                switch type {
                case CMARK_NODE_TEXT, CMARK_NODE_CODE, CMARK_NODE_CODE_BLOCK, CMARK_NODE_HTML_INLINE, CMARK_NODE_HTML_BLOCK:
                    guard let literal = Self.data(cmark_node_get_literal(node)),
                          let text = String(data: literal, encoding: .utf8) else { return nil }
                    event.literal = literal
                    var range: Range<Int>?
                    if type == CMARK_NODE_TEXT {
                        range = Self.exactRange(node, matching: literal, lines: lines, bytes: bytes)
                        if let range {
                            guard range.lowerBound >= previousSourceEnd else { return nil }
                            previousSourceEnd = range.upperBound
                        }
                    }
                    if type == CMARK_NODE_CODE_BLOCK { event.title = Self.data(cmark_node_get_fence_info(node)) }
                    if type == CMARK_NODE_CODE_BLOCK || type == CMARK_NODE_HTML_BLOCK {
                        if displayOffset > 0 { append("\n\n") }
                    }
                    append(text, from: range, style: Self.style(of: node), synthetic: false)
                case CMARK_NODE_LINK, CMARK_NODE_IMAGE:
                    event.destination = Self.data(cmark_node_get_url(node))
                    event.title = Self.data(cmark_node_get_title(node))
                case CMARK_NODE_LIST:
                    event.listType = cmark_node_get_list_type(node)
                    event.listDelimiter = cmark_node_get_list_delim(node)
                    event.number = cmark_node_get_list_start(node)
                    event.tight = cmark_node_get_list_tight(node) != 0
                    if event.listType == CMARK_ORDERED_LIST { listOrdinals[node] = Int(event.number) }
                case CMARK_NODE_HEADING:
                    event.number = cmark_node_get_heading_level(node)
                    if displayOffset > 0 { append("\n\n") }
                case CMARK_NODE_PARAGRAPH:
                    if displayOffset > 0 { append("\n\n") }
                    if let item = cmark_node_parent(node), cmark_node_get_type(item) == CMARK_NODE_ITEM,
                       cmark_node_first_child(item) == node, let list = cmark_node_parent(item) {
                        let marker: String
                        if Self.extensionName(of: item) == "tasklist" {
                            marker = cmark_gfm_extensions_get_tasklist_item_checked(item) ? "☑ " : "☐ "
                        } else if let ordinal = listOrdinals[list] {
                            marker = "\(ordinal). "
                            listOrdinals[list] = ordinal + 1
                        } else {
                            marker = "• "
                        }
                        append(marker, style: Self.style(of: node))
                    }
                case CMARK_NODE_SOFTBREAK, CMARK_NODE_LINEBREAK:
                    append("\n")
                case CMARK_NODE_THEMATIC_BREAK:
                    if displayOffset > 0 { append("\n\n") }
                    append("―")
                case CMARK_NODE_DOCUMENT, CMARK_NODE_BLOCK_QUOTE, CMARK_NODE_ITEM, CMARK_NODE_EMPH, CMARK_NODE_STRONG:
                    break
                default:
                    if event.extensionName == nil { return nil }
                }
            }
            if phase == CMARK_EVENT_EXIT, type == CMARK_NODE_LIST { listOrdinals.removeValue(forKey: node) }
            events.append(event)
        }

        var prose: Prose?
        if let start = proseStart, Self.onlyProseWhitespace(bytes[lastProseEnd...]),
           let prefixEnd = Range(NSRange(location: 0, length: start.display), in: visible)?.upperBound {
            visible = String(visible[..<prefixEnd])
            spans.removeLast(spans.count - start.spans)
            displayOffset = start.display
            if displayOffset > 0 { append("\n\n") }
            prose = Prose(rawStart: start.raw, displayStart: displayOffset, eventStart: start.events)
            append(String(decoding: bytes[start.raw...], as: UTF8.self))
        } else if events.count == 2, Self.onlyProseWhitespace(bytes[...]) {
            prose = Prose(rawStart: 0, displayStart: 0, eventStart: 1)
            append(source)
        } else if let tail = Self.blankProseStart(in: bytes) {
            if displayOffset > 0 { append("\n\n") }
            prose = Prose(rawStart: tail, displayStart: displayOffset, eventStart: events.count - 1)
            append(String(decoding: bytes[tail...], as: UTF8.self))
        }

        self.source = source
        self.visible = visible
        self.events = events
        self.spans = spans
        self.prose = prose
    }

    func replacing(_ selection: NSRange, with replacement: String) -> CmarkDocument? {
        verifiedEdit(selection, with: replacement)?.document
    }

    func verifiedEdit(_ selection: NSRange, with replacement: String) -> VerifiedEdit? {
        guard let displayed = Self.graphemeRange(selection, in: visible) else { return nil }
        if let prose, selection.location >= prose.displayStart {
            return editProse(prose, displayed: displayed, with: replacement)
        }
        if selection.length == 0, selection.location == visible.utf16.count, replacement == "\n" {
            return startProse(with: Self.lineEnding(in: source, near: source.endIndex))
        }
        guard !replacement.unicodeScalars.contains(where: { CharacterSet.newlines.contains($0) || $0.value == 0 }),
              !replacement.isEmpty || selection.length > 0 else { return nil }
        let candidates = spans.filter {
            !$0.synthetic && selection.location >= $0.display.location && NSMaxRange(selection) <= NSMaxRange($0.display)
        }
        // A block separator or list marker has no raw caret position of its own.
        // At the boundary use the only text leaf, if there is one.
        guard candidates.count == 1, let span = candidates.first, let sourceRange = span.source,
              let literal = events[span.event].literal, let text = String(data: literal, encoding: .utf8),
              let local = Self.graphemeRange(
                NSRange(location: selection.location - span.display.location, length: selection.length), in: text
              ) else { return nil }
        let lowerOffset = text.utf8.distance(from: text.utf8.startIndex, to: local.lowerBound)
        let upperOffset = text.utf8.distance(from: text.utf8.startIndex, to: local.upperBound)
        let rawLower = source.utf8.index(source.utf8.startIndex, offsetBy: sourceRange.lowerBound + lowerOffset)
        let rawUpper = source.utf8.index(source.utf8.startIndex, offsetBy: sourceRange.lowerBound + upperOffset)
        guard let lower = rawLower.samePosition(in: source), let upper = rawUpper.samePosition(in: source) else { return nil }

        let editedSource = String(source[..<lower]) + replacement + String(source[upper...])
        let editedText = String(text[..<local.lowerBound]) + replacement + String(text[local.upperBound...])
        let expectedDisplay = String(visible[..<displayed.lowerBound]) + replacement + String(visible[displayed.upperBound...])
        let newRawLower = editedSource.utf8.index(editedSource.utf8.startIndex, offsetBy: sourceRange.lowerBound + lowerOffset)
        let newRawUpper = editedSource.utf8.index(newRawLower, offsetBy: replacement.utf8.count)
        guard newRawLower.samePosition(in: editedSource) != nil,
              newRawUpper.samePosition(in: editedSource) != nil else { return nil }
        var expectedEvents = events
        expectedEvents[span.event].literal = Data(editedText.utf8)
        let expectedSourceRange = sourceRange.lowerBound..<(sourceRange.upperBound + replacement.utf8.count - upperOffset + lowerOffset)
        let inserted = NSRange(location: selection.location, length: replacement.utf16.count)
        guard let next = CmarkDocument(source: editedSource), next.events == expectedEvents,
              next.visible.utf8.elementsEqual(expectedDisplay.utf8),
              Self.graphemeRange(inserted, in: next.visible) != nil,
              next.spans.contains(where: { $0.event == span.event && $0.source == expectedSourceRange }) else { return nil }
        return VerifiedEdit(document: next, sourceRange: NSRange(lower..<upper, in: source),
                            sourceReplacement: replacement, renderedReplacement: replacement, style: span.run.style)
    }

    private func editProse(_ prose: Prose, displayed: Range<String.Index>, with replacement: String) -> VerifiedEdit? {
        guard !replacement.unicodeScalars.contains(where: {
            $0.value == 0 || (CharacterSet.newlines.contains($0) && $0.value != 10 && $0.value != 13)
        }) else { return nil }
        let start = visible.utf16.index(visible.utf16.startIndex, offsetBy: prose.displayStart)
        let sourceStart = source.utf8.index(source.utf8.startIndex, offsetBy: prose.rawStart)
        let lower = source.utf8.index(sourceStart, offsetBy: visible.utf8.distance(from: start, to: displayed.lowerBound))
        let upper = source.utf8.index(sourceStart, offsetBy: visible.utf8.distance(from: start, to: displayed.upperBound))
        guard let lower = lower.samePosition(in: source), let upper = upper.samePosition(in: source) else { return nil }
        let inserted: String
        if replacement.contains(where: \.isNewline) {
            let eol = Self.lineEnding(in: source, near: lower)
            inserted = replacement == "\n" ? eol + eol : Self.normalizeLineEndings(in: replacement, to: eol)
        } else {
            inserted = replacement
        }
        let edited = String(source[..<lower]) + inserted + String(source[upper...])
        let expected = String(visible[..<displayed.lowerBound]) + inserted + String(visible[displayed.upperBound...])
        guard let next = CmarkDocument(source: edited), let region = next.prose,
              region.rawStart == prose.rawStart, region.displayStart == prose.displayStart,
              events.prefix(prose.eventStart).elementsEqual(next.events.prefix(region.eventStart)),
              next.visible.utf8.elementsEqual(expected.utf8) else { return nil }
        let changed = NSRange(location: NSRange(displayed, in: visible).location, length: inserted.utf16.count)
        guard Self.graphemeRange(changed, in: next.visible) != nil else { return nil }
        return VerifiedEdit(document: next, sourceRange: NSRange(lower..<upper, in: source),
                            sourceReplacement: inserted, renderedReplacement: inserted, style: MarkdownStyle())
    }

    private func startProse(with eol: String) -> VerifiedEdit? {
        // A final EOL already separates the last Markdown block from one blank line.
        let hasEnding = source.last(where: { $0 != " " })?.isNewline == true
        let raw = hasEnding ? eol : eol + eol
        guard let next = CmarkDocument(source: source + raw), next.events == events,
              next.prose?.displayStart == visible.utf16.count + 2,
              next.visible.utf8.elementsEqual((visible + "\n\n").utf8) else { return nil }
        return VerifiedEdit(document: next, sourceRange: NSRange(location: source.utf16.count, length: 0),
                            sourceReplacement: raw, renderedReplacement: "\n\n", style: MarkdownStyle())
    }

    private static func plainParagraph(_ node: OpaquePointer, lines: [Line], bytes: [UInt8]) -> Range<Int>? {
        guard cmark_node_get_type(node) == CMARK_NODE_PARAGRAPH else { return nil }
        let first = Int(cmark_node_get_start_line(node))
        let last = Int(cmark_node_get_end_line(node))
        guard first > 0, last >= first, last <= lines.count else { return nil }
        let body = lines[first - 1].bytes.lowerBound..<lines[last - 1].bytes.upperBound
        var covered = body.lowerBound
        var child = cmark_node_first_child(node)
        while let leaf = child {
            switch cmark_node_get_type(leaf) {
            case CMARK_NODE_TEXT:
                guard let literal = data(cmark_node_get_literal(leaf)),
                      let span = exactRange(leaf, matching: literal, lines: lines, bytes: bytes),
                      span.lowerBound >= covered, span.upperBound <= body.upperBound,
                      onlyProseWhitespace(bytes[covered..<span.lowerBound]) else { return nil }
                covered = span.upperBound
            case CMARK_NODE_SOFTBREAK:
                break
            default:
                return nil
            }
            child = cmark_node_next(leaf)
        }
        return onlyProseWhitespace(bytes[covered..<body.upperBound]) ? body : nil
    }

    private static func onlyProseWhitespace(_ bytes: ArraySlice<UInt8>) -> Bool {
        bytes.allSatisfy { $0 == 32 || $0 == 10 || $0 == 13 }
    }

    private static func blankProseStart(in bytes: [UInt8]) -> Int? {
        guard let lastText = bytes.lastIndex(where: { $0 != 32 && $0 != 10 && $0 != 13 }) else { return nil }
        var offset = lastText + 1
        for _ in 0..<2 {
            while offset < bytes.count, bytes[offset] == 32 { offset += 1 }
            guard offset < bytes.count, bytes[offset] == 10 || bytes[offset] == 13 else { return nil }
            if bytes[offset] == 13, offset + 1 < bytes.count, bytes[offset + 1] == 10 { offset += 1 }
            offset += 1
        }
        return offset
    }

    private static func lineEnding(in source: String, near index: String.Index) -> String {
        let newline = source[index...].first(where: \.isNewline) ?? source[..<index].last(where: \.isNewline)
        return switch newline {
        case "\r\n": "\r\n"
        case "\r": "\r"
        default: "\n"
        }
    }

    private static func normalizeLineEndings(in text: String, to lineEnding: String) -> String {
        guard text.contains(where: \.isNewline) else { return text }
        return text.map { $0.isNewline ? lineEnding : String($0) }.joined()
    }

    private static func extensionName(of node: OpaquePointer) -> String? {
        guard cmark_node_get_syntax_extension(node) != nil, let name = cmark_node_get_type_string(node) else { return nil }
        return String(cString: name)
    }

    private static func style(of node: OpaquePointer) -> MarkdownStyle {
        var style = MarkdownStyle()
        var current: OpaquePointer? = node
        while let parent = current {
            switch cmark_node_get_type(parent) {
            case CMARK_NODE_HEADING: style.heading = Int(cmark_node_get_heading_level(parent))
            case CMARK_NODE_CODE, CMARK_NODE_CODE_BLOCK: style.code = true
            case CMARK_NODE_STRONG: style.strong = true
            case CMARK_NODE_EMPH: style.italic = true
            case CMARK_NODE_LINK: style.link = true
            case CMARK_NODE_BLOCK_QUOTE: style.quote = true
            default:
                if extensionName(of: parent) == "strikethrough" { style.strike = true }
            }
            current = cmark_node_parent(parent)
        }
        return style
    }

    private static let parserLock = NSLock()
    private static let registered: Void = cmark_gfm_core_extensions_ensure_registered()

    private static func parse(_ bytes: [UInt8]) -> OpaquePointer? {
        _ = registered
        guard let parser = cmark_parser_new(CMARK_OPT_SOURCEPOS | CMARK_OPT_STRIKETHROUGH_DOUBLE_TILDE) else { return nil }
        defer { cmark_parser_free(parser) }
        for name in ["table", "tasklist", "strikethrough", "autolink"] {
            guard let syntax = cmark_find_syntax_extension(name), cmark_parser_attach_syntax_extension(parser, syntax) != 0
            else { return nil }
        }
        bytes.withUnsafeBytes {
            cmark_parser_feed(parser, $0.baseAddress?.assumingMemoryBound(to: CChar.self), $0.count)
        }
        return cmark_parser_finish(parser)
    }

    private static func data(_ pointer: UnsafePointer<CChar>?) -> Data? {
        pointer.map { Data(bytes: $0, count: strlen($0)) }
    }

    private static func graphemeRange(_ range: NSRange, in text: String) -> Range<String.Index>? {
        let count = text.utf16.count
        guard range.location >= 0, range.location <= count, range.length >= 0,
              range.length <= count - range.location, let indices = Range(range, in: text),
              indices.lowerBound.samePosition(in: text) != nil,
              indices.upperBound.samePosition(in: text) != nil else { return nil }
        return indices
    }

    private static func exactRange(_ node: OpaquePointer, matching literal: Data, lines: [Line],
                                   bytes: [UInt8]) -> Range<Int>? {
        let line = Int(cmark_node_get_start_line(node))
        let column = Int(cmark_node_get_start_column(node))
        let endColumn = Int(cmark_node_get_end_column(node))
        guard line > 0, line <= lines.count, cmark_node_get_end_line(node) == line,
              column > 0, endColumn >= column else { return nil }
        let physical = lines[line - 1]
        guard endColumn <= physical.bytes.count, endColumn - column + 1 == literal.count else { return nil }
        let range = (physical.bytes.lowerBound + column - 1)..<(physical.bytes.lowerBound + endColumn)
        // Tabs may be expanded in container prefixes. A BOM may be skipped by the parser.
        guard (physical.firstTab ?? physical.bytes.upperBound) >= range.upperBound,
              !physical.hasWikiOpening,
              line > 1 || !bytes.starts(with: [0xEF, 0xBB, 0xBF]),
              bytes[range].elementsEqual(literal),
              hasStableLinkSyntax(node, range: range, line: physical.bytes, bytes: bytes) else { return nil }
        return range
    }

    private static func hasStableLinkSyntax(_ node: OpaquePointer, range: Range<Int>, line: Range<Int>,
                                            bytes: [UInt8]) -> Bool {
        var ancestor = cmark_node_parent(node)
        while let parent = ancestor {
            let type = cmark_node_get_type(parent)
            if type == CMARK_NODE_LINK || type == CMARK_NODE_IMAGE {
                // A source range can include an unresolved second bracket after a shortcut.
                // Prove that the original token is an inline link without any definitions in scope.
                let end = Int(cmark_node_get_end_column(parent))
                let suffix = range.upperBound + 1
                let tokenEnd = line.lowerBound + end
                guard type == CMARK_NODE_LINK, cmark_node_first_child(parent) == node, cmark_node_next(node) == nil,
                      cmark_node_get_start_line(parent) == cmark_node_get_end_line(parent),
                      cmark_node_get_start_line(parent) == cmark_node_get_start_line(node),
                      cmark_node_get_start_column(parent) == range.lowerBound - line.lowerBound,
                      range.lowerBound > line.lowerBound, suffix + 1 < line.upperBound,
                      end <= line.count, tokenEnd > suffix + 1,
                      bytes[range.lowerBound - 1] == 91, bytes[range.upperBound] == 93,
                      bytes[suffix] == 40, bytes[tokenEnd - 1] == 41 else { return false }
                let start = range.lowerBound - 1
                guard let document = bytes.withUnsafeBytes({ buffer -> OpaquePointer? in
                    guard let base = buffer.baseAddress else { return nil }
                    return cmark_parse_document(base.advanced(by: start).assumingMemoryBound(to: CChar.self),
                                                tokenEnd - start, CMARK_OPT_DEFAULT)
                }) else { return false }
                defer { cmark_node_free(document) }
                guard let paragraph = cmark_node_first_child(document), cmark_node_next(paragraph) == nil,
                      let link = cmark_node_first_child(paragraph), cmark_node_get_type(link) == CMARK_NODE_LINK,
                      cmark_node_next(link) == nil else { return false }
                return data(cmark_node_get_url(link)) == data(cmark_node_get_url(parent))
                    && data(cmark_node_get_title(link)) == data(cmark_node_get_title(parent))
            }
            ancestor = cmark_node_parent(parent)
        }
        return true
    }

    private static func lineRanges(in bytes: [UInt8]) -> [Line] {
        var lines: [Line] = []
        var start = 0
        var index = 0
        var firstTab: Int?
        var hasWikiOpening = false
        while index < bytes.count {
            let byte = bytes[index]
            if byte == 9 && firstTab == nil { firstTab = index }
            if byte == 91 && index > start && bytes[index - 1] == 91 { hasWikiOpening = true }
            if byte == 10 || byte == 13 {
                lines.append(Line(bytes: start..<index, firstTab: firstTab, hasWikiOpening: hasWikiOpening))
                if byte == 13 && index + 1 < bytes.count && bytes[index + 1] == 10 { index += 1 }
                start = index + 1
                firstTab = nil
                hasWikiOpening = false
            }
            index += 1
        }
        lines.append(Line(bytes: start..<bytes.count, firstTab: firstTab, hasWikiOpening: hasWikiOpening))
        return lines
    }
}
