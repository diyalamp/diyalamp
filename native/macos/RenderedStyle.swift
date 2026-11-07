import AppKit

private let headingSizes: [CGFloat] = [28, 23, 19]

@MainActor private let quoteParagraphStyle: NSParagraphStyle = {
    let style = NSMutableParagraphStyle()
    style.firstLineHeadIndent = 16
    style.headIndent = 16
    style.tailIndent = -16
    return style
}()

@MainActor
func renderedAttributes(_ style: MarkdownStyle) -> [NSAttributedString.Key: Any] {
    let isHeading = style.heading > 0
    let size = isHeading ? headingSizes[min(style.heading, headingSizes.count) - 1] : 16
    let font = style.code
        ? NSFont.monospacedSystemFont(ofSize: size, weight: .regular)
        : noteFont(size, strong: isHeading || style.strong, italic: style.italic)
    var attributes: [NSAttributedString.Key: Any] = [
        .font: font,
        .foregroundColor: style.quote ? foreground.withAlphaComponent(0.82) : foreground,
    ]
    if style.quote {
        attributes[.paragraphStyle] = quoteParagraphStyle
    }
    if style.link {
        attributes[.underlineStyle] = NSUnderlineStyle.single.rawValue
    }
    if style.strike {
        attributes[.strikethroughStyle] = NSUnderlineStyle.single.rawValue
    }
    return attributes
}

@MainActor
func renderedNote(_ projection: CmarkDocument) -> NSAttributedString {
    let baseStyle = MarkdownStyle()
    let result = NSMutableAttributedString(
        string: projection.visible, attributes: renderedAttributes(baseStyle)
    )
    var cachedAttributes: [MarkdownStyle: [NSAttributedString.Key: Any]] = [:]
    result.beginEditing()
    for run in projection.renderedRuns where run.style != baseStyle {
        let attributes: [NSAttributedString.Key: Any]
        if let cached = cachedAttributes[run.style] {
            attributes = cached
        } else {
            attributes = renderedAttributes(run.style)
            cachedAttributes[run.style] = attributes
        }
        result.addAttributes(attributes, range: run.range)
    }
    result.endEditing()
    return result
}
