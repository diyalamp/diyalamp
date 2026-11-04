package com.diyalamp.desktop;

import java.nio.file.*;
import java.util.*;
import javax.swing.text.*;

/** Reopen saved styled documents, including edits that invalidate source-line preservation. */
public final class MarkdownRegressionTests {
  private static int checks;

  private static void check(boolean ok, String why) {
    checks++;
    if (!ok) throw new AssertionError(why);
  }

  private static String text(StyledDocument d) throws BadLocationException {
    return d.getText(0, d.getLength());
  }

  private static String signature(StyledDocument d) throws BadLocationException {
    StringBuilder out = new StringBuilder(text(d)).append('\0');
    for (int i = 0; i < d.getLength(); i++) {
      AttributeSet a = d.getCharacterElement(i).getAttributes();
      AttributeSet p = d.getParagraphElement(i).getAttributes();
      boolean visible = !Character.isWhitespace(d.getText(i, 1).charAt(0));
      out.append(visible && StyleConstants.isBold(a))
          .append(':')
          .append(visible && StyleConstants.isItalic(a))
          .append(':')
          .append(visible && StyleConstants.isStrikeThrough(a))
          .append(':')
          .append(a.getAttribute(Markdown.INLINE_CODE))
          .append(':')
          .append(a.getAttribute(Markdown.LINK))
          .append(':')
          .append(a.getAttribute(Markdown.LINK_TITLE))
          .append(':')
          .append(p.getAttribute(Markdown.KIND))
          .append('|');
    }
    return out.toString();
  }

  private static void unchanged(String source) {
    check(
        source.equals(Markdown.write(Markdown.read(source))),
        "Unchanged source structure must survive: " + source);
  }

  private static void editAndReopen(String source) throws Exception {
    DefaultStyledDocument doc = Markdown.read(source);
    doc.insertString(doc.getLength() - 1, "!", null);
    String saved = Markdown.write(doc);
    DefaultStyledDocument reopened = Markdown.read(saved);
    check(
        signature(doc).equals(signature(reopened)),
        "Saved text and formatting must reopen after an edit: "
            + source
            + "\nSaved: "
            + saved
            + "\nBefore: "
            + signature(doc)
            + "\nAfter: "
            + signature(reopened));
    check(saved.equals(Markdown.write(reopened)), "The second serialization must stabilize");
  }

  private static SimpleAttributeSet formatting(int mask) {
    SimpleAttributeSet attributes = new SimpleAttributeSet();
    StyleConstants.setBold(attributes, (mask & 1) != 0);
    StyleConstants.setItalic(attributes, (mask & 2) != 0);
    StyleConstants.setStrikeThrough(attributes, (mask & 4) != 0);
    return attributes;
  }

  public static void main(String[] args) throws Exception {
    for (String source :
        List.of(
            "***both***\n",
            "**bold *both* bold**\n",
            "*italic **both** italic*\n",
            "~~strike **strong**~~\n",
            "`code` and ``a ` b``\n",
            "`` `edge` ``\n",
            "\\# Literal heading\n",
            "\\- Literal list\n",
            "\\> Literal quote\n",
            "\\~\\~literal\\~\\~\n",
            "file_name_with_underscores\n",
            "[Wikipedia](https://en.wikipedia.org/wiki/Function_(mathematics) \"A title\")\n",
            "![alt](https://example.com/remote.png \"image title\")\n",
            "<script>alert('x')</script>\n")) {
      unchanged(source);
      editAndReopen(source);
    }
    for (String source :
        List.of(
            "Before\n\n```java\n```\n\nAfter\n",
            "```java\none\n```\n```python\ntwo\n```\n",
            "````markdown\n```java\ntext\n```\n````\n",
            "~~~markdown\n```java\ntext\n```\n~~~\n",
            "```java\ncontent\n",
            "```\n",
            "```\n\n```\n",
            "  ```java\n  content\n  ```\n",
            "- parent\n  - child **bold**\n- next\n")) unchanged(source);

    // Editing a different paragraph must not rewrite unsupported constructs in the source note.
    String retained = "[[Other note]]\n\n- parent\n  - child\n\nTail\n";
    DefaultStyledDocument retainedDoc = Markdown.read(retained);
    retainedDoc.insertString(retainedDoc.getLength() - 1, "!", null);
    check(
        Markdown.write(retainedDoc).startsWith("[[Other note]]\n\n- parent\n  - child\n"),
        "Unchanged paragraphs retain original Markdown on unrelated edits");

    // Native toolbar formatting can produce combinations that were never parsed from source.
    for (int mask = 1; mask < 8; mask++) {
      DefaultStyledDocument nativeDoc = new DefaultStyledDocument();
      SimpleAttributeSet a = new SimpleAttributeSet();
      StyleConstants.setBold(a, (mask & 1) != 0);
      StyleConstants.setItalic(a, (mask & 2) != 0);
      StyleConstants.setStrikeThrough(a, (mask & 4) != 0);
      nativeDoc.insertString(0, "both", a);
      nativeDoc.insertString(nativeDoc.getLength(), "\n", null);
      nativeDoc.setParagraphAttributes(0, 1, Markdown.paragraph("paragraph", 1), true);
      check(
          signature(nativeDoc).equals(signature(Markdown.read(Markdown.write(nativeDoc)))),
          "Native formatting combination must reopen: " + mask);
    }

    // Ambiguous delimiter boundaries must fail before any file bytes can be replaced.
    int rejected = 0;
    for (int first = 0; first < 8; first++) {
      for (int second = 0; second < 8; second++) {
        DefaultStyledDocument adjacent = new DefaultStyledDocument();
        adjacent.insertString(0, "alpha", formatting(first));
        adjacent.insertString(5, "beta", formatting(second));
        adjacent.insertString(9, "\n", null);
        adjacent.setParagraphAttributes(0, 1, Markdown.paragraph("paragraph", 1), true);
        try {
          String saved = Markdown.write(adjacent);
          check(
              signature(adjacent).equals(signature(Markdown.read(saved))),
              "Adjacent formatting must reopen correctly when accepted");
        } catch (IllegalStateException e) {
          rejected++;
          check(
              e.getMessage().contains("Separate or simplify"),
              "Unrepresentable formatting reports an actionable save error");
        }
      }
    }
    check(rejected > 0, "Lossy adjacent formatting must be rejected");
    Path root = Files.createTempDirectory("diyalamp-format-save-");
    NoteStore store = new NoteStore(root);
    Path note = store.create("Original", "Original bytes\n");
    String fingerprint = store.load(note).fingerprint();
    DefaultStyledDocument unsafe = new DefaultStyledDocument();
    unsafe.insertString(0, "a", formatting(0));
    unsafe.insertString(1, "b", formatting(5));
    unsafe.insertString(2, "\n", null);
    unsafe.setParagraphAttributes(0, 1, Markdown.paragraph("paragraph", 1), true);
    boolean prevented = false;
    try {
      store.save(note, Markdown.write(unsafe), fingerprint);
    } catch (IllegalStateException e) {
      prevented = true;
    }
    check(
        prevented && Files.readString(note).equals("Original bytes\n"),
        "A lossy serialization never reaches disk replacement");

    // A newly inserted closing delimiter is content, not an accidental end of the code block.
    DefaultStyledDocument collision = Markdown.read("```text\nline\n```\n");
    collision.insertString(0, "```\n", null);
    String collisionSaved = Markdown.write(collision);
    check(
        collisionSaved.startsWith("````text\n```\n"), "Code fences grow around delimiter content");
    check(
        text(collision).equals(text(Markdown.read(collisionSaved))),
        "Code delimiter content survives a save and reopen");

    DefaultStyledDocument exitingCode = Markdown.read("```java\none\ntwo\n");
    Element secondLine = exitingCode.getDefaultRootElement().getElement(1);
    exitingCode.setParagraphAttributes(
        secondLine.getStartOffset(), 1, Markdown.paragraph("paragraph", 1), true);
    check(
        Markdown.write(exitingCode).equals("```java\none\n```\ntwo\n"),
        "Leaving an originally unclosed code block inserts a closing fence before later text");

    DefaultStyledDocument linkDoc = Markdown.read("[link](https://example.com \"Kept title\")\n");
    linkDoc.insertString(linkDoc.getLength() - 1, "!", null);
    check(Markdown.write(linkDoc).contains("\"Kept title\""), "Link titles are retained on edits");
    check(
        text(Markdown.read("![remote](https://example.com/private.png)\n")).contains("![remote]"),
        "Remote image markup remains literal instead of being fetched");
    System.out.println("Passed " + checks + " Markdown regression checks.");
  }
}
