import Foundation

@main
enum WritingTests {
    static func main() {
        typeIntoEmptyNote()
        preserveLineEndings()
        continueAfterRichContent()
        deleteAcrossPlainParagraphs()
        protectHiddenDefinitions()
        rejectStructureChanges()
        print("PASS Native writing core")
    }

    private static func typeIntoEmptyNote() {
        let empty = require("")
        check(empty.visible.isEmpty, "An empty note has an empty projection")
        let first = append("Cafe\u{301}", to: empty, expecting: "Cafe\u{301}")
        let space = append(" ", to: first, expecting: "Cafe\u{301} ")
        check(space.visible == "Cafe\u{301} ", "The trailing space stays in the projection")
        let words = append("日本語 🪔", to: space, expecting: "Cafe\u{301} 日本語 🪔")
        let reopened = require(words.source)
        check(reopened.visible.utf8.elementsEqual(words.visible.utf8), "Reopening preserves prose bytes")
        let cleared = accept(reopened, NSRange(location: 0, length: reopened.visible.utf16.count),
                             "", expecting: "")
        _ = append("New again", to: cleared, expecting: "New again")

        let whitespace = "\r\n  \r\n"
        _ = append("始め", to: require(whitespace), expecting: whitespace + "始め")
    }

    private static func preserveLineEndings() {
        let first = "A first line\r\nA second line"
        let returned = append("\n", to: require(first), expecting: first + "\r\n\r\n")
        check(returned.visible.hasSuffix("\r\n\r\n"), "Return keeps an empty paragraph editable")
        let pasted = append("Café\rOne\r\nTwo\nThree 🪔", to: returned,
                            expecting: first + "\r\n\r\nCafé\r\nOne\r\nTwo\r\nThree 🪔")
        check(pasted.visible.utf8.elementsEqual(pasted.source.utf8), "Prose projection mirrors raw CRLF")
        let crlf = find("\r\n", in: pasted)
        reject(pasted, NSRange(location: crlf.location + 1, length: 1), "", "Cannot split CRLF")

        _ = append("\n", to: require("First\nLast"), expecting: "First\nLast\n\n")
        _ = append("\n", to: require("First\rLast"), expecting: "First\rLast\r\r")
        _ = append("\n", to: require("First\nMiddle\r\nLast"),
                   expecting: "First\nMiddle\r\nLast\r\n\r\n")
    }

    private static func continueAfterRichContent() {
        let rich = "# Heading\r\n\r\nA **bold** [link](https://example.test).\r\n\r\n> Quoted.\r\n"
        let original = require(rich)
        let blank = append("\n", to: original, expecting: rich + "\r\n")
        check(blank.visible.utf8.elementsEqual((original.visible + "\n\n").utf8),
              "Return adds a rendered insertion point without changing rich content")
        let first = append("新しい", to: blank, expecting: rich + "\r\n新しい")
        let space = append(" ", to: first, expecting: rich + "\r\n新しい ")
        let finished = append("🪔", to: space, expecting: rich + "\r\n新しい 🪔")
        check(finished.visible.hasPrefix(original.visible), "Heading, link, and quote projection are intact")
        reject(finished, find("Quoted.\n\n新しい", in: finished), "", "Cannot delete across the rich boundary")

        let withTail = "# Heading\n\nOne paragraph.\n\nSecond paragraph."
        _ = append(" Next.", to: require(withTail), expecting: withTail + " Next.")
        _ = append("\n", to: require("# No line ending"), expecting: "# No line ending\n\n")
        let blankWithSpaces = "# Heading\r\n   "
        _ = append("\n", to: require(blankWithSpaces), expecting: blankWithSpaces + "\r\n")
    }

    private static func deleteAcrossPlainParagraphs() {
        let input = "Cafe\u{301} 👩🏽‍💻 before\r\n\r\n日本語 after"
        let document = require(input)
        let emoji = find("👩🏽‍💻", in: document)
        reject(document, NSRange(location: emoji.location, length: 1), "", "Cannot split a surrogate pair")
        reject(document, NSRange(location: emoji.location + 2, length: 2), "", "Cannot split a grapheme cluster")
        _ = accept(document, find(" before\r\n\r\n日本語", in: document),
                   "", expecting: "Cafe\u{301} 👩🏽‍💻 after")
    }

    private static func protectHiddenDefinitions() {
        let definition = "[kept]: https://example.test/a \"Keep title\"\r\n\r\n"
        let prefix = "A [reference][kept].\r\n\r\n" + definition
        let document = require(prefix)
        check(document.visible.contains("reference") && !document.visible.contains("[kept]"),
              "Reference definitions stay hidden")
        let next = append("Fresh prose", to: document, expecting: prefix + "Fresh prose")
        _ = append(" stays.", to: next, expecting: prefix + "Fresh prose stays.")
    }

    private static func rejectStructureChanges() {
        let plain = require("A plain paragraph")
        let whole = NSRange(location: 0, length: plain.visible.utf16.count)
        for replacement in [
            "# Heading", "- Item", "1. Item", "> Quote", "[Link](https://example.test)",
            "[[Note]]", "**Bold**", "[hidden]: https://example.test", "Embedded\u{0}null",
            "New\u{2028}line",
        ] {
            reject(plain, whole, replacement, "Cannot introduce \(replacement.debugDescription)")
        }
        reject(plain, NSRange(location: NSNotFound, length: 1), "x", "Reject invalid selection")
        let lines = require("First\r\nSecond")
        reject(lines, NSRange(location: 5, length: 0), "  ", "Reject a soft break becoming a hard break")
    }

    @discardableResult
    private static func append(_ replacement: String, to document: CmarkDocument,
                               expecting source: String) -> CmarkDocument {
        accept(document, NSRange(location: document.visible.utf16.count, length: 0),
               replacement, expecting: source)
    }

    private static func accept(_ document: CmarkDocument, _ range: NSRange,
                               _ replacement: String, expecting source: String) -> CmarkDocument {
        let priorSource = Data(document.source.utf8)
        let priorVisible = Data(document.visible.utf8)
        guard let result = document.replacing(range, with: replacement) else {
            fatalError("Rejected \(replacement.debugDescription) at \(range) in \(document.source.debugDescription)")
        }
        check(result.source.utf8.elementsEqual(source.utf8),
              "Unexpected saved bytes for \(replacement.debugDescription) in \(document.source.debugDescription)")
        check(Data(document.source.utf8) == priorSource && Data(document.visible.utf8) == priorVisible,
              "Accepting an edit must not mutate its input")
        return result
    }

    private static func reject(_ document: CmarkDocument, _ range: NSRange,
                               _ replacement: String, _ description: String) {
        let source = Data(document.source.utf8)
        let visible = Data(document.visible.utf8)
        check(document.replacing(range, with: replacement) == nil, description)
        check(Data(document.source.utf8) == source && Data(document.visible.utf8) == visible,
              "Rejecting an edit must not mutate its input")
    }

    private static func require(_ source: String) -> CmarkDocument {
        guard let document = CmarkDocument(source: source) else {
            fatalError("Could not parse \(source.debugDescription)")
        }
        return document
    }

    private static func find(_ text: String, in document: CmarkDocument) -> NSRange {
        let range = (document.visible as NSString).range(of: text, options: .literal)
        check(range.location != NSNotFound, "Missing \(text.debugDescription) in \(document.visible.debugDescription)")
        return range
    }

    private static func check(_ condition: Bool, _ description: String) {
        if !condition { fatalError(description) }
    }
}
