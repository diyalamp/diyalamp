package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.Markdown;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.application.Application;
import javafx.stage.Stage;
import atlantafx.base.theme.PrimerLight;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.control.ScrollBar;
import javafx.geometry.Orientation;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
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
        Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
        roundTrips(); editingAndUndo(); blocksAndTasks(); shortcuts(); linksAndLifecycle(); pasteAndDivider(); incrementalSaving(); actualComponent();
      } catch (Throwable error) { failure.set(error); }
      finally { finished.countDown(); }
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
    FxWritingPane editor = new FxWritingPane("# Title\nBody\n- [ ] Task\n`value`\n");
    StackPane root = new StackPane(editor.node());
    root.getStyleClass().add("workspace-root");
    root.setStyle("-diyalamp-accent-subtle: #e8eaff; -color-accent-emphasis: #5868cf;");
    root.getStylesheets().add(FxWritingPane.class.getResource("/fx-workspace.css").toExternalForm());
    Scene scene = new Scene(root, 900, 600);
    Stage stage = new Stage();
    stage.setScene(scene); stage.show();
    root.applyCss(); root.layout();
    check(editor.area().getClass().getName().startsWith("org.fxmisc.richtext."),
        "Maintained RichTextFX must own the editing surface");
    check(editor.area().getText().equals("Title\nBody\nTask\nvalue"), "The editing surface must render Markdown without markers");
    check(editor.area().getParagraphGraphic(2) instanceof javafx.scene.control.CheckBox,
        "Checklist graphics must be standard JavaFX controls, not custom painting");
    editor.area().selectRange(editor.area().getAbsolutePosition(1, 0), editor.area().getAbsolutePosition(1, 0));
    root.layout();
    var caret = editor.area().getCaretBounds();
    check(caret.isPresent(), "The maintained editor must expose an actual laid-out caret");
    check(caret.get().getHeight() >= 16 && caret.get().getHeight() <= 27,
        "Body caret geometry must match the 18-point text, rather than inflated paragraph spacing");
    for (var node : editor.area().lookupAll(".md-text")) if (node instanceof TextExt text) {
      if (text.getText().equals("Body")) {
        check(text.getFont().getFamily().equals(Font.getDefault().getFamily()) && text.getFont().getSize() == 18,
            "Full application CSS must preserve the one system font family at the intended body size");
        check(!text.getFont().getStyle().contains("Bold") && !text.getFont().getStyle().contains("Thin"),
            "Body copy must resolve a true regular face rather than a heavy or thin substitution");
        check(text.getFill() instanceof Color ink && !ink.equals(Color.BLACK) && ink.getBrightness() < .45,
            "Reading ink should be softened while remaining dark enough for the paper");
      } else if (text.getText().equals("Title")) {
        check(text.getFont().getFamily().equals(Font.getDefault().getFamily()) && text.getFont().getSize() == 30,
            "Headings must use the same system family with a clear size hierarchy");
      } else if (text.getText().equals("value") && System.getProperty("os.name").startsWith("Mac"))
        check(text.getFont().getFamily().equals("Menlo"), "Only code should switch to installed Menlo");
    }
    System.out.println("RichTextFX body caret height: " + caret.get().getHeight());
    editor.dispose(); stage.close();
    String longNote = "Line of text\n".repeat(200);
    editor = new FxWritingPane(longNote);
    root = new StackPane(editor.node());
    root.getStyleClass().add("workspace-root");
    root.setStyle("-diyalamp-accent-subtle: #e8eaff; -color-accent-emphasis: #5868cf;");
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
}
