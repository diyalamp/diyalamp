package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.Markdown;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javax.swing.text.StyleConstants;

/** Rendered local links keep their Markdown targets, code contexts, and native undo history. */
public final class FxWikiLinkTests {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] args) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      try { FxTheme.setup(); reading(); editing(); typing(); clicking(); }
      catch (Throwable error) { failure.set(error); }
      finally { done.countDown(); }
    });
    if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("Wiki-link tests timed out");
    Platform.exit();
    if (failure.get() != null) throw new AssertionError("Wiki-link regression", failure.get());
    System.out.println("Passed " + checks + " JavaFX wiki-link checks.");
  }

  private static void reading() {
    String source = "See [[Project Notes]] and [[folder/Plan|roadmap]].\n"
        + "**[[Project Notes]]** and [web](https://example.com)\n"
        + "`[[literal]]`\n```markdown\n[[fenced]]\n```\n\\[[escaped]]\nTail\n";
    FxWritingPane editor = new FxWritingPane(source);
    check(editor.recoveryText().startsWith("See Project Notes and roadmap."), "Completed wiki markup must render as readable note labels");
    check(editor.markdown().equals(source), "Opening wiki links must preserve exact original Markdown bytes");
    int label = editor.recoveryText().indexOf("roadmap");
    check(editor.area().getStyleOfChar(label).noteLink().equals("folder/Plan"), "Aliases must retain the actual filename target");
    int literal = editor.recoveryText().indexOf("[[literal]]");
    int fenced = editor.recoveryText().indexOf("[[fenced]]");
    int escaped = editor.recoveryText().indexOf("[[escaped]]");
    check(editor.area().getStyleOfChar(literal).noteLink() == null && editor.area().getStyleOfChar(literal).flag(Markdown.INLINE_CODE),
        "Inline code must retain literal double brackets");
    check(editor.area().getStyleOfChar(fenced).noteLink() == null && editor.area().getStyleOfChar(fenced).blockKind().equals("code"),
        "Fenced code must not create note links");
    check(editor.area().getStyleOfChar(escaped).noteLink() == null, "Escaped wiki markup must remain literal");
    int web = editor.recoveryText().indexOf("web");
    check(editor.area().getStyleOfChar(web).link().equals("https://example.com") && editor.area().getStyleOfChar(web).noteLink() == null,
        "Normal Markdown links must retain their existing destination semantics");
    AtomicReference<String> opened = new AtomicReference<>(); editor.setOnNoteLink(opened::set);
    check(editor.openNoteLink(label) && opened.get().equals("folder/Plan"), "Activating a rendered link must emit only its local target");
    check(!editor.openNoteLink(literal) && !editor.openNoteLink(web), "Local-link callbacks must not receive code or web links");
    editor.area().appendText("!");
    check(editor.markdown().equals(source.replace("Tail\n", "Tail!\n")),
        "Editing adjacent text must not rewrite wiki, escaped, web, or code content");
    editor.undo(); check(editor.markdown().equals(source), "Undo must restore the exact loaded source");
    editor.dispose();
    editor = new FxWritingPane("[see [[literal]]](https://example.com)\n");
    check(editor.recoveryText().equals("see [[literal]]") && editor.area().getStyleOfChar(5).noteLink() == null,
        "Double brackets inside an existing web-link label must preserve that Markdown link's semantics");
    editor.dispose();
  }

  private static void editing() {
    FxWritingPane editor = new FxWritingPane("See [[folder/Plan|roadmap]].\n");
    int start = editor.recoveryText().indexOf("roadmap");
    editor.area().replaceText(start + 4, start + 4, " new");
    check(editor.markdown().equals("See [[folder/Plan|road newmap]].\n"),
        "Editing a rendered label must preserve its filename and retain the changed label as an alias");
    editor.undo();
    editor.area().selectRange(start, start + 7); editor.format("bold");
    check(editor.markdown().equals("See **[[folder/Plan|roadmap]]**.\n"), "Formatting a note link must retain its target and renderable emphasis");
    editor.undo(); check(editor.markdown().equals("See [[folder/Plan|roadmap]].\n"), "One undo must restore a whole link-format action");
    editor.redo(); check(editor.area().getStyleOfChar(start).flag(StyleConstants.Bold), "Redo must restore the complete emphasized link");
    editor.dispose();

    editor = new FxWritingPane("[[Plan]]\n"); editor.area().selectRange(0, 4); editor.insertLink("https://example.com");
    check(editor.markdown().equals("[Plan](https://example.com)\n"), "Replacing a note link with a web link must retain the text and remove local-only metadata");
    editor.dispose();

    editor = new FxWritingPane("[[A]][[A]]\n");
    editor.area().selectRange(editor.area().getLength(), editor.area().getLength()); editor.area().replaceSelection("!");
    check(editor.markdown().equals("[[A]][[A]]!\n"), "Adjacent links with the same target must not merge into one altered label");
    editor.dispose();
    editor = new FxWritingPane("[[Plan|head \\] tail]]\n");
    editor.area().selectRange(editor.area().getLength(), editor.area().getLength()); editor.area().replaceSelection("!");
    check(editor.markdown().equals("[[Plan|head \\] tail]]!\n"), "Escaped closing brackets in a label must remain safe to serialize");
    editor.dispose();
  }

  private static void typing() {
    FxWritingPane editor = new FxWritingPane("");
    editor.area().replaceText("[[Project Notes]]"); editor.convertShortcuts();
    check(editor.recoveryText().equals("Project Notes") && editor.area().getCaretPosition() == "Project Notes".length(),
        "Completing double brackets must render the note label and move the caret with it");
    check(editor.markdown().equals("[[Project Notes]]\n"), "A typed rendered link must save as wiki Markdown");
    editor.undo(); check(editor.recoveryText().equals("[[Project Notes]]"), "Conversion must be one native undo action");
    editor.redo(); check(editor.recoveryText().equals("Project Notes"), "One redo must restore the rendered link");
    editor.area().replaceSelection(" after");
    check(editor.markdown().equals("[[Project Notes]] after\n"), "Typing after a completed link must stay outside the filename");
    editor.dispose();

    editor = new FxWritingPane(""); editor.area().replaceText("\\[[Literal]]"); editor.convertShortcuts();
    check(editor.recoveryText().equals("\\[[Literal]]"), "Live shortcuts must honor an escaped opening bracket"); editor.dispose();
    editor = new FxWritingPane("`[[Literal]]`\n"); editor.area().selectRange(editor.area().getLength(), editor.area().getLength()); editor.convertShortcuts();
    check(editor.area().getStyleOfChar(0).noteLink() == null && editor.recoveryText().equals("[[Literal]]"),
        "Completing brackets inside an inline code span must stay literal"); editor.dispose();
  }

  private static void clicking() {
    FxWritingPane editor = new FxWritingPane("[[Local target]]\n");
    AtomicReference<String> activated = new AtomicReference<>(); editor.setOnNoteLink(activated::set);
    StackPane root = new StackPane(editor.node()); FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
    root.getStyleClass().add("workspace-root");
    root.getStylesheets().add(FxWritingPane.class.getResource("/fx-workspace.css").toExternalForm());
    Stage stage = new Stage(); stage.setScene(new Scene(root, 800, 300)); stage.show(); root.applyCss(); root.layout();
    var character = editor.area().getCharacterBoundsOnScreen(1, 2).orElseThrow();
    var point = editor.area().screenToLocal(character.getCenterX(), character.getCenterY());
    MouseEvent click = new MouseEvent(MouseEvent.MOUSE_CLICKED, point.getX(), point.getY(),
        character.getCenterX(), character.getCenterY(), MouseButton.PRIMARY, 1,
        false, false, false, false, false, false, false, false, false, true, null);
    editor.area().fireEvent(click);
    check("Local target".equals(activated.get()), "Clicking actual laid-out link text must activate the local filename callback");
    check(editor.area().lookupAll(".md-link").stream().anyMatch(node -> node.getAccessibleText().equals("Note link: Local target")),
        "Rendered wiki links must expose their note destination to accessibility");
    editor.dispose(); stage.close();
  }
}
