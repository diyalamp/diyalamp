package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.Markdown;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.application.Application;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.stage.Stage;
import atlantafx.base.theme.PrimerLight;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.control.ScrollBar;
import javafx.geometry.Orientation;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.event.Event;
import org.fxmisc.richtext.TextExt;
import javax.swing.text.StyleConstants;

/** Runs actual RichTextFX controls on the JavaFX toolkit, including undo and Markdown storage. */
public final class FxWritingPaneTests {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] args) throws Exception {
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      try {
        FxTheme.setup();
        roundTrips(); editingAndUndo(); blocksAndTasks(); shortcuts(); keyboardModifiers(); linksAndLifecycle(); pasteAndDivider(); incrementalSaving(); actualComponent(); focusLine();
        focusScrolling(finished, failure);
      } catch (Throwable error) { failure.set(error); }
      if (failure.get() != null) finished.countDown();
    });
    if (!finished.await(45, TimeUnit.SECONDS)) throw new AssertionError("FX editor tests timed out");
    Platform.exit();
    if (failure.get() != null) throw new AssertionError("FX editor regression", failure.get());
    System.out.println("Passed " + checks + " JavaFX editor checks.");
  }

  private static void roundTrips() {
    for (String source : List.of("", "plain without final newline", "# Heading\n\nBody **bold** and *italic*.\n",
        "***both***\n~~strike **strong**~~\n", "`code` and ``a ` b``\n",
        "7. Number seven\n8. Number eight\n- [x] Checked\n- [ ] Unchecked\n",
        "~~~java\nString text = \"**literal**\";\n~~~\n",
        "````markdown\n```java\ntext\n```\n````\n",
        "[label](https://example.com \"title\")\n![remote](https://example.com/image.png)\n",
        "<script>alert('no')</script>\n\n- parent\n  - nested\n\nTail\n")) {
      FxWritingPane editor = new FxWritingPane(source);
      check(editor.markdown().equals(source), "Opening a note must retain exact source, including unsupported content");
      check(FxMarkdown.write(editor.area().getDocument()).equals(Markdown.write(Markdown.read(source))),
          "The semantic adapter must retain the proven serializer's structures: " + source + " GOT " + FxMarkdown.write(editor.area().getDocument()));
      check(!editor.canUndo(), "Loading a note must not create an undo action");
      editor.dispose();
    }
  }

  private static void editingAndUndo() {
    String source = "one **two** three\nAnother *line*\n";
    FxWritingPane editor = new FxWritingPane(source);
    editor.area().selectRange(0, editor.area().getLength());
    editor.format("code");
    String formatted = editor.markdown();
    check(!formatted.equals(source), "Inline-code formatting must change exported Markdown");
    check(editor.canUndo(), "Formatting must enter the maintained UndoFX history");
    editor.undo();
    check(editor.markdown().equals(source), "One Undo must restore a formatting command across all runs");
    check(!editor.canUndo(), "One action must not leave fragments in Undo history");
    editor.redo();
    check(editor.markdown().equals(formatted), "One Redo must restore complete formatting");
    editor.dispose();

    editor = new FxWritingPane("# A heading\nText\n");
    editor.area().selectRange(9, 9);
    editor.enter();
    check(editor.area().getParagraph(1).getParagraphStyle().kind().equals("paragraph"),
        "Enter after a heading must create a body paragraph");
    editor.dispose();
  }

  private static void blocksAndTasks() {
    FxWritingPane editor = new FxWritingPane("A task\n");
    editor.block("task", 1);
    check(editor.markdown().equals("- [ ] A task\n"), "Block command must create a checklist");
    editor.toggleChecklist();
    check(editor.markdown().equals("- [x] A task\n"), "Task control must toggle the exported state");
    editor.undo();
    check(editor.markdown().equals("- [ ] A task\n"), "Task toggling must be undoable");
    editor.block("heading", 2);
    check(editor.markdown().equals("## A task\n"), "Heading command must replace the previous block kind");
    editor.dispose();

    editor = new FxWritingPane("~~~~java\none\n~~~~\n");
    editor.area().selectRange(3, 3);
    editor.enter();
    editor.area().replaceSelection("two");
    check(editor.markdown().equals("~~~~java\none\ntwo\n~~~~\n"), "Code continuation must retain the fence and language");
    editor.dispose();
  }

  private static void shortcuts() {
    FxWritingPane editor = new FxWritingPane("");
    editor.area().replaceText("# ");
    editor.convertShortcuts();
    check(editor.area().getText().isEmpty(), "Markdown block markers must become formatting");
    check(editor.area().getParagraph(0).getParagraphStyle().kind().equals("heading"), "Heading shortcut must set a heading");
    editor.area().replaceSelection("Title");
    check(editor.markdown().equals("# Title\n"), "Typing in a rendered heading must save Markdown");
    editor.dispose();

    editor = new FxWritingPane("");
    editor.area().replaceText("**strong** ");
    editor.convertShortcuts();
    check(editor.area().getText().equals("strong "), "Inline markers must disappear from the writing page");
    check(editor.area().getStyleOfChar(0).flag(StyleConstants.Bold), "Inline shortcut must retain bold metadata");
    check(editor.markdown().equals("**strong** \n"), "Inline shortcuts must round-trip through Markdown");
    editor.dispose();
  }

  private static void linksAndLifecycle() {
    FxWritingPane editor = new FxWritingPane("Read this\n");
    editor.area().selectRange(5, 9);
    editor.insertLink("https://example.com");
    check(editor.markdown().equals("Read [this](https://example.com)\n"), "Link command must style the selected label");
    editor.area().replaceSelection(" more");
    check(editor.markdown().equals("Read [this](https://example.com) more\n"), "New typing must stay outside the previous link");
    int[] events = {0};
    editor.setOnChange(() -> events[0]++);
    editor.load("Loaded\n");
    check(editor.area().getCaretPosition() == 6, "Synchronized loads must preserve and clamp the caret position");
    editor.area().selectRange(0, 0);
    check(events[0] == 0, "Loading synchronized notes must not cause save feedback loops");
    editor.area().replaceSelection("New ");
    check(events[0] > 0, "Real text changes must notify autosave");
    check(editor.find("loaded"), "Search must find note text without case sensitivity");
    editor.dispose();
    editor.dispose();
  }

  private static void keyboardModifiers() {
    boolean mac = System.getProperty("os.name", "").startsWith("Mac");
    for (KeyCode code : List.of(KeyCode.B, KeyCode.I, KeyCode.BACK_QUOTE)) {
      FxWritingPane editor = new FxWritingPane("Selected words\n");
      StackPane parent = new StackPane(editor.node());
      new Scene(parent);
      int[] bubbled = {0};
      parent.addEventHandler(KeyEvent.KEY_PRESSED, event -> bubbled[0]++);
      editor.area().selectRange(0, 8);
      Event.fireEvent(editor.area(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, true, !mac, false, mac));
      check(editor.markdown().equals("Selected words\n") && !editor.canUndo(),
          "Shift-Shortcut-" + code + " must not format the selected text");
      check(bubbled[0] == 1, "Shift-Shortcut-" + code + " must remain available to workspace commands");
      Event.fireEvent(editor.area(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, !mac, true, mac));
      check(editor.markdown().equals("Selected words\n") && !editor.canUndo(),
          "Alt-Shortcut-" + code + " must not trigger the unmodified formatting command");
      Event.fireEvent(editor.area(), new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, !mac, false, mac));
      check(editor.canUndo() && !editor.markdown().equals("Selected words\n"),
          "The ordinary formatting shortcut must still work for " + code);
      editor.dispose();
    }
  }

  private static void pasteAndDivider() {
    FxWritingPane editor = new FxWritingPane("After\n");
    editor.pasteText("Before\n");
    check(editor.area().getText().equals("Before\nAfter"), "Pasted trailing newlines must not join separate lines");
    check(editor.markdown().equals("Before\nAfter\n"), "Pasting must retain both paragraphs in the saved note");
    editor.dispose();
    editor = new FxWritingPane("Keep this text\n");
    editor.block("divider", 1);
    check(editor.markdown().equals("Keep this text\n---\n"), "Inserting a divider must not silently discard an existing paragraph");
    editor.undo();
    check(editor.markdown().equals("Keep this text\n"), "Divider insertion must be a single recoverable action");
    editor.dispose();
    editor = new FxWritingPane("```java\n\n```\n");
    editor.pasteText("**literal**\nnext");
    check(editor.area().getText().equals("**literal**\nnext"), "Pasting inside a code block must keep code literal");
    check(editor.markdown().equals("```java\n**literal**\nnext\n```\n"), "Pasted code must retain its fence");
    check(editor.recoveryText().contains("**literal**"), "Recovery text must retain the actual visible content");
    editor.dispose();
    editor = new FxWritingPane("\n```java\nold\n```\n");
    editor.pasteText("```java\nnew\n```");
    check(editor.markdown().equals("```java\nnew\n```\n```java\nold\n```\n"),
        "Pasted and existing adjacent code fences must retain distinct identities");
    editor.dispose();
    String unsupported = "[[Other note]]\n\n- parent\n  - child\n\nTail\n";
    editor = new FxWritingPane(unsupported);
    editor.area().replaceText(editor.area().getLength(), editor.area().getLength(), "!");
    check(editor.markdown().equals(unsupported.replace("Tail", "Tail!")),
        "Editing a different paragraph must retain unsupported source constructs exactly");
    editor.dispose();
  }

  private static void incrementalSaving() {
    StringBuilder notes = new StringBuilder("[[Retained syntax]]\n\n");
    for (int i = 0; i < 300; i++) notes.append("Paragraph ").append(i).append(" with **emphasis**.\n");
    notes.append("\n~~~java\nint value = 1;\n~~~\n");
    String source = notes.toString();
    FxWritingPane editor = new FxWritingPane(source);
    int warmed = editor.serializedChunks();
    String visible = editor.recoveryText();
    int position = visible.indexOf("Paragraph 150") + "Paragraph 150".length();
    editor.area().replaceText(position, position, " edited");
    String saved = editor.markdown();
    check(saved.equals(source.replace("Paragraph 150", "Paragraph 150 edited")),
        "Incremental serialization must retain every other source line and code fence");
    check(editor.serializedChunks() - warmed == 1,
        "Editing one normal paragraph must serialize only that changed paragraph");
    int afterSave = editor.serializedChunks();
    check(editor.markdown() == saved, "Repeated save/status reads must reuse the revision's serialized value");
    editor.area().selectRange(0, 0);
    check(editor.markdown().equals(saved) && editor.serializedChunks() == afterSave,
        "Moving the caret must not reconstruct or revalidate the note");
    editor.undo();
    check(editor.markdown().equals(source), "Undo back to the load mark must restore exact source");
    editor.redo();
    check(editor.markdown().equals(saved), "Redo must invalidate the revision cache correctly");
    editor.dispose();

    editor = new FxWritingPane("```java\nline\n");
    editor.area().selectRange(4, 4); editor.enter(); editor.block("paragraph", 1);
    check(editor.markdown().equals(FxMarkdown.write(editor.area().getDocument())),
        "An open fence followed by a new body paragraph needs the same boundary handling as the full serializer");
    editor.dispose();
    editor = new FxWritingPane("```text\nline\n```\n\nTail\n");
    editor.area().replaceText(0, 4, "```");
    check(editor.markdown().equals(FxMarkdown.write(editor.area().getDocument())),
        "Cached code groups must still widen fence delimiters when a body line collides");
    editor.dispose();
  }

  private static void actualComponent() {
    FxWritingPane editor = new FxWritingPane("# Title\n## Subheading\n### Detail\n###### Sixth\nBody\n"
        + "- [ ] Task\n- Bullet\n7. Numbered\n> Quote\n`value`\n```java\nfenced()\n```\n"
        + "**strong** *slant* ***both*** [link](https://example.com)\n");
    StackPane root = new StackPane(editor.node());
    root.getStyleClass().add("workspace-root");
    FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
    root.getStylesheets().add(FxWritingPane.class.getResource("/fx-workspace.css").toExternalForm());
    Scene scene = new Scene(root, 900, 600);
    Stage stage = new Stage();
    stage.setScene(scene); stage.show();
    root.applyCss(); root.layout();
    check(editor.area().getClass().getName().startsWith("org.fxmisc.richtext."),
        "Maintained RichTextFX must own the editing surface");
    check(editor.area().getText().equals("Title\nSubheading\nDetail\nSixth\nBody\nTask\nBullet\nNumbered\nQuote\nvalue\nfenced()\nstrong slant both link"),
        "The editing surface must render Markdown without markers");
    check(editor.area().getParagraphGraphic(5) instanceof javafx.scene.control.CheckBox,
        "Checklist graphics must be standard JavaFX controls, not custom painting");
    var checklist = (javafx.scene.control.CheckBox) editor.area().getParagraphGraphic(5);
    check(checklist.getFont().getName().equals("Inter Regular") && checklist.getFont().getSize() == 16,
        "Checklist controls must retain the same 16-point text font");
    for (int row : List.of(6, 7, 8)) {
      var marker = (javafx.scene.control.Label) editor.area().getParagraphGraphic(row);
      check(marker.getFont().getName().equals("Inter Regular") && marker.getFont().getSize() == 16
          && marker.getTextFill().equals(Color.web("#8C8C8C")),
          "Bullet, ordered-list and quote markers must use the same size and readable dark-theme muted ink");
    }
    editor.area().selectRange(editor.area().getAbsolutePosition(4, 0), editor.area().getAbsolutePosition(4, 0));
    root.layout();
    var caret = editor.area().getCaretBounds();
    check(caret.isPresent(), "The maintained editor must expose an actual laid-out caret");
    check(caret.get().getHeight() >= 16 && caret.get().getHeight() <= 27,
        "Body caret geometry must match the 16-point text, rather than inflated paragraph spacing");
    java.util.Set<String> headings = new java.util.HashSet<>();
    java.util.Set<String> codeRuns = new java.util.HashSet<>();
    for (var node : editor.area().lookupAll(".md-text")) if (node instanceof TextExt text) {
      check(text.getFont().getSize() == 16, "Every rendered body, heading, emphasis, link and code run must have exactly one font size");
      if (text.getText().equals("Body")) {
        check(text.getFont().getName().equals("Inter Regular") && text.getFont().getSize() == 16,
            "Full application CSS must preserve static Inter Regular at the intended body size");
        check(!text.getFont().getStyle().contains("Bold") && !text.getFont().getStyle().contains("Thin"),
            "Body copy must resolve a true regular face rather than a heavy or thin substitution");
        check(text.getFill().equals(Color.web("#BBBEBF")), "Prose must use the VS Code dark foreground");
      } else if (List.of("Title", "Subheading", "Detail", "Sixth").contains(text.getText())) {
        headings.add(text.getText());
        check(text.getFont().getName().equals("Inter SemiBold") && text.getFont().getSize() == 16
            && text.getFill().equals(Color.web("#BBBEBF")),
            "All heading levels must preserve semantic emphasis through weight while sharing the body size and dark ink");
      } else if (List.of("value", "fenced()").contains(text.getText())) {
        codeRuns.add(text.getText());
        if (System.getProperty("os.name").startsWith("Mac"))
          check(text.getFont().getFamily().equals("Menlo"), "Only code should switch to installed Menlo");
        check(text.getFill().equals(Color.web("#8C8C8C")) && text.getBackgroundColor().equals(Color.web("#242526")),
            "Inline and fenced code must use readable dark-theme foreground and background colors");
      }
      else if (text.getText().equals("strong"))
        check(text.getFont().getName().equals("Inter SemiBold"), "Markdown emphasis must use the real static semibold face");
      else if (text.getText().equals("slant"))
        check(text.getFont().getName().equals("Inter Italic"), "Italic text must retain a real slanted face");
      else if (text.getText().equals("both"))
        check(text.getFont().getName().equals("Inter SemiBold Italic"), "Bold italic text must retain its own real static face");
      else if (text.getText().equals("link"))
        check(text.getFill().equals(Color.web("#48A0C7")), "Links must use the readable VS Code foreground accent");
    }
    check(headings.size() == 4 && codeRuns.size() == 2, "The actual typography check must include all representative heading levels and both code widgets");
    int bodyStart = editor.area().getAbsolutePosition(4, 0);
    editor.area().selectRange(bodyStart, bodyStart + 4); root.applyCss(); root.layout();
    check(editor.area().lookupAll(".selection").stream().anyMatch(node -> node instanceof javafx.scene.shape.Shape shape
        && Color.web("#276782DD").equals(shape.getFill())),
        "The native text selection must use Dark 2026's exact translucent selection color: "
            + editor.area().lookupAll(".selection").stream().map(node -> ((javafx.scene.shape.Shape) node).getFill()).toList());
    System.out.println("RichTextFX body caret height: " + caret.get().getHeight());
    editor.dispose(); stage.close();
    String longNote = "Line of text\n".repeat(200);
    editor = new FxWritingPane(longNote);
    root = new StackPane(editor.node());
    root.getStyleClass().add("workspace-root");
    FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
    root.getStylesheets().add(FxWritingPane.class.getResource("/fx-workspace.css").toExternalForm());
    stage = new Stage(); stage.setScene(new Scene(root, 900, 600)); stage.show();
    root.applyCss(); root.layout();
    var outer = editor.node().localToScene(editor.node().getBoundsInLocal());
    ScrollBar vertical = null;
    for (var node : editor.node().lookupAll(".scroll-bar"))
      if (node instanceof ScrollBar bar && bar.getOrientation() == Orientation.VERTICAL) vertical = bar;
    check(vertical != null && vertical.isVisible(), "A long note must have a maintained vertical scrollbar");
    var edge = vertical.localToScene(vertical.getBoundsInLocal());
    check(Math.abs(edge.getMaxX() - outer.getMaxX()) <= 2,
        "Scrollbar must sit on the editor's outer right edge, outside the writing gutters: bar=" + edge + " viewport=" + outer + " layout=" + editor.node().getLayoutBounds());
    check(editor.area().getPadding().getLeft() >= 24 && editor.area().getPadding().getRight() >= 24,
        "Readable text gutters belong inside the editing surface");
    editor.dispose(); stage.close();
  }

  private static void focusLine() {
    String source = "word alpha beta gamma delta epsilon zeta eta theta ".repeat(30) + "\n";
    FxWritingPane editor = new FxWritingPane(source);
    StackPane root = new StackPane(editor.node());
    root.getStyleClass().add("workspace-root");
    FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
    root.getStylesheets().add(FxWritingPane.class.getResource("/fx-workspace.css").toExternalForm());
    Stage stage = new Stage(); stage.setScene(new Scene(root, 500, 500)); stage.show();
    root.applyCss(); root.layout();
    SimpleDoubleProperty progress = new SimpleDoubleProperty();
    editor.setFocusLineProgress(progress);
    Rectangle mask = (Rectangle) editor.node().lookup(".zen-focus-mask");
    check(mask != null && mask.isMouseTransparent() && !mask.isFocusTraversable() && !mask.isVisible(),
        "The focus mask must be decorative, ignore input and start hidden");
    editor.area().selectRange(0, 0);
    editor.area().requestFollowCaret(); root.layout();
    var first = root.screenToLocal(editor.area().getCaretBoundsOnScreen(editor.area().getCaretSelectionBind().getUnderlyingCaret()).orElseThrow());
    editor.area().selectRange(300, 300); root.layout();
    var current = root.screenToLocal(editor.area().getCaretBoundsOnScreen(editor.area().getCaretSelectionBind().getUnderlyingCaret()).orElseThrow());
    check(editor.area().getCurrentParagraph() == 0 && current.getMinY() > first.getMaxY() + 3 * first.getHeight(),
        "The focus position must distinguish wrapped visual lines inside one Markdown paragraph");
    var normal = root.snapshot(null, null);
    var frame = editor.node().getBoundsInParent();
    int[] changes = {0}; editor.setOnChange(() -> changes[0]++);
    progress.set(1); root.applyCss(); root.layout();
    var focused = root.snapshot(null, null);
    check(mask.isVisible() && mask.getOpacity() == 1 && editor.area().isLineHighlighterOn(),
        "Full focus must reveal the fade and the maintained editor's current-line highlight");
    var gradient = (LinearGradient) mask.getFill();
    var maskBounds = mask.localToScene(mask.getBoundsInLocal());
    double caretCenter = editor.node().screenToLocal(editor.area().getCaretBoundsOnScreen(editor.area().getCaretSelectionBind().getUnderlyingCaret()).orElseThrow()).getCenterY();
    check(gradientOpacity(gradient, (caretCenter - mask.getY()) / mask.getHeight()) < .01,
        "The actual wrapped caret line must remain fully readable through the gradient");
    check(gradientOpacity(gradient, (first.getCenterY() - mask.getY()) / mask.getHeight()) > .99,
        "Lines far from the visual caret line must fade completely into the editor background");
    int normalFar = brightPixels(normal, first.getMinY(), first.getMaxY());
    int focusedFar = brightPixels(focused, first.getMinY(), first.getMaxY());
    int focusedCurrent = brightPixels(focused, current.getMinY(), current.getMaxY());
    check(normalFar > 30 && focusedFar == 0 && focusedCurrent > 30,
        "Actual rendered pixels must hide distant text while retaining the current visual line");
    for (Node node : editor.node().lookupAll(".scroll-bar")) if (node instanceof ScrollBar bar && bar.isVisible()
        && bar.getOrientation() == Orientation.VERTICAL)
      check(maskBounds.getMaxX() <= bar.localToScene(bar.getBoundsInLocal()).getMinX() + 1,
          "Focus fading must leave the native scrollbar visible and available");
    progress.set(.4); check(Math.abs(mask.getOpacity() - .4) < .001, "An interrupted transition must render its current progress");
    progress.set(.8); check(Math.abs(mask.getOpacity() - .8) < .001, "A reversed transition must follow the same property without restarting");
    editor.area().selectRange(150, 150); root.layout();
    caretCenter = editor.node().screenToLocal(editor.area().getCaretBoundsOnScreen(editor.area().getCaretSelectionBind().getUnderlyingCaret()).orElseThrow()).getCenterY();
    check(gradientOpacity((LinearGradient) mask.getFill(), (caretCenter - mask.getY()) / mask.getHeight()) < .01,
        "The fade must follow caret movement without moving or selecting text");
    check(editor.node().getBoundsInParent().equals(frame) && editor.markdown().equals(source)
        && !editor.canUndo() && changes[0] == 0,
        "Focus mode must preserve layout, source, selection history and save notifications");
    progress.set(0);
    check(!mask.isVisible() && mask.getOpacity() == 0, "Leaving focus mode must restore all text immediately at zero progress");
    editor.area().replaceText(0, 0, "New "); editor.undo();
    check(editor.markdown().equals(source), "Native edit and Undo must retain their behavior after focus-mode transitions");
    editor.dispose(); progress.set(1);
    check(mask.getOpacity() == 0, "Disposing an editor must detach its shared animation property");
    stage.close();
  }

  private static double gradientOpacity(LinearGradient gradient, double fraction) {
    var stops = gradient.getStops();
    if (fraction <= stops.getFirst().getOffset()) return stops.getFirst().getColor().getOpacity();
    for (int i = 1; i < stops.size(); i++) if (fraction <= stops.get(i).getOffset()) {
      var before = stops.get(i - 1); var after = stops.get(i);
      double position = (fraction - before.getOffset()) / (after.getOffset() - before.getOffset());
      return before.getColor().getOpacity() + position * (after.getColor().getOpacity() - before.getColor().getOpacity());
    }
    return stops.getLast().getColor().getOpacity();
  }

  private static void focusScrolling(CountDownLatch finished, AtomicReference<Throwable> failure) {
    String source = "word alpha beta gamma delta epsilon zeta eta theta ".repeat(100) + "\n";
    FxWritingPane editor = new FxWritingPane(source);
    StackPane root = new StackPane(editor.node()); root.getStyleClass().add("workspace-root");
    FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
    root.getStylesheets().add(FxWritingPane.class.getResource("/fx-workspace.css").toExternalForm());
    Scene scene = new Scene(root, 500, 400); Stage stage = new Stage(); stage.setScene(scene); stage.show();
    SimpleDoubleProperty progress = new SimpleDoubleProperty(1); editor.setFocusLineProgress(progress);
    int[] phase = {0}; double[] initialY = {0}; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    Runnable[] pulse = {null};
    pulse[0] = () -> {
      boolean complete = false;
      try {
        if (System.nanoTime() > deadline) throw new AssertionError("Focus scrolling did not reach fresh post-layout geometry");
        Rectangle mask = (Rectangle) editor.node().lookup(".zen-focus-mask");
        if (phase[0] == 0) {
          editor.area().selectRange(600, 600); editor.area().requestFollowCaret(); phase[0]++;
        } else {
          var fresh = editor.area().getCaretBoundsOnScreen(editor.area().getCaretSelectionBind().getUnderlyingCaret());
          var caret = fresh.map(editor.node()::screenToLocal);
          if (phase[0] == 1 && caret.isPresent()) {
            initialY[0] = caret.get().getCenterY(); editor.area().scrollYToPixel(150); phase[0]++;
          } else if (phase[0] == 2 && caret.isPresent() && initialY[0] - caret.get().getCenterY() > 50) {
            check(mask.getFill() instanceof LinearGradient && gradientOpacity((LinearGradient) mask.getFill(),
                (caret.get().getCenterY() - mask.getY()) / mask.getHeight()) < .01,
                "A scroll within the same wrapped paragraph must move the transparent band to fresh caret geometry");
            editor.area().scrollYToPixel(700); phase[0]++;
          } else if (phase[0] == 3 && (caret.isEmpty() || caret.get().getMaxY() < -100)) {
            boolean opaque = mask.getFill().equals(FxTheme.EDITOR_BACKGROUND);
            if (mask.getFill() instanceof LinearGradient gradient)
              opaque = gradientOpacity(gradient, 0) > .99 && gradientOpacity(gradient, .5) > .99 && gradientOpacity(gradient, 1) > .99;
            check(opaque, "An offscreen focus line must not leave a stale bright band over unrelated text");
            check(editor.area().getCaretPosition() == 600 && editor.markdown().equals(source) && !editor.canUndo(),
                "Scrolling in focus mode must not force the caret, change content, or enter Undo history");
            complete = true;
          }
        }
      } catch (Throwable error) { failure.set(error); complete = true; }
      if (complete) {
        scene.removePostLayoutPulseListener(pulse[0]); editor.dispose(); stage.close(); finished.countDown();
      } else Platform.requestNextPulse();
    };
    scene.addPostLayoutPulseListener(pulse[0]); Platform.requestNextPulse();
  }

  private static int brightPixels(javafx.scene.image.WritableImage image, double top, double bottom) {
    int count = 0;
    for (int y = Math.max(0, (int) Math.floor(top)); y < Math.min(image.getHeight(), Math.ceil(bottom)); y++)
      for (int x = 24; x < image.getWidth() - 24; x++) {
        Color color = image.getPixelReader().getColor(x, y);
        if (color.getRed() > .4 && color.getGreen() > .4 && color.getBlue() > .4) count++;
      }
    return count;
  }
}
