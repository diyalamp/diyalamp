package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.Markdown;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextFlow;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Element;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyledTextArea;
import org.fxmisc.richtext.TextExt;
import org.fxmisc.richtext.model.ReadOnlyStyledDocument;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.reactfx.Subscription;

/** Rendered Markdown on RichTextFX's maintained text, selection, caret, and undo implementation. */
public final class FxWritingPane {
  private final StyledTextArea<FxMarkdown.Block, FxMarkdown.Span> area;
  private final VirtualizedScrollPane<?> viewport;
  private final StackPane editorRoot;
  private final Rectangle focusLineMask = new Rectangle();
  private ReadOnlyDoubleProperty focusLineProgress;
  private Scene focusScene;
  private final Runnable focusLayoutListener = this::updateFocusLine;
  private final ChangeListener<Number> focusProgressListener = (observable, previous, value) -> updateFocusProgress(value.doubleValue());
  private double focusProgress;
  private double lastFocusTop = Double.NaN, lastFocusBottom, lastFocusLine, lastFocusHeight;
  private final ContextMenu slash = FxTheme.contextMenu();
  private int slashCaret = -1;
  private final Subscription richSubscription, plainSubscription;
  private final FxMarkdown.Writer writer = new FxMarkdown.Writer();
  private Runnable onChange = () -> {};
  private Consumer<String> onNoteLink = target -> {};
  private long revision, serializedRevision = -1;
  private String serialized = "";
  private static final Pattern BLOCK_SHORTCUT = Pattern.compile("^(#{1,6}) $|^([-*+]) $|^(\\d+)\\. $|^> $|^(?:[-*+] )?\\[ \\] $|^```(?:[a-zA-Z0-9_-]+)? $");
  private static final Pattern INLINE_SHORTCUT = Pattern.compile("(\\*\\*[^*\\n]+\\*\\*|~~[^~\\n]+~~|`[^`\\n]+`|(?<!\\*)\\*[^*\\n]+\\*)(?: )?$");
  private static final Map<String, Font> FONTS = new HashMap<>();
  private static final KeyCombination BOLD_KEY = new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN);
  private static final KeyCombination ITALIC_KEY = new KeyCodeCombination(KeyCode.I, KeyCombination.SHORTCUT_DOWN);
  private static final KeyCombination CODE_KEY = new KeyCodeCombination(KeyCode.BACK_QUOTE, KeyCombination.SHORTCUT_DOWN);
  private static final KeyCombination LINK_KEY = new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN);
  private static final KeyCombination PASTE_KEY = new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);
  private static final KeyCombination CHECKLIST_KEY = new KeyCodeCombination(KeyCode.ENTER,
      KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
  private static final KeyCombination OPEN_NOTE_KEY = new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN);
  private boolean changing, disposed, shortcutScheduled;
  private String original = "";
  private record BlockCommand(String label, String kind, int level, String icon) {}
  private static final List<BlockCommand> BLOCKS = List.of(
      new BlockCommand("Text", "paragraph", 1, "type"),
      new BlockCommand("Heading 1", "heading", 1, "heading-1"),
      new BlockCommand("Heading 2", "heading", 2, "heading-2"),
      new BlockCommand("Heading 3", "heading", 3, "heading-3"),
      new BlockCommand("Bullet list", "bullet", 1, "list"),
      new BlockCommand("Numbered list", "ordered", 1, "list-ordered"),
      new BlockCommand("Checklist", "task", 1, "list-checks"),
      new BlockCommand("Quote", "quote", 1, "quote"),
      new BlockCommand("Code block", "code", 1, "code"),
      new BlockCommand("Divider", "divider", 1, "minus"));

  public FxWritingPane(String markdown) {
    FxMarkdown.Block text = FxMarkdown.Block.text();
    area = new StyledTextArea<>(text, FxWritingPane::applyBlock,
        FxMarkdown.Span.plain(text), FxWritingPane::applySpan);
    area.getStyleClass().add("writing-area");
    area.setWrapText(true);
    area.widthProperty().addListener((observable, previous, width) -> gutters());
    gutters();
    area.setAccessibleRole(AccessibleRole.TEXT_AREA);
    area.setAccessibleText("Markdown writing page");
    area.setAccessibleHelp("Rendered Markdown. Use the Format menu, Command K for a link, or slash for blocks.");
    area.setParagraphGraphicFactory(this::marker);
    viewport = new VirtualizedScrollPane<>(area);
    viewport.getStyleClass().add("diyalamp-editor");
    viewport.setMinWidth(0);
    viewport.setMinHeight(0);
    viewport.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    focusLineMask.getStyleClass().add("zen-focus-mask");
    focusLineMask.setManaged(false);
    focusLineMask.setMouseTransparent(true);
    focusLineMask.setVisible(false);
    editorRoot = new StackPane(viewport, focusLineMask) {
      @Override protected void layoutChildren() {
        super.layoutChildren();
        updateFocusLine();
      }
    };
    editorRoot.setMinSize(0, 0);
    editorRoot.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    editorRoot.sceneProperty().addListener((observable, previous, current) -> {
      slash.hide();
      syncFocusLayoutListener();
    });
    area.caretBoundsProperty().addListener((observable, previous, current) -> updateFocusLine());
    area.estimatedScrollYProperty().addListener((observable, previous, current) -> updateFocusLine());
    area.addEventFilter(KeyEvent.KEY_PRESSED, this::key);
    area.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
      // Popup auto-hide exempts its anchor node, which is the whole editor.
      slash.hide();
      area.setTextInsertionStyle(null);
    });
    area.addEventFilter(ScrollEvent.SCROLL, event -> slash.hide());
    area.caretPositionProperty().addListener((observable, previous, current) -> {
      if (slash.isShowing() && current.intValue() != slashCaret) slash.hide();
      leaveNoteLinkAtBoundary();
      updateFocusLine();
    });
    area.addEventHandler(MouseEvent.MOUSE_RELEASED, event -> leaveNoteLinkAtBoundary());
    area.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
      if (event.getButton() != MouseButton.PRIMARY || !event.isStillSincePress() || event.isShiftDown()
          || event.getClickCount() != 1 || area.getSelection().getLength() != 0) return;
      area.hit(event.getX(), event.getY()).getCharacterIndex().ifPresent(index -> {
        if (openNoteLink(index)) event.consume();
      });
    });
    area.setContextMenu(contextMenu());
    slash.getStyleClass().add("slash-menu");
    slash.setAutoHide(true);
    slash.setConsumeAutoHidingEvents(false);
    for (BlockCommand item : BLOCKS) {
      MenuItem command = new MenuItem(item.label(), FxTheme.icon(item.icon(), 16));
      command.setOnAction(event -> {
        if (disposed || slashCaret <= 0 || area.getCaretPosition() != slashCaret
            || !area.getText(slashCaret - 1, slashCaret).equals("/")) return;
        area.replaceText(slashCaret - 1, slashCaret, "");
        block(item.kind(), item.level());
        focus();
      });
      slash.getItems().add(command);
    }
    load(markdown);
    richSubscription = area.multiRichChanges().subscribe(change -> {
      revision++;
      if (!changing && !disposed) onChange.run();
    });
    plainSubscription = area.plainTextChanges().subscribe(change -> {
      slash.hide();
      if (changing || disposed || shortcutScheduled || area.getUndoManager().isPerformingAction()) return;
      shortcutScheduled = true;
      Platform.runLater(() -> {
        shortcutScheduled = false;
        if (!disposed && !changing) convertShortcuts();
      });
    });
  }

  public Node node() { return editorRoot; }
  /** Fades around the actual visual caret line without changing the editor, its layout, or document. */
  public void setFocusLineProgress(ReadOnlyDoubleProperty progress) {
    if (focusLineProgress != null) focusLineProgress.removeListener(focusProgressListener);
    focusLineProgress = progress;
    if (progress != null) progress.addListener(focusProgressListener);
    updateFocusProgress(progress == null ? 0 : progress.get());
  }

  private void updateFocusProgress(double progress) {
    focusProgress = Double.isFinite(progress) ? Math.max(0, Math.min(1, progress)) : 0;
    focusLineMask.setVisible(focusProgress > 0);
    focusLineMask.setOpacity(focusProgress);
    syncFocusLayoutListener();
    updateFocusLine();
  }

  private void syncFocusLayoutListener() {
    Scene next = focusProgress > 0 && !disposed ? editorRoot.getScene() : null;
    if (next == focusScene) return;
    if (focusScene != null) focusScene.removePostLayoutPulseListener(focusLayoutListener);
    focusScene = next;
    // Only this unmanaged overlay's paint/geometry changes here; no control or
    // text layout is invalidated after JavaFX has finished its layout pass.
    if (focusScene != null) focusScene.addPostLayoutPulseListener(focusLayoutListener);
  }

  private void updateFocusLine() {
    if (editorRoot == null || focusProgress <= 0 || editorRoot.getScene() == null) return;
    Bounds frame = editorRoot.sceneToLocal(area.localToScene(area.getLayoutBounds()));
    if (frame.getWidth() <= 0 || frame.getHeight() <= 0) return;
    focusLineMask.setX(frame.getMinX());
    focusLineMask.setY(frame.getMinY());
    focusLineMask.setWidth(frame.getWidth());
    focusLineMask.setHeight(frame.getHeight());
    // Query fresh control geometry: caretBoundsProperty caches screen coordinates
    // and can remain unchanged when scrolling within the same wrapped paragraph.
    Optional<Bounds> caret = area.getCaretBoundsOnScreen(area.getCaretSelectionBind().getUnderlyingCaret());
    // A newly laid-out, unfocused note can have no visible CaretNode yet. Ask the
    // maintained control for that insertion point's geometry, without moving it.
    if (caret.isEmpty() && area.getParagraphBoundsOnScreen(area.getCurrentParagraph()).isPresent())
      caret = area.getCharacterBoundsOnScreen(area.getCaretPosition(), area.getCaretPosition());
    caret = caret.map(editorRoot::screenToLocal);
    if (caret.isEmpty()) {
      if (!Color.BLACK.equals(focusLineMask.getFill())) focusLineMask.setFill(Color.BLACK);
      return;
    }
    double top = caret.get().getMinY() - frame.getMinY() - 2;
    double bottom = caret.get().getMaxY() - frame.getMinY() + 2;
    double line = Math.max(FxTheme.TEXT_SIZE, caret.get().getHeight());
    double height = frame.getHeight();
    if (focusLineMask.getFill() instanceof LinearGradient && top == lastFocusTop && bottom == lastFocusBottom
        && line == lastFocusLine && height == lastFocusHeight) return;
    lastFocusTop = top; lastFocusBottom = bottom; lastFocusLine = line; lastFocusHeight = height;
    double[] positions = {top - 4 * line, top - 3 * line, top - 2 * line, top - line,
        top, bottom, bottom + line, bottom + 2 * line, bottom + 3 * line, bottom + 4 * line};
    double[] opacity = {1, .9, .66, .34, 0, 0, .34, .66, .9, 1};
    List<Stop> stops = new ArrayList<>();
    stops.add(new Stop(0, focusShade(shadeAt(0, positions, opacity))));
    for (int i = 0; i < positions.length; i++) if (positions[i] > 0 && positions[i] < height)
      stops.add(new Stop(positions[i] / height, focusShade(opacity[i])));
    stops.add(new Stop(1, focusShade(shadeAt(height, positions, opacity))));
    focusLineMask.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE, stops));
  }

  private static Color focusShade(double opacity) {
    return Color.color(0, 0, 0, opacity);
  }

  private static double shadeAt(double y, double[] positions, double[] opacity) {
    if (y <= positions[0]) return opacity[0];
    for (int i = 1; i < positions.length; i++) if (y < positions[i]) {
      double fraction = (y - positions[i - 1]) / (positions[i] - positions[i - 1]);
      return opacity[i - 1] + fraction * (opacity[i] - opacity[i - 1]);
    }
    return opacity[opacity.length - 1];
  }
  public void focus() { area.requestFocus(); }
  public void setOnChange(Runnable handler) { onChange = handler == null ? () -> {} : handler; }
  public void setOnNoteLink(Consumer<String> handler) { onNoteLink = handler == null ? target -> {} : handler; }
  public int textLength() { return area.getLength(); }
  public String recoveryText() { return area.getText(); }
  public String markdown() {
    if (serializedRevision == revision) return serialized;
    String next = area.getUndoManager().isAtMarkedPosition() ? original : writer.write(area.getDocument());
    serialized = next; serializedRevision = revision;
    return next;
  }

  public void load(String markdown) {
    String next = markdown == null ? "" : markdown;
    if (next.equals(original) && area.getUndoManager().isAtMarkedPosition() && serializedRevision >= 0) return;
    boolean previous = changing;
    int anchor = area.getAnchor(), caret = area.getCaretPosition();
    Double scroll = area.estimatedScrollYProperty().getValue();
    changing = true;
    try {
      var document = FxMarkdown.read(next);
      area.replace(0, area.getLength(), document);
      area.selectRange(Math.min(anchor, area.getLength()), Math.min(caret, area.getLength()));
      if (scroll != null) area.scrollYToPixel(scroll);
      area.setTextInsertionStyle(null);
      area.getUndoManager().forgetHistory();
      area.getUndoManager().mark();
      original = next;
      writer.seed(document);
      serialized = next; serializedRevision = revision;
    } finally { changing = previous; }
  }

  public void dispose() {
    if (disposed) return;
    disposed = true;
    setFocusLineProgress(null);
    slash.hide();
    richSubscription.unsubscribe();
    plainSubscription.unsubscribe();
    area.dispose();
    onChange = () -> {};
    onNoteLink = target -> {};
  }

  public void undo() { if (canUndo()) { area.undo(); area.requestFollowCaret(); } }
  public void redo() { if (canRedo()) { area.redo(); area.requestFollowCaret(); } }
  public boolean canUndo() { return area.getUndoManager().isUndoAvailable(); }
  public boolean canRedo() { return area.getUndoManager().isRedoAvailable(); }
  public void selectAll() { area.selectAll(); }
  public void copy() { area.copy(); }
  public void cut() { area.cut(); }
  public boolean find(String query) {
    if (query == null || query.isBlank()) return false;
    String source = area.getText().toLowerCase(Locale.ROOT);
    String needle = query.toLowerCase(Locale.ROOT);
    int found = source.indexOf(needle, area.getSelection().getEnd());
    if (found < 0) found = source.indexOf(needle);
    if (found < 0) return false;
    area.selectRange(found, found + query.length());
    focus(); area.requestFollowCaret();
    return true;
  }

  public void revealSearchMatch(String markdown, int line, int start, int end) {
    if (markdown == null) return;
    Markdown.SearchLocation location = Markdown.locateSearchMatch(markdown, line, start, end);
    if (location == null) return;
    int row = Math.min(location.paragraph(), area.getParagraphs().size() - 1);
    int paragraphStart = area.getAbsolutePosition(row, 0);
    String text = area.getParagraph(row).getText();
    if (row == location.paragraph() && location.end() > location.start()
        && location.end() <= text.length()
        && text.regionMatches(location.start(), markdown, start, end - start)) {
      area.selectRange(paragraphStart + location.start(), paragraphStart + location.end());
    } else {
      area.moveTo(paragraphStart);
    }
    focus();
    area.requestFollowCaret();
  }

  public void paste() {
    Clipboard clipboard = Clipboard.getSystemClipboard();
    if (!clipboard.hasString()) return;
    pasteText(clipboard.getString());
  }

  void pasteText(String text) {
    if (text == null || text.isEmpty()) return;
    if (currentBlock().kind().equals("code")) area.replaceSelection(text);
    else {
      Map<Object, Object> fenceIds = new HashMap<>();
      var fragment = FxMarkdown.read(text).mapParagraphs(paragraph -> {
        FxMarkdown.Block block = paragraph.getParagraphStyle();
        if (!block.kind().equals("code")) return paragraph;
        Map<Object, Object> values = new LinkedHashMap<>(block.values());
        Object identity = fenceIds.computeIfAbsent(values.get(Markdown.FENCE_ID),
            ignored -> UUID.randomUUID().toString());
        values.put(Markdown.FENCE_ID, identity);
        return paragraph.setParagraphStyle(new FxMarkdown.Block(values));
      });
      // A file's final newline is implicit in the editor, but a pasted newline is an insertion.
      if (text.endsWith("\n") || text.endsWith("\r")) {
        FxMarkdown.Block block = FxMarkdown.Block.text();
        fragment = fragment.concat(ReadOnlyStyledDocument.fromString("\n", block,
            FxMarkdown.Span.plain(block), org.fxmisc.richtext.model.SegmentOps.styledTextOps()));
      }
      area.replaceSelection(fragment);
    }
    area.requestFollowCaret();
  }

  public void format(String kind) {
    Object attribute = switch (kind) {
      case "bold" -> StyleConstants.Bold;
      case "italic" -> StyleConstants.Italic;
      case "strike" -> StyleConstants.StrikeThrough;
      case "code" -> Markdown.INLINE_CODE;
      default -> throw new IllegalArgumentException("Unknown format: " + kind);
    };
    int start = area.getSelection().getStart(), end = area.getSelection().getEnd();
    FxMarkdown.Span at = start == end ? area.getTextStyleForInsertionAt(start) : area.getStyleOfChar(start);
    boolean enabled = !at.flag(attribute);
    if (start == end) {
      SimpleAttributeSet attributes = at.attributes();
      attributes.addAttribute(attribute, enabled);
      area.setTextInsertionStyle(FxMarkdown.Span.of(attributes, currentBlock()));
      focus();
      return;
    }
    var styles = new StyleSpansBuilder<FxMarkdown.Span>();
    for (var span : area.getStyleSpans(start, end)) {
      SimpleAttributeSet attributes = span.getStyle().attributes();
      attributes.addAttribute(attribute, enabled);
      FxMarkdown.Span changed = new FxMarkdown.Span(FxMarkdown.copy(attributes),
          span.getStyle().blockKind(), span.getStyle().level());
      styles.add(changed, span.getLength());
    }
    area.getUndoManager().preventMerge();
    area.setStyleSpans(start, styles.create());
    area.getUndoManager().preventMerge();
    focus();
  }

  public void block(String kind, int level) {
    if (kind.equals("divider")) { divider(); return; }
    int start = area.getSelection().getStart(), end = area.getSelection().getEnd();
    mutate(document -> {
      Element root = document.getDefaultRootElement();
      int first = root.getElementIndex(start), last = root.getElementIndex(Math.max(start, end - 1));
      for (int index = first; index <= last; index++) {
        Element paragraph = root.getElement(index);
        SimpleAttributeSet attributes = Markdown.paragraph(kind, level);
        attributes.addAttribute(Markdown.NUMBER,
            "ordered".equals(paragraph.getAttributes().getAttribute(Markdown.KIND))
                ? paragraph.getAttributes().getAttribute(Markdown.NUMBER) : index - first + 1);
        attributes.addAttribute(Markdown.CHECKED,
            "task".equals(paragraph.getAttributes().getAttribute(Markdown.KIND))
                && Boolean.TRUE.equals(paragraph.getAttributes().getAttribute(Markdown.CHECKED)));
        document.setParagraphAttributes(paragraph.getStartOffset(), 1, attributes, true);
      }
    }, area.getAnchor(), area.getCaretPosition());
  }

  private void divider() {
    int row = area.getCurrentParagraph();
    int start = area.getAbsolutePosition(row, 0);
    int length = area.getParagraph(row).length();
    boolean empty = area.getParagraph(row).getText().isBlank();
    int position = empty ? start : start + length + 1;
    mutate(document -> {
      if (empty) {
        remove(document, start, length);
        insert(document, start, "────────────────────────", null);
      } else insert(document, start + length, "\n────────────────────────", null);
      document.setParagraphAttributes(position, 1, Markdown.paragraph("divider", 1), true);
    }, position, position);
  }

  public void toggleChecklist() { toggleChecklist(area.getCurrentParagraph()); }
  private void toggleChecklist(int index) {
    if (index < 0 || index >= area.getParagraphs().size()) return;
    FxMarkdown.Block block = area.getParagraph(index).getParagraphStyle();
    if (!block.kind().equals("task")) return;
    int position = area.getAbsolutePosition(index, 0);
    mutate(document -> {
      SimpleAttributeSet attributes = new SimpleAttributeSet();
      attributes.addAttribute(Markdown.CHECKED, !block.checked());
      document.setParagraphAttributes(position, 1, attributes, false);
    }, area.getAnchor(), area.getCaretPosition());
  }

  public void insertLink() {
    FxMarkdown.Span style = area.getTextStyleForInsertionAt(area.getCaretPosition());
    TextInputDialog dialog = new TextInputDialog(style.link() == null ? "https://" : style.link());
    dialog.setTitle("Insert or edit link");
    dialog.setHeaderText(null);
    dialog.setContentText("Destination");
    FxDialogs.style(dialog, area.getScene() == null ? null : area.getScene().getWindow());
    dialog.showAndWait().ifPresent(this::insertLink);
    focus();
  }

  void insertLink(String target) {
    if (target == null || target.isBlank()) return;
    String destination = target.strip();
    int start = area.getSelection().getStart(), end = area.getSelection().getEnd();
    if (start == end) {
      var at = area.getDocument();
      int probe = start < area.getLength() && at.getStyleOfChar(start).link() != null ? start : Math.max(0, start - 1);
      String existing = area.getLength() > 0 ? at.getStyleOfChar(probe).link() : null;
      if (existing != null) {
        start = probe; end = probe + 1;
        while (start > 0 && existing.equals(at.getStyleOfChar(start - 1).link())) start--;
        while (end < area.getLength() && existing.equals(at.getStyleOfChar(end).link())) end++;
      }
    }
    final int from = start, to = end;
    int caret = start == end ? start + destination.length() : end;
    mutate(document -> {
      SimpleAttributeSet attributes = new SimpleAttributeSet();
      attributes.addAttribute(Markdown.LINK, destination);
      StyleConstants.setUnderline(attributes, true);
      if (from == to) insert(document, from, destination, attributes);
      else for (int offset = from; offset < to;) {
        Element run = document.getCharacterElement(offset);
        int runEnd = Math.min(to, run.getEndOffset());
        SimpleAttributeSet replacement = new SimpleAttributeSet(run.getAttributes());
        replacement.removeAttribute(Markdown.WIKI_TARGET); replacement.removeAttribute(Markdown.WIKI_ID);
        replacement.addAttributes(attributes);
        document.setCharacterAttributes(offset, runEnd - offset, replacement, true); offset = runEnd;
      }
    }, caret, caret);
    area.setTextInsertionStyle(FxMarkdown.Span.plain(currentBlock()));
  }

  private void key(KeyEvent event) {
    KeyCode code = event.getCode();
    if (event.isShortcutDown()) {
      String format = BOLD_KEY.match(event) ? "bold" : ITALIC_KEY.match(event) ? "italic" : CODE_KEY.match(event) ? "code" : null;
      if (format != null) { format(format); event.consume(); }
      else if (LINK_KEY.match(event)) { insertLink(); event.consume(); }
      else if (PASTE_KEY.match(event)) { paste(); event.consume(); }
      else if (CHECKLIST_KEY.match(event)) { toggleChecklist(); event.consume(); }
      else if (OPEN_NOTE_KEY.match(event) && openNoteLinkAtCaret()) event.consume();
    } else if (code == KeyCode.ENTER && !event.isShiftDown()) {
      enter(); event.consume();
    } else if (code.isArrowKey() || code == KeyCode.HOME || code == KeyCode.END) area.setTextInsertionStyle(null);
    else if (code == KeyCode.ESCAPE) slash.hide();
  }

  void enter() {
    int start = area.getSelection().getStart(), end = area.getSelection().getEnd();
    FxMarkdown.Block old = currentBlock();
    if (Set.of("bullet", "ordered", "task", "quote").contains(old.kind())
        && area.getParagraph(area.getCurrentParagraph()).getText().isBlank()) {
      block("paragraph", 1); return;
    }
    mutate(document -> {
      remove(document, start, end - start);
      insert(document, start, "\n", null);
      String kind = Set.of("heading", "divider").contains(old.kind()) ? "paragraph" : old.kind();
      SimpleAttributeSet next = Markdown.paragraph(kind, 1);
      next.addAttribute(Markdown.NUMBER, old.number() + 1);
      next.addAttribute(Markdown.CHECKED, false);
      if (kind.equals("code")) {
        for (String key : List.of(Markdown.LANG, Markdown.FENCE_ID, Markdown.FENCE_OPEN, Markdown.FENCE_CLOSE)) {
          Object value = old.values().get(key);
          if (value != null) next.addAttribute(key, value);
        }
        next.addAttribute(Markdown.FENCE_EMPTY, false);
      }
      document.setParagraphAttributes(start + 1, 1, next, true);
    }, start + 1, start + 1);
    area.setTextInsertionStyle(FxMarkdown.Span.plain(currentBlock()));
  }

  void convertShortcuts() {
    if (changing || disposed || area.getUndoManager().isPerformingAction()) return;
    int row = area.getCurrentParagraph(), column = area.getCaretColumn();
    if (column == 0) return;
    int position = area.getCaretPosition();
    char last = area.getText(position - 1, position).charAt(0);
    if (last != ' ' && last != '*' && last != '~' && last != '`' && last != '/' && last != ']') return;
    String paragraphText = area.getParagraph(row).getText();
    String before = paragraphText.substring(0, column);
    if (currentBlock().kind().equals("code")) return;
    if (before.endsWith("]]")) {
      int opening = before.lastIndexOf("[[");
      int escapes = 0;
      for (int i = opening - 1; i >= 0 && before.charAt(i) == '\\'; i--) escapes++;
      if (opening >= 0 && escapes % 2 == 0 && (opening == 0 || before.charAt(opening - 1) != '[')) {
        int from = area.getAbsolutePosition(row, opening), end = area.getCaretPosition();
        boolean plain = area.getStyleSpans(from, end).stream().allMatch(span ->
            !span.getStyle().flag(Markdown.INLINE_CODE) && span.getStyle().link() == null && span.getStyle().noteLink() == null);
        String token = before.substring(opening);
        var parsed = FxMarkdown.read(token);
        if (plain && parsed.getParagraph(0).getStyledSegments().stream().anyMatch(segment -> segment.getStyle().noteLink() != null)) {
          int caret = from + parsed.length();
          mutate(document -> {
            SimpleAttributeSet inherited = new SimpleAttributeSet(document.getCharacterElement(from).getAttributes());
            remove(document, from, token.length());
            try {
              DefaultStyledDocument inline = FxMarkdown.semantic(parsed);
              for (int offset = 0; offset < parsed.length();) {
                Element run = inline.getCharacterElement(offset);
                int next = Math.min(parsed.length(), run.getEndOffset());
                SimpleAttributeSet attributes = new SimpleAttributeSet(inherited); attributes.addAttributes(run.getAttributes());
                insert(document, from + offset, inline.getText(offset, next - offset), attributes); offset = next;
              }
            } catch (javax.swing.text.BadLocationException error) { throw new IllegalStateException(error); }
          }, caret, caret);
          area.setTextInsertionStyle(FxMarkdown.Span.plain(currentBlock()));
          return;
        }
      }
    }
    if (before.equals("/")) {
      if (area.getScene() != null && area.getScene().getWindow() != null)
        area.getCaretBounds().ifPresent(bounds -> {
          slashCaret = position;
          slash.show(area, bounds.getMinX(), bounds.getMaxY() + 8);
        });
      return;
    }
    Matcher prefix = BLOCK_SHORTCUT.matcher(before);
    if (prefix.matches()) {
      String kind = before.startsWith("#") ? "heading" : before.startsWith(">") ? "quote"
          : before.startsWith("```") ? "code" : before.contains("[") ? "task"
          : Character.isDigit(before.charAt(0)) ? "ordered" : "bullet";
      int level = kind.equals("heading") ? before.strip().length() : 1;
      int start = area.getAbsolutePosition(row, 0);
      mutate(document -> {
        remove(document, start, before.length());
        SimpleAttributeSet attributes = Markdown.paragraph(kind, level);
        attributes.addAttribute(Markdown.NUMBER, prefix.group(3) != null ? listNumber(prefix.group(3)) : 1);
        attributes.addAttribute(Markdown.CHECKED, false);
        if (kind.equals("code")) attributes.addAttribute(Markdown.LANG, before.strip().substring(3));
        document.setParagraphAttributes(start, 1, attributes, true);
      }, start, start);
      return;
    }
    Matcher inline = INLINE_SHORTCUT.matcher(before);
    if (inline.find()) {
      String token = inline.group(1);
      DefaultStyledDocument formatted = Markdown.read(token);
      int from = area.getAbsolutePosition(row, inline.start(1));
      int length = formatted.getLength() - 1;
      int caret = area.getCaretPosition() - token.length() + length;
      mutate(document -> {
        remove(document, from, token.length());
        try {
          for (int offset = 0; offset < length;) {
            Element run = formatted.getCharacterElement(offset);
            int next = Math.min(length, run.getEndOffset());
            insert(document, from + offset, formatted.getText(offset, next - offset), run.getAttributes());
            offset = next;
          }
        } catch (javax.swing.text.BadLocationException error) { throw new IllegalStateException(error); }
      }, caret, caret);
      area.setTextInsertionStyle(FxMarkdown.Span.plain(currentBlock()));
    }
  }

  private FxMarkdown.Block currentBlock() { return area.getParagraph(area.getCurrentParagraph()).getParagraphStyle(); }
  private void leaveNoteLinkAtBoundary() {
    int caret = area.getCaretPosition();
    if (caret == 0 || area.getSelection().getLength() != 0) return;
    FxMarkdown.Span previous = area.getStyleOfChar(caret - 1);
    if (previous.noteLink() == null) return;
    Object identity = previous.values().get(Markdown.WIKI_ID);
    if (caret < area.getLength() && Objects.equals(identity, area.getStyleOfChar(caret).values().get(Markdown.WIKI_ID))) return;
    SimpleAttributeSet plain = previous.attributes();
    plain.removeAttribute(Markdown.WIKI_TARGET); plain.removeAttribute(Markdown.WIKI_ID);
    if (previous.link() == null) plain.removeAttribute(StyleConstants.Underline);
    area.setTextInsertionStyle(FxMarkdown.Span.of(plain, currentBlock()));
  }
  private boolean openNoteLinkAtCaret() {
    int caret = area.getCaretPosition();
    return openNoteLink(caret) || (caret > 0 && openNoteLink(caret - 1));
  }
  boolean openNoteLink(int position) {
    if (position < 0 || position >= area.getLength()) return false;
    FxMarkdown.Span span = area.getStyleOfChar(position);
    if (span.noteLink() == null || span.flag(Markdown.INLINE_CODE) || span.blockKind().equals("code")) return false;
    onNoteLink.accept(span.noteLink()); return true;
  }
  private void mutate(Consumer<DefaultStyledDocument> command, int anchor, int caret) {
    DefaultStyledDocument semantic = FxMarkdown.semantic(area.getDocument());
    command.accept(semantic);
    ReadOnlyStyledDocument<FxMarkdown.Block, String, FxMarkdown.Span> next = FxMarkdown.fromSemantic(semantic);
    if (next.getParagraphs().equals(area.getParagraphs())) return;
    changing = true;
    try {
      area.getUndoManager().preventMerge();
      // Replace only the changed paragraph neighborhood, retaining the other virtualized cells.
      var before = area.getParagraphs();
      var after = next.getParagraphs();
      int first = 0, common = Math.min(before.size(), after.size());
      while (first < common && before.get(first).equals(after.get(first))) first++;
      int suffix = 0;
      while (suffix < common - first
          && before.get(before.size() - suffix - 1).equals(after.get(after.size() - suffix - 1))) suffix++;
      first = Math.max(0, first - 1);
      int oldFrom = area.getAbsolutePosition(first, 0);
      int oldTo = suffix == 0 ? area.getLength() : area.getAbsolutePosition(before.size() - suffix, 0);
      int newFrom = next.position(first, 0).toOffset();
      int newTo = suffix == 0 ? next.length() : next.position(after.size() - suffix, 0).toOffset();
      area.replace(oldFrom, oldTo, next.subSequence(newFrom, newTo));
      area.selectRange(Math.max(0, Math.min(anchor, area.getLength())), Math.max(0, Math.min(caret, area.getLength())));
      area.setTextInsertionStyle(null);
      area.getUndoManager().preventMerge();
      for (int i = first; i < after.size() - suffix; i++) area.recreateParagraphGraphic(i);
    } finally { changing = false; }
    onChange.run();
    focus(); area.requestFollowCaret();
  }

  private Node marker(int index) {
    FxMarkdown.Block block = area.getParagraph(index).getParagraphStyle();
    if (block.kind().equals("task")) {
      CheckBox check = new CheckBox();
      check.setFont(FxTheme.textFont(FxTheme.TEXT_SIZE, false, false));
      check.setSelected(block.checked());
      check.setAccessibleText("Checklist item " + (block.checked() ? "checked" : "unchecked"));
      check.getStyleClass().add("md-task");
      check.setOnAction(event -> toggleChecklist(index));
      return check;
    }
    String marker = switch (block.kind()) {
      case "bullet" -> "•";
      case "ordered" -> block.number() + ".";
      default -> null;
    };
    if (marker == null) return null;
    Label label = new Label(marker);
    label.setFont(FxTheme.textFont(FxTheme.TEXT_SIZE, false, false));
    label.getStyleClass().add("md-marker");
    label.setMinWidth(24);
    label.setMaxWidth(Double.MAX_VALUE);
    return label;
  }

  private void gutters() {
    double horizontal = Math.max(24, (area.getWidth() - 760) / 2);
    Insets padding = new Insets(24, horizontal, 40, horizontal);
    if (!padding.equals(area.getPadding())) area.setPadding(padding);
  }

  private static void applyBlock(TextFlow flow, FxMarkdown.Block block) {
    flow.getStyleClass().removeIf(name -> name.startsWith("md-"));
    flow.getStyleClass().add("md-paragraph");
    if (!block.kind().equals("paragraph")) flow.getStyleClass().add("md-" + block.kind());
  }

  private static void applySpan(TextExt text, FxMarkdown.Span span) {
    boolean inlineCode = span.flag(Markdown.INLINE_CODE);
    boolean code = inlineCode || span.blockKind().equals("code");
    boolean heading = span.blockKind().equals("heading");
    double size = heading ? switch (span.level()) {
      case 1 -> 28;
      case 2 -> 23;
      case 3 -> 19;
      default -> FxTheme.TEXT_SIZE;
    } : FxTheme.TEXT_SIZE;
    boolean bold = heading || span.flag(StyleConstants.Bold), italic = span.flag(StyleConstants.Italic);
    String face = code ? "Menlo " + (bold ? "Bold" : "Regular") : "System " + (bold ? "Bold" : "Regular");
    if (italic) face = code ? (bold ? "Menlo Bold Italic" : "Menlo Italic")
        : (bold ? "System Bold Italic" : "System Italic");
    final String exactFace = face;
    text.setFont(!code ? FxTheme.textFont(size, bold, italic) : FONTS.computeIfAbsent(exactFace + ":" + size, ignored ->
        code && !System.getProperty("os.name").startsWith("Mac")
            ? Font.font("Monospaced", bold ? FontWeight.BOLD : FontWeight.NORMAL,
                italic ? FontPosture.ITALIC : FontPosture.REGULAR, size)
            : new Font(exactFace, size)));
    text.setStrikethrough(span.flag(StyleConstants.StrikeThrough));
    boolean link = span.link() != null || (!code && span.noteLink() != null);
    text.setUnderline(link);
    text.getStyleClass().add("md-text");
    if (inlineCode) text.getStyleClass().add("md-inline-code");
    if (link) text.getStyleClass().add("md-link");
    if (!code && span.noteLink() != null) {
      text.setAccessibleRole(AccessibleRole.HYPERLINK);
      text.setAccessibleText("Note link: " + span.noteLink());
      text.setCursor(javafx.scene.Cursor.HAND);
    }
    text.setStyle("-fx-fill: " + (link ? "-color-accent-fg" : code ? "-color-code-fg" : "-color-editor-fg") + ";"
        + (code ? "-rtfx-background-color: -color-bg-subtle;" : ""));
  }

  private ContextMenu contextMenu() {
    ContextMenu menu = FxTheme.contextMenu();
    item(menu, "Undo", this::undo); item(menu, "Redo", this::redo);
    menu.getItems().add(new SeparatorMenuItem());
    item(menu, "Cut", this::cut); item(menu, "Copy", this::copy); item(menu, "Paste", this::paste);
    menu.getItems().add(new SeparatorMenuItem());
    item(menu, "Bold", () -> format("bold")); item(menu, "Italic", () -> format("italic"));
    item(menu, "Inline code", () -> format("code")); item(menu, "Insert or edit link…", this::insertLink);
    Menu blocks = new Menu("Block style");
    for (BlockCommand choice : BLOCKS) {
      MenuItem item = new MenuItem(choice.label(), FxTheme.icon(choice.icon(), 16));
      item.setOnAction(event -> block(choice.kind(), choice.level()));
      blocks.getItems().add(item);
    }
    menu.getItems().add(blocks);
    return menu;
  }
  private static void item(ContextMenu menu, String title, Runnable action) {
    MenuItem item = new MenuItem(title); item.setOnAction(event -> action.run()); menu.getItems().add(item);
  }
  private static int listNumber(String value) {
    try { return Math.max(1, Integer.parseInt(value)); }
    catch (NumberFormatException ignored) { return 1; }
  }
  private static void insert(DefaultStyledDocument document, int position, String text, javax.swing.text.AttributeSet attributes) {
    try { document.insertString(position, text, attributes); }
    catch (javax.swing.text.BadLocationException error) { throw new IllegalStateException(error); }
  }
  private static void remove(DefaultStyledDocument document, int position, int length) {
    try { if (length > 0) document.remove(position, length); }
    catch (javax.swing.text.BadLocationException error) { throw new IllegalStateException(error); }
  }
  int serializedChunks() { return writer.serializedChunks(); }
  StyledTextArea<FxMarkdown.Block, FxMarkdown.Span> area() { return area; }
}
