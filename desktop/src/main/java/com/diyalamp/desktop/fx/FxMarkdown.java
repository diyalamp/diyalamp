package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.Markdown;
import java.util.*;
import javax.swing.text.AttributeSet;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import org.fxmisc.richtext.model.ReadOnlyStyledDocument;
import org.fxmisc.richtext.model.ReadOnlyStyledDocumentBuilder;
import org.fxmisc.richtext.model.SegmentOps;
import org.fxmisc.richtext.model.StyledSegment;

/** Semantic bridge only: Swing never creates a control or paints text in the JavaFX app. */
final class FxMarkdown {
  record Block(Map<Object, Object> values) {
    Block { values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    String kind() { return Objects.toString(values.get(Markdown.KIND), "paragraph"); }
    int level() { return values.get(Markdown.LEVEL) instanceof Number n ? n.intValue() : 1; }
    int number() { return values.get(Markdown.NUMBER) instanceof Number n ? n.intValue() : 1; }
    boolean checked() { return Boolean.TRUE.equals(values.get(Markdown.CHECKED)); }
    SimpleAttributeSet attributes() { return attributesOf(values); }
    static Block text() { return new Block(copy(Markdown.paragraph("paragraph", 1))); }
  }

  record Span(Map<Object, Object> values, String blockKind, int level) {
    Span { values = Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    boolean flag(Object key) { return Boolean.TRUE.equals(values.get(key)); }
    String link() { return values.get(Markdown.LINK) instanceof String s ? s : null; }
    String noteLink() { return values.get(Markdown.WIKI_TARGET) instanceof String s ? s : null; }
    SimpleAttributeSet attributes() { return attributesOf(values); }
    static Span plain(Block block) { return new Span(Map.of(), block.kind(), block.level()); }
    static Span of(AttributeSet attributes, Block block) {
      return new Span(copy(attributes), block.kind(), block.level());
    }
  }

  static Map<Object, Object> copy(AttributeSet attributes) {
    Map<Object, Object> result = new LinkedHashMap<>();
    Enumeration<?> names = attributes.getAttributeNames();
    while (names.hasMoreElements()) {
      Object name = names.nextElement();
      // Parent document references must not escape into immutable RichTextFX style values.
      if (name != StyleConstants.ResolveAttribute) result.put(name, attributes.getAttribute(name));
    }
    return result;
  }

  private static SimpleAttributeSet attributesOf(Map<Object, Object> values) {
    SimpleAttributeSet result = new SimpleAttributeSet();
    values.forEach(result::addAttribute);
    return result;
  }

  static ReadOnlyStyledDocument<Block, String, Span> read(String source) {
    return fromSemantic(Markdown.read(source));
  }

  static ReadOnlyStyledDocument<Block, String, Span> fromSemantic(DefaultStyledDocument source) {
    var builder = new ReadOnlyStyledDocumentBuilder<Block, String, Span>(
        SegmentOps.styledTextOps(), Block.text());
    Element root = source.getDefaultRootElement();
    int added = 0;
    try {
      for (int i = 0; i < root.getElementCount(); i++) {
        Element paragraph = root.getElement(i);
        int start = paragraph.getStartOffset();
        if (start >= source.getLength()) break;
        int end = Math.min(source.getLength(), paragraph.getEndOffset());
        if (end > start && "\n".equals(source.getText(end - 1, 1))) end--;
        Block block = new Block(copy(paragraph.getAttributes()));
        List<StyledSegment<String, Span>> segments = new ArrayList<>();
        while (start < end) {
          Element run = source.getCharacterElement(start);
          int next = Math.min(end, run.getEndOffset());
          segments.add(new StyledSegment<>(source.getText(start, next - start), Span.of(run.getAttributes(), block)));
          start = next;
        }
        if (segments.isEmpty()) segments.add(new StyledSegment<>("", Span.plain(block)));
        builder.addParagraph(segments, block);
        added++;
      }
      if (added == 0) builder.addParagraph("", Span.plain(Block.text()), Block.text());
      return builder.build();
    } catch (javax.swing.text.BadLocationException error) {
      throw new IllegalStateException("Could not decode the Markdown note", error);
    }
  }

  static DefaultStyledDocument semantic(org.fxmisc.richtext.model.StyledDocument<Block, String, Span> source) {
    DefaultStyledDocument result = new DefaultStyledDocument();
    try {
      for (var paragraph : source.getParagraphs()) {
        int start = result.getLength();
        for (var segment : paragraph.getStyledSegments())
          result.insertString(result.getLength(), segment.getSegment(), segment.getStyle().attributes());
        result.insertString(result.getLength(), "\n", null);
        result.setParagraphAttributes(start, Math.max(1, result.getLength() - start),
            paragraph.getParagraphStyle().attributes(), true);
      }
      return result;
    } catch (javax.swing.text.BadLocationException error) {
      throw new IllegalStateException("Could not encode the Markdown note", error);
    }
  }

  static String write(org.fxmisc.richtext.model.StyledDocument<Block, String, Span> source) {
    if (source.length() == 0 && source.getParagraphs().size() == 1) {
      Block block = source.getParagraph(0).getParagraphStyle();
      if (block.kind().equals("paragraph") && !block.values().containsKey(Markdown.SOURCE_LINE)) return "";
    }
    return Markdown.write(semantic(source));
  }

  /** Reuses unchanged immutable paragraphs instead of rebuilding a whole styled model per save. */
  static final class Writer {
    private Map<org.fxmisc.richtext.model.Paragraph<Block, String, Span>, String> lines = new IdentityHashMap<>();
    private Map<CodeChunk, String> fences = new HashMap<>();
    private int serializedChunks;
    private record CodeChunk(List<org.fxmisc.richtext.model.Paragraph<Block, String, Span>> paragraphs, boolean followed) {}

    void seed(org.fxmisc.richtext.model.StyledDocument<Block, String, Span> source) {
      lines.clear(); fences.clear();
      for (var paragraph : source.getParagraphs()) {
        Block block = paragraph.getParagraphStyle();
        if (!block.kind().equals("code") && block.values().get(Markdown.SOURCE_LINE) instanceof String original)
          lines.put(paragraph, original + "\n");
      }
      // Code fences need group context. Warm them once while opening, rather than while typing.
      write(source);
    }

    int serializedChunks() { return serializedChunks; }

    String write(org.fxmisc.richtext.model.StyledDocument<Block, String, Span> source) {
      if (source.length() == 0 && source.getParagraphs().size() == 1) {
        Block block = source.getParagraph(0).getParagraphStyle();
        if (block.kind().equals("paragraph") && !block.values().containsKey(Markdown.SOURCE_LINE)) return "";
      }
      var nextLines = new IdentityHashMap<org.fxmisc.richtext.model.Paragraph<Block, String, Span>, String>();
      var nextFences = new HashMap<CodeChunk, String>();
      StringBuilder result = new StringBuilder();
      List<org.fxmisc.richtext.model.Paragraph<Block, String, Span>> paragraphs = source.getParagraphs();
      for (int index = 0; index < paragraphs.size();) {
        var paragraph = paragraphs.get(index);
        Block block = paragraph.getParagraphStyle();
        if (!block.kind().equals("code")) {
          String line = lines.get(paragraph);
          if (line == null) line = serialize(List.of(paragraph), false);
          nextLines.put(paragraph, line);
          result.append(line); index++;
        } else {
          int end = index + 1;
          Object identity = block.values().get(Markdown.FENCE_ID);
          String language = Objects.toString(block.values().get(Markdown.LANG), "");
          while (end < paragraphs.size()) {
            Block following = paragraphs.get(end).getParagraphStyle();
            if (!following.kind().equals("code")
                || !Objects.equals(identity, following.values().get(Markdown.FENCE_ID))
                || !language.equals(Objects.toString(following.values().get(Markdown.LANG), ""))) break;
            end++;
          }
          CodeChunk key = new CodeChunk(List.copyOf(paragraphs.subList(index, end)), end < paragraphs.size());
          String code = fences.get(key);
          if (code == null) code = serialize(key.paragraphs(), key.followed());
          nextFences.put(key, code); result.append(code); index = end;
        }
      }
      // Retain only live paragraph identities, not one cached copy for every past keystroke.
      lines = nextLines; fences = nextFences;
      return result.toString();
    }

    private String serialize(List<org.fxmisc.richtext.model.Paragraph<Block, String, Span>> paragraphs, boolean followed) {
      serializedChunks++;
      var builder = new ReadOnlyStyledDocumentBuilder<Block, String, Span>(SegmentOps.styledTextOps(), Block.text());
      for (var paragraph : paragraphs) builder.addParagraph(paragraph.getStyledSegments(), paragraph.getParagraphStyle());
      boolean boundary = followed && "".equals(paragraphs.getFirst().getParagraphStyle().values().get(Markdown.FENCE_CLOSE));
      String marker = "diyalamp serialization boundary";
      if (boundary) builder.addParagraph(marker, Span.plain(Block.text()), Block.text());
      String written = Markdown.write(semantic(builder.build()));
      return boundary ? written.substring(0, written.length() - marker.length() - 1) : written;
    }
  }

  private FxMarkdown() {}
}
