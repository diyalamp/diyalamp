package com.diyalamp.desktop;

import java.util.*;
import java.util.regex.*;
import javax.swing.text.*;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** Common Markdown is stored as native styled text, never executable HTML. */
public final class Markdown {
  public static final String KIND = "md.kind",
      LEVEL = "md.level",
      LINK = "md.link",
      INLINE_CODE = "md.code",
      LANG = "md.language",
      CHECKED = "md.checked",
      NUMBER = "md.number",
      LINK_TITLE = "md.linkTitle",
      RAW_INLINE = "md.rawInline",
      FENCE_ID = "md.fenceId",
      FENCE_OPEN = "md.fenceOpen",
      FENCE_CLOSE = "md.fenceClose",
      FENCE_EMPTY = "md.fenceEmpty",
      SOURCE_LINE = "md.sourceLine",
      CANONICAL_LINE = "md.canonicalLine";
  private static final Parser INLINE_PARSER =
      Parser.builder()
          .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
          .extensions(List.of(StrikethroughExtension.create()))
          .build();
  private static final Pattern FENCE = Pattern.compile("^( {0,3})(`{3,}|~{3,})(.*)$");
  private static final Pattern BLOCK =
      Pattern.compile("^(#{1,6}) (.*)$|^([-+*]) (\\[[ xX]\\] )?(.*)$|^(\\d+)\\. (.*)$|^> ?(.*)$");

  public static DefaultStyledDocument read(String source) {
    DefaultStyledDocument d = new WritingDocument();
    String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    int fenceId = 0;
    try {
      for (int i = 0; i < lines.length; i++) {
        String line = lines[i];
        if (i == lines.length - 1 && line.isEmpty()) break;
        Matcher fence = FENCE.matcher(line);
        if (fence.matches() && (fence.group(2).charAt(0) != '`' || !fence.group(3).contains("`"))) {
          char marker = fence.group(2).charAt(0);
          int length = fence.group(2).length();
          Pattern closing =
              Pattern.compile(
                  "^ {0,3}" + Pattern.quote(String.valueOf(marker)) + "{" + length + ",}[ \t]*$");
          int last = i + 1;
          while (last < lines.length && !closing.matcher(lines[last]).matches()) last++;
          boolean closed = last < lines.length;
          int contentEnd = last;
          if (!closed && contentEnd > i + 1 && lines[contentEnd - 1].isEmpty()) contentEnd--;
          int id = ++fenceId;
          for (int k = i + 1; k < Math.max(i + 2, contentEnd); k++) {
            boolean empty = contentEnd == i + 1;
            SimpleAttributeSet a = paragraph("code", 1);
            a.addAttribute(LANG, fence.group(3).strip());
            a.addAttribute(FENCE_ID, id);
            a.addAttribute(FENCE_OPEN, line);
            a.addAttribute(FENCE_CLOSE, closed ? lines[last] : "");
            a.addAttribute(FENCE_EMPTY, empty);
            append(d, empty ? "" : lines[k], a, true);
          }
          i = closed ? last : lines.length;
          continue;
        }
        String kind = "paragraph", text = line;
        int level = 1, number = 1;
        boolean checked = false;
        if (line.matches(" {0,3}([-*_])(?: *\\1){2,} *")) {
          kind = "divider";
          text = "────────────────────────";
        } else {
          Matcher m = BLOCK.matcher(line);
          if (m.matches()) {
            if (m.group(1) != null) {
              kind = "heading";
              level = m.group(1).length();
              text = m.group(2);
            } else if (m.group(3) != null) {
              kind = m.group(4) != null ? "task" : "bullet";
              checked = m.group(4) != null && m.group(4).toLowerCase(Locale.ROOT).contains("x");
              text = m.group(5);
            } else if (m.group(6) != null) {
              kind = "ordered";
              try {
                number = Integer.parseInt(m.group(6));
              } catch (NumberFormatException ignored) {
              }
              text = m.group(7);
            } else {
              kind = "quote";
              text = m.group(8);
            }
          }
        }
        SimpleAttributeSet a = paragraph(kind, level);
        a.addAttribute(CHECKED, checked);
        a.addAttribute(NUMBER, number);
        int start = d.getLength();
        append(d, text, a, false);
        Element paragraph = d.getParagraphElement(start);
        SimpleAttributeSet provenance = new SimpleAttributeSet();
        provenance.addAttribute(SOURCE_LINE, line);
        provenance.addAttribute(CANONICAL_LINE, writeLine(d, paragraph));
        d.setParagraphAttributes(start, 1, provenance, false);
      }
    } catch (BadLocationException e) {
      throw new IllegalStateException(e);
    }
    return d;
  }

  private static void append(
      DefaultStyledDocument d, String text, SimpleAttributeSet paragraph, boolean literal)
      throws BadLocationException {
    int start = d.getLength();
    if (literal) d.insertString(start, text, null);
    else inline(d, text, new SimpleAttributeSet());
    d.insertString(d.getLength(), "\n", null);
    d.setParagraphAttributes(start, Math.max(1, d.getLength() - start), paragraph, true);
  }

  private static void inline(DefaultStyledDocument d, String text, AttributeSet inherited)
      throws BadLocationException {
    int tail = text.length();
    while (tail > 0 && (text.charAt(tail - 1) == ' ' || text.charAt(tail - 1) == '\t')) tail--;
    if (tail == 0) {
      d.insertString(d.getLength(), text, inherited);
      return;
    }
    // A plain prefix keeps this a paragraph: block syntax has already been handled above.
    String input = "x " + text.substring(0, tail);
    Node paragraph = INLINE_PARSER.parse(input).getFirstChild();
    boolean prefix = true;
    for (Node n = paragraph.getFirstChild(); n != null; n = n.getNext()) {
      if (prefix && n instanceof org.commonmark.node.Text t) {
        d.insertString(d.getLength(), t.getLiteral().substring(2), inherited);
        prefix = false;
      } else {
        appendInline(d, n, inherited, input);
      }
    }
    if (tail < text.length()) d.insertString(d.getLength(), text.substring(tail), inherited);
  }

  private static void appendInline(
      DefaultStyledDocument d, Node node, AttributeSet inherited, String input)
      throws BadLocationException {
    SimpleAttributeSet a = new SimpleAttributeSet(inherited);
    if (node instanceof org.commonmark.node.Text t) {
      d.insertString(d.getLength(), t.getLiteral(), a);
      return;
    }
    if (node instanceof org.commonmark.node.HtmlInline html) {
      d.insertString(d.getLength(), html.getLiteral(), a);
      return;
    }
    if (node instanceof org.commonmark.node.Image) {
      String raw = source(node, input);
      a.addAttribute(RAW_INLINE, raw);
      d.insertString(d.getLength(), raw, a);
      return;
    }
    if (node instanceof org.commonmark.node.Code c) {
      a.addAttribute(INLINE_CODE, true);
      StyleConstants.setFontFamily(a, Theme.codeFont(16).getFamily());
      StyleConstants.setBackground(a, Theme.CODE);
      d.insertString(d.getLength(), c.getLiteral(), a);
      return;
    }
    if (node instanceof org.commonmark.node.StrongEmphasis) StyleConstants.setBold(a, true);
    else if (node instanceof org.commonmark.node.Emphasis) StyleConstants.setItalic(a, true);
    else if (node instanceof Strikethrough) StyleConstants.setStrikeThrough(a, true);
    else if (node instanceof org.commonmark.node.Link link) {
      a.addAttribute(LINK, link.getDestination());
      if (link.getTitle() != null) a.addAttribute(LINK_TITLE, link.getTitle());
      StyleConstants.setUnderline(a, true);
      StyleConstants.setForeground(a, Theme.RED);
    }
    if (node.getFirstChild() == null) {
      d.insertString(d.getLength(), source(node, input), a);
      return;
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNext())
      appendInline(d, child, a, input);
  }

  private static String source(Node node, String input) {
    StringBuilder raw = new StringBuilder();
    for (var span : node.getSourceSpans()) {
      int start = span.getInputIndex();
      raw.append(input, start, Math.min(input.length(), start + span.getLength()));
    }
    return raw.toString();
  }

  public static SimpleAttributeSet paragraph(String kind, int level) {
    SimpleAttributeSet a = new SimpleAttributeSet();
    a.addAttribute(KIND, kind);
    a.addAttribute(LEVEL, level);
    StyleConstants.setFontFamily(
        a, kind.equals("code") ? Theme.codeFont(16).getFamily() : Theme.font(16).getFamily());
    StyleConstants.setFontSize(
        a,
        kind.equals("heading")
            ? switch (level) {
              case 1 -> 32;
              case 2 -> 25;
              case 3 -> 21;
              default -> 18;
            }
            : 16);
    StyleConstants.setBold(a, kind.equals("heading"));
    StyleConstants.setForeground(a, Theme.INK);
    if (kind.equals("code")) StyleConstants.setBackground(a, Theme.CODE);
    StyleConstants.setSpaceAbove(a, kind.equals("heading") ? 18 : 3);
    StyleConstants.setSpaceBelow(a, kind.equals("heading") ? 10 : 6);
    StyleConstants.setLineSpacing(a, .28f);
    StyleConstants.setLeftIndent(
        a, Set.of("bullet", "ordered", "task", "quote").contains(kind) ? 28 : 0);
    return a;
  }

  public static String write(StyledDocument d) {
    StringBuilder out = new StringBuilder();
    Element root = d.getDefaultRootElement();
    try {
      for (int i = 0; i < root.getElementCount(); i++) {
        Element p = root.getElement(i);
        int start = p.getStartOffset();
        if (start >= d.getLength()) break;
        AttributeSet a = p.getAttributes();
        String kind = Objects.toString(a.getAttribute(KIND), "paragraph");
        if (kind.equals("code")) {
          List<Element> lines = new ArrayList<>();
          Object id = a.getAttribute(FENCE_ID);
          String lang = Objects.toString(a.getAttribute(LANG), "");
          int next = i;
          while (next < root.getElementCount()) {
            Element line = root.getElement(next);
            AttributeSet attributes = line.getAttributes();
            if (line.getStartOffset() >= d.getLength()
                || !"code".equals(attributes.getAttribute(KIND))
                || !Objects.equals(id, attributes.getAttribute(FENCE_ID))
                || !lang.equals(Objects.toString(attributes.getAttribute(LANG), ""))) break;
            lines.add(line);
            next++;
          }
          writeCode(
              out,
              d,
              lines,
              a,
              next < root.getElementCount()
                  && root.getElement(next).getStartOffset() < d.getLength());
          i = next - 1;
          continue;
        }
        String line = writeLine(d, p);
        if (line.equals(a.getAttribute(CANONICAL_LINE))
            && a.getAttribute(SOURCE_LINE) instanceof String original) out.append(original);
        else {
          validateEditedLine(d, p, line);
          out.append(line);
        }
        out.append('\n');
      }
    } catch (BadLocationException e) {
      throw new IllegalStateException(e);
    }
    return out.toString();
  }

  private static void validateEditedLine(StyledDocument document, Element paragraph, String line)
      throws BadLocationException {
    DefaultStyledDocument reopened = read(line + "\n");
    String expected = text(document, paragraph);
    String actual = reopened.getText(0, reopened.getLength()).replaceFirst("\\n$", "");
    boolean matches = expected.equals(actual);
    String kind = Objects.toString(paragraph.getAttributes().getAttribute(KIND), "paragraph");
    String nextKind =
        Objects.toString(
            reopened.getParagraphElement(0).getAttributes().getAttribute(KIND), "paragraph");
    matches &= kind.equals(nextKind);
    for (int i = 0; matches && i < expected.length(); i++) {
      if (Character.isWhitespace(expected.charAt(i))) continue;
      AttributeSet before =
          document.getCharacterElement(paragraph.getStartOffset() + i).getAttributes();
      AttributeSet after = reopened.getCharacterElement(i).getAttributes();
      matches =
          StyleConstants.isBold(before) == StyleConstants.isBold(after)
              && StyleConstants.isItalic(before) == StyleConstants.isItalic(after)
              && StyleConstants.isStrikeThrough(before) == StyleConstants.isStrikeThrough(after)
              && Boolean.TRUE.equals(before.getAttribute(INLINE_CODE))
                  == Boolean.TRUE.equals(after.getAttribute(INLINE_CODE))
              && Objects.equals(before.getAttribute(LINK), after.getAttribute(LINK))
              && Objects.equals(before.getAttribute(LINK_TITLE), after.getAttribute(LINK_TITLE));
    }
    if (!matches)
      throw new IllegalStateException(
          "This formatting cannot round-trip safely as Markdown. Separate or simplify the adjacent "
              + "formatting and save again. The existing file has not been overwritten.");
  }

  private static String writeLine(StyledDocument d, Element p) throws BadLocationException {
    AttributeSet a = p.getAttributes();
    String kind = Objects.toString(a.getAttribute(KIND), "paragraph");
    String prefix =
        switch (kind) {
          case "heading" -> "#".repeat(Math.max(1, Math.min(6, number(a, LEVEL, 1)))) + " ";
          case "bullet" -> "- ";
          case "ordered" -> number(a, NUMBER, 1) + ". ";
          case "task" -> Boolean.TRUE.equals(a.getAttribute(CHECKED)) ? "- [x] " : "- [ ] ";
          case "quote" -> "> ";
          case "divider" -> "---";
          default -> "";
        };
    if (kind.equals("divider")) return prefix;
    String body = writeInline(d, p, kind);
    return prefix + (prefix.isEmpty() ? escapeBlockStart(body) : body);
  }

  private static String text(StyledDocument d, Element p) throws BadLocationException {
    return d.getText(
            p.getStartOffset(), Math.min(d.getLength(), p.getEndOffset()) - p.getStartOffset())
        .replaceFirst("\\n$", "");
  }

  private static void writeCode(
      StringBuilder out,
      StyledDocument d,
      List<Element> lines,
      AttributeSet a,
      boolean followedByContent)
      throws BadLocationException {
    String opening = Objects.toString(a.getAttribute(FENCE_OPEN), "");
    String closing = Objects.toString(a.getAttribute(FENCE_CLOSE), "```");
    Matcher original = FENCE.matcher(opening);
    char marker = original.matches() ? original.group(2).charAt(0) : '`';
    int width = original.matches() ? original.group(2).length() : 3;
    StringBuilder body = new StringBuilder();
    for (Element line : lines) {
      String content = text(d, line);
      if (Boolean.TRUE.equals(line.getAttributes().getAttribute(FENCE_EMPTY)) && content.isEmpty())
        continue;
      body.append(content).append('\n');
      Matcher run =
          Pattern.compile("^ {0,3}(" + Pattern.quote(String.valueOf(marker)) + "+)[ \t]*$")
              .matcher(content);
      if (run.matches()) width = Math.max(width, run.group(1).length() + 1);
    }
    if (opening.isEmpty() || !original.matches() || width != original.group(2).length()) {
      opening = String.valueOf(marker).repeat(width) + Objects.toString(a.getAttribute(LANG), "");
      if (!closing.isEmpty()) closing = String.valueOf(marker).repeat(width);
    }
    if (closing.isEmpty() && followedByContent) closing = String.valueOf(marker).repeat(width);
    out.append(opening).append('\n').append(body);
    if (!closing.isEmpty()) out.append(closing).append('\n');
  }

  private record Format(
      boolean bold,
      boolean italic,
      boolean strike,
      boolean code,
      String link,
      String title,
      String raw) {}

  private record Run(String text, Format format) {}

  private record Scope(String open, String close) {}

  private static String writeInline(StyledDocument d, Element paragraph, String kind)
      throws BadLocationException {
    List<Run> runs = new ArrayList<>();
    int end = Math.min(paragraph.getEndOffset(), d.getLength());
    for (int j = 0; j < paragraph.getElementCount(); j++) {
      Element element = paragraph.getElement(j);
      int start = Math.max(paragraph.getStartOffset(), element.getStartOffset());
      String t =
          d.getText(start, Math.max(0, Math.min(end, element.getEndOffset()) - start))
              .replace("\n", "");
      if (t.isEmpty()) continue;
      AttributeSet a = element.getAttributes();
      Format f =
          new Format(
              StyleConstants.isBold(a) && !kind.equals("heading"),
              StyleConstants.isItalic(a),
              StyleConstants.isStrikeThrough(a),
              Boolean.TRUE.equals(a.getAttribute(INLINE_CODE)),
              (String) a.getAttribute(LINK),
              (String) a.getAttribute(LINK_TITLE),
              (String) a.getAttribute(RAW_INLINE));
      if (!runs.isEmpty() && runs.getLast().format.equals(f)) {
        Run previous = runs.removeLast();
        runs.add(new Run(previous.text + t, f));
      } else runs.add(new Run(t, f));
    }
    StringBuilder out = new StringBuilder();
    List<Scope> active = new ArrayList<>();
    for (Run run : runs) {
      Format f = run.format;
      String t = run.text;
      // Delimiters cannot wrap boundary whitespace. Preserve those characters outside emphasis.
      int from = 0, to = t.length();
      if (!f.code && f.raw == null) {
        while (from < to && Character.isWhitespace(t.charAt(from))) from++;
        while (to > from && Character.isWhitespace(t.charAt(to - 1))) to--;
      }
      if (from > 0) {
        transition(out, active, List.of());
        out.append(escape(t.substring(0, from)));
      }
      if (from < to) {
        List<Scope> scopes = new ArrayList<>();
        if (f.link != null)
          scopes.add(
              new Scope(
                  "[",
                  "]("
                      + destination(f.link)
                      + (f.title == null
                          ? ""
                          : " \"" + f.title.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                      + ")"));
        if (f.bold) scopes.add(new Scope("**", "**"));
        if (f.italic) scopes.add(new Scope("*", "*"));
        if (f.strike) scopes.add(new Scope("~~", "~~"));
        transition(out, active, scopes);
        String content = t.substring(from, to);
        if (f.code) out.append(codeSpan(content));
        else out.append(f.raw != null && f.raw.equals(content) ? content : escape(content));
      }
      if (to < t.length()) {
        transition(out, active, List.of());
        out.append(escape(t.substring(to)));
      }
    }
    transition(out, active, List.of());
    return out.toString();
  }

  private static void transition(StringBuilder out, List<Scope> active, List<Scope> next) {
    int same = 0;
    while (same < active.size() && same < next.size() && active.get(same).equals(next.get(same)))
      same++;
    for (int i = active.size() - 1; i >= same; i--) out.append(active.remove(i).close);
    for (int i = same; i < next.size(); i++) {
      out.append(next.get(i).open);
      active.add(next.get(i));
    }
  }

  private static String destination(String url) {
    String escaped = url.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
    return escaped.chars().anyMatch(Character::isWhitespace)
            || escaped.contains("(")
            || escaped.contains(")")
        ? "<" + escaped + ">"
        : escaped;
  }

  private static String codeSpan(String content) {
    int longest = 0;
    Matcher runs = Pattern.compile("`+").matcher(content);
    while (runs.find()) longest = Math.max(longest, runs.group().length());
    String fence = "`".repeat(longest + 1);
    boolean pad =
        content.startsWith("`")
            || content.endsWith("`")
            || (content.startsWith(" ") && content.endsWith(" ") && !content.isBlank());
    return fence + (pad ? " " : "") + content + (pad ? " " : "") + fence;
  }

  private static String escapeBlockStart(String text) {
    if (text.matches(" {0,3}(?:#{1,6}(?:[ \t].*)?|[>+*-](?:[ \t].*)?|[-*_]{3,}[ \t]*)")) {
      int first = 0;
      while (first < text.length() && text.charAt(first) == ' ') first++;
      return text.substring(0, first) + "\\" + text.substring(first);
    }
    Matcher ordered = Pattern.compile("^( {0,3}[0-9]{1,9})([.)])([ \t].*)$").matcher(text);
    return ordered.matches() ? ordered.group(1) + "\\" + ordered.group(2) + ordered.group(3) : text;
  }

  private static int number(AttributeSet a, String key, int fallback) {
    Object n = a.getAttribute(key);
    return n instanceof Number ? ((Number) n).intValue() : fallback;
  }

  private static String escape(String s) {
    return s.replace("\\", "\\\\")
        .replace("*", "\\*")
        .replace("_", "\\_")
        .replace("`", "\\`")
        .replace("[", "\\[")
        .replace("~", "\\~");
  }
}
