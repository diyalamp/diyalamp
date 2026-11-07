import AppKit

enum RenderedWikiLink {
    private static let scheme = "diyalamp-note"

    static func url(to destination: String) -> URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = "wiki"
        components.queryItems = [URLQueryItem(name: "target", value: destination)]
        return components.url
    }

    static func destination(from link: Any) -> String? {
        let url = (link as? URL) ?? (link as? String).flatMap(URL.init(string:))
        guard let url, let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              components.scheme == scheme, components.host == "wiki", components.path.isEmpty,
              components.user == nil, components.password == nil, components.port == nil,
              components.fragment == nil, let items = components.queryItems, items.count == 1,
              items[0].name == "target", let target = items[0].value, !target.isEmpty else { return nil }
        return target
    }
}

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
    for run in projection.wikiRuns {
        guard let url = RenderedWikiLink.url(to: run.destination) else { continue }
        result.addAttributes([.link: url, .underlineStyle: NSUnderlineStyle.single.rawValue], range: run.range)
    }
    result.endEditing()
    return result
}
