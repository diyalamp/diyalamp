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
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.paint.Color;
import javafx.scene.paint.LinearGradient;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.event.Event;
import javafx.stage.Window;
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
        roundTrips(); editingAndUndo(); blocksAndTasks(); shortcuts(); slashCommands(); keyboardModifiers(); linksAndLifecycle();
        searchMatches(); pasteAndDivider(); incrementalSaving(); actualComponent(); checklistAppearance(); quoteAlignment(); focusLine();
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

  private static void slashCommands() {
    FxWritingPane editor = new FxWritingPane("Existing text at the far side of this writing pane\n");
    Button outside = new Button("Outside control");
    BorderPane root = new BorderPane(editor.node());
    root.setBottom(outside);
    root.getStyleClass().add("workspace-root");
    FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
    Scene scene = new Scene(root, 860, 540);
    scene.getStylesheets().add(FxWritingPane.class.getResource("/fx-workspace.css").toExternalForm());
    Stage stage = new Stage();
    stage.setScene(scene);
    stage.show();
    try {
      editor.focus();
      editor.enter();
      root.applyCss();
      root.layout();
      ContextMenu menu = openSlashMenu(editor, stage);
      List<String> labels = List.of("Text", "Heading 1", "Heading 2", "Heading 3", "Bullet list",
          "Numbered list", "Checklist", "Quote", "Code block", "Divider");
      check(menu.getStyleClass().contains("slash-menu"), "The slash menu must have its workspace styling");
      check(menu.getItems().stream().map(MenuItem::getText).toList().equals(labels),
          "The slash menu must retain a named native menu item for each command");
      menu.getScene().getRoot().applyCss();
      Node surface = menu.getSkin().getNode();
      check(surface instanceof Region panel && panel.getEffect() == null && panel.getBackground() != null
              && !panel.getBackground().getFills().isEmpty()
              && panel.getBackground().getFills().getFirst().getFill().equals(FxTheme.EDITOR_BACKGROUND)
              && panel.getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius() == 12,
          "The rendered menu must use the same rounded, shadow-free surface as the workspace");
      var rows = menu.getScene().getRoot().lookupAll(".menu-item");
      for (MenuItem item : menu.getItems()) {
        check(item.getGraphic() != null && item.getGraphic().lookup(".icon-stroke") != null,
            "Every slash command must display a real bundled icon: " + item.getText());
        check(rows.stream().anyMatch(row -> row.getAccessibleRole() == AccessibleRole.MENU_ITEM
                && item.getText().equals(row.queryAccessibleAttribute(AccessibleAttribute.TEXT))),
            "Every rendered command must have its text label exposed to accessibility: " + item.getText());
      }

      int[] outsidePresses = {0};
      outside.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
        outsidePresses[0]++;
        outside.requestFocus();
      });
      press(outside, outside.localToScreen(outside.getBoundsInLocal()));
      check(!menu.isShowing() && outsidePresses[0] == 1 && root.getScene().getFocusOwner() == outside,
          "An outside click must close the menu and still reach the control and its focus action");
      check(editor.recoveryText().startsWith("/\n"), "Dismissing must leave the typed slash as ordinary text");

      menu = openSlashMenu(editor, stage);
      int position = editor.area().getAbsolutePosition(1, 34);
      Bounds character = editor.area().getCharacterBoundsOnScreen(position, position + 1).orElseThrow();
      press(editor.area(), character);
      check(!menu.isShowing() && editor.area().getCurrentParagraph() == 1,
          "Clicking outside the menu within the same editor must dismiss it and place the caret");
      check(editor.recoveryText().startsWith("/\n"), "An editor click must not apply a slash command");

      menu = openSlashMenu(editor, stage);
      pressKey(editor.area(), KeyCode.ESCAPE);
      check(!menu.isShowing() && editor.recoveryText().startsWith("/\n"),
          "Escape must dismiss the menu without applying a command");

      menu = openSlashMenu(editor, stage);
      Node selected = null;
      for (int attempt = 0; attempt < labels.size(); attempt++) {
        pressKey(editor.area(), KeyCode.DOWN);
        selected = menu.getScene().getFocusOwner();
        if (selected != null && "Heading 2".equals(selected.queryAccessibleAttribute(AccessibleAttribute.TEXT))) break;
      }
      check(selected != null && "Heading 2".equals(selected.queryAccessibleAttribute(AccessibleAttribute.TEXT)),
          "Arrow keys must navigate the native slash menu");
      pressKey(editor.area(), KeyCode.ENTER);
      check(!menu.isShowing() && editor.area().getParagraph(0).getParagraphStyle().kind().equals("heading")
              && editor.area().getParagraph(0).getParagraphStyle().level() == 2,
          "Enter must apply the selected command and dismiss the menu");
      check(editor.recoveryText().equals("\nExisting text at the far side of this writing pane"),
          "Applying a command must remove only the active slash");
      check(root.getScene().getFocusOwner() == editor.area(), "Applying a command must return focus to the editor");
    } finally {
      editor.dispose();
      stage.close();
    }
  }

  private static ContextMenu openSlashMenu(FxWritingPane editor, Stage stage) {
    if (editor.recoveryText().startsWith("/\n")) editor.area().replaceText(0, 1, "");
    editor.focus();
    editor.area().selectRange(0, 0);
    Event.fireEvent(editor.area(), new KeyEvent(KeyEvent.KEY_TYPED, "/", "", KeyCode.UNDEFINED,
        false, false, false, false));
    editor.convertShortcuts();
    return Window.getWindows().stream().filter(ContextMenu.class::isInstance).map(ContextMenu.class::cast)
        .filter(menu -> menu.isShowing() && menu.getOwnerWindow() == stage).findFirst()
        .orElseThrow(() -> new AssertionError("Typing slash at the caret must open the command menu"));
  }

  private static void press(Node target, Bounds screen) {
    Point2D point = target.screenToLocal(screen.getCenterX(), screen.getCenterY());
    Event.fireEvent(target, new MouseEvent(MouseEvent.MOUSE_PRESSED, point.getX(), point.getY(),
        screen.getCenterX(), screen.getCenterY(), MouseButton.PRIMARY, 1,
        false, false, false, false, true, false, false, false, false, true, null));
  }

  private static void pressKey(Node target, KeyCode code) {
    Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
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

  private static void searchMatches() {
    String source = "# 🪔 hit **hit** [hit](https://hit.example)\r\n"
        + "~~~java\r\nfirst hit\r\nsecond hit hit\r\n~~~\r\n"
        + "> &amp; hit [[hit target|hit]] `` hit ``\r\n"
        + "~~~\r\n~~~\r\n"
        + "- [x] final hit\r\n";
    FxWritingPane editor = new FxWritingPane(source);
    int hit = source.indexOf("**hit**") + 2;
    editor.revealSearchMatch(source, 1, hit, hit + 3);
    check(editor.area().getAnchor() == 7 && editor.area().getSelectedText().equals("hit"),
        "Search must select the second hit in a formatted heading and count UTF-16 columns");
    hit = source.indexOf("[hit]") + 1;
    editor.revealSearchMatch(source, 1, hit, hit + 3);
    check(editor.area().getAnchor() == 11 && editor.area().getSelectedText().equals("hit"),
        "Search must select a visible Markdown link label");
    hit = source.indexOf("hit.example");
    editor.revealSearchMatch(source, 1, hit, hit + 3);
    check(editor.area().getCurrentParagraph() == 0 && editor.area().getCaretColumn() == 0
        && editor.area().getSelectedText().isEmpty(),
        "A hidden Markdown link destination must reveal the paragraph without selecting a different hit");
    hit = source.indexOf("java");
    editor.revealSearchMatch(source, 2, hit, hit + 4);
    check(editor.area().getCurrentParagraph() == 1 && editor.area().getSelectedText().isEmpty(),
        "A code fence header must reveal the first code paragraph");
    hit = source.indexOf("hit hit") + 4;
    editor.revealSearchMatch(source, 4, hit, hit + 3);
    check(editor.area().getCurrentParagraph() == 2 && editor.area().getCaretColumn() == 14
        && editor.area().getSelectedText().equals("hit"),
        "Repeated hits on a literal code line must resolve past hidden fence markers");
    hit = source.indexOf("&amp; hit") + 6;
    editor.revealSearchMatch(source, 6, hit, hit + 3);
    check(editor.area().getCurrentParagraph() == 3 && editor.area().getCaretColumn() == 5
        && editor.area().getSelectedText().equals("hit"),
        "Decoded text before a hit must shift its rendered position");
    hit = source.indexOf("|hit") + 1;
    editor.revealSearchMatch(source, 6, hit, hit + 3);
    check(editor.area().getCaretColumn() == 9 && editor.area().getSelectedText().equals("hit"),
        "A wiki alias match must select the visible alias");
    hit = source.indexOf("`` hit") + 3;
    editor.revealSearchMatch(source, 6, hit, hit + 3);
    check(editor.area().getCaretColumn() == 13 && editor.area().getSelectedText().equals("hit"),
        "Inline code matches must account for the stripped delimiters and padding");
    hit = source.lastIndexOf("hit");
    editor.revealSearchMatch(source, 9, hit, hit + 3);
    check(editor.area().getCurrentParagraph() == 5 && editor.area().getCaretColumn() == 9
        && editor.area().getSelectedText().equals("hit"),
        "Empty fences must occupy their one rendered paragraph before the next note hit");
    check(editor.markdown().equals(source) && !editor.canUndo(), "Revealing a search hit must not edit the note");
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
    for (int row : List.of(6, 7)) {
      var marker = (javafx.scene.control.Label) editor.area().getParagraphGraphic(row);
      check(marker.getFont().getName().equals("Inter Regular") && marker.getFont().getSize() == 16
          && marker.getTextFill().equals(Color.web("#A49D95")),
          "Bullet and ordered-list markers must use the same size and readable dark-theme muted ink");
    }
    editor.area().selectRange(editor.area().getAbsolutePosition(4, 0), editor.area().getAbsolutePosition(4, 0));
    root.layout();
    var caret = editor.area().getCaretBounds();
    check(caret.isPresent(), "The maintained editor must expose an actual laid-out caret");
    check(caret.get().getHeight() >= 16 && caret.get().getHeight() <= 27,
        "Body caret geometry must match the 16-point text, rather than inflated paragraph spacing");
    var headingSizes = java.util.Map.of("Title", 28.0, "Subheading", 23.0, "Detail", 19.0, "Sixth", 16.0);
    java.util.Set<String> headings = new java.util.HashSet<>();
    java.util.Set<String> codeRuns = new java.util.HashSet<>();
    for (var node : editor.area().lookupAll(".md-text")) if (node instanceof TextExt text) {
      check(text.getFont().getSize() == headingSizes.getOrDefault(text.getText(), 16.0),
          "Only H1–H3 may exceed the 16-point body size: " + text.getText());
      if (text.getText().equals("Body")) {
        check(text.getFont().getName().equals("Inter Regular") && text.getFont().getSize() == 16,
            "Full application CSS must preserve static Inter Regular at the intended body size");
        check(!text.getFont().getStyle().contains("Bold") && !text.getFont().getStyle().contains("Thin"),
            "Body copy must resolve a true regular face rather than a heavy or thin substitution");
        check(text.getFill().equals(Color.web("#CEC9C2")), "Prose must use the shared readable foreground");
      } else if (headingSizes.containsKey(text.getText())) {
        headings.add(text.getText());
        check(text.getFont().getName().equals("Inter SemiBold") && text.getFill().equals(Color.web("#CEC9C2")),
            "All heading levels must preserve the semibold face and dark-theme text color");
      } else if (List.of("value", "fenced()").contains(text.getText())) {
        codeRuns.add(text.getText());
        if (System.getProperty("os.name").startsWith("Mac"))
          check(text.getFont().getFamily().equals("Menlo"), "Only code should switch to installed Menlo");
        check(text.getFill().equals(Color.web("#A49D95")) && text.getBackgroundColor().equals(FxTheme.EDITOR_BACKGROUND),
            "Inline and fenced code must use readable dark-theme foreground and background colors");
      }
      else if (text.getText().equals("strong"))
        check(text.getFont().getName().equals("Inter SemiBold"), "Markdown emphasis must use the real static semibold face");
      else if (text.getText().equals("slant"))
        check(text.getFont().getName().equals("Inter Italic"), "Italic text must retain a real slanted face");
      else if (text.getText().equals("both"))
        check(text.getFont().getName().equals("Inter SemiBold Italic"), "Bold italic text must retain its own real static face");
      else if (text.getText().equals("link"))
        check(text.getFill().equals(Color.web("#FF6B6B")), "Links must use the readable foreground accent");
    }
    check(headings.size() == 4 && codeRuns.size() == 2, "The actual typography check must include all representative heading levels and both code widgets");
    int bodyStart = editor.area().getAbsolutePosition(4, 0);
    editor.area().selectRange(bodyStart, bodyStart + 4); root.applyCss(); root.layout();
    check(editor.area().lookupAll(".selection").stream().anyMatch(node -> node instanceof javafx.scene.shape.Shape shape
        && Color.web("#AB2828DD").equals(shape.getFill())),
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

  private static void checklistAppearance() {
    String source = "Body text\n- A bullet\n7. A numbered item\n- [ ] A pending task\n- [x] A finished task\n"
        + "- [x] A longer task that wraps across several lines should keep its box beside the first line. "
        + "The following lines continue beneath the task text.\n> A short quotation\n";
    FxWritingPane editor = new FxWritingPane(source);
    StackPane root = new StackPane(editor.node());
    root.getStyleClass().add("workspace-root");
    FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
    root.getStylesheets().add(FxWritingPane.class.getResource("/fx-workspace.css").toExternalForm());
    Stage stage = new Stage(); stage.setScene(new Scene(root, 420, 440)); stage.show();
    root.applyCss(); root.layout();
    try {
      CheckBox pending = (CheckBox) editor.area().getParagraphGraphic(3);
      CheckBox finished = (CheckBox) editor.area().getParagraphGraphic(4);
      CheckBox wrapped = (CheckBox) editor.area().getParagraphGraphic(5);
      Node pendingBox = pending.lookup(".box"), finishedBox = finished.lookup(".box"), wrappedBox = wrapped.lookup(".box");
      Bounds wrappedFirstLine = firstCharacter(editor, 5);
      Bounds wrappedOnScreen = wrappedBox.localToScreen(wrappedBox.getLayoutBounds());
      check(editor.area().getParagraphBoundsOnScreen(5).orElseThrow().getHeight() > 65,
          "The fixture must include a genuinely wrapped checklist row");
      check(Math.abs(wrappedOnScreen.getWidth() - 16) <= 1 && Math.abs(wrappedOnScreen.getHeight() - 16) <= 1,
          "The native checkbox must remain compact on a tall row");
      check(Math.abs(wrappedFirstLine.getCenterY() - wrappedOnScreen.getCenterY()) <= 2,
          "A wrapped checklist checkbox must align to the first line");
      double bodyPitch = firstCharacter(editor, 1).getMinY() - firstCharacter(editor, 0).getMinY();
      double taskPitch = firstCharacter(editor, 4).getMinY() - firstCharacter(editor, 3).getMinY();
      check(Math.abs(bodyPitch - taskPitch) <= 1,
          "Checklist lines must use the same pitch as prose after CSS is applied");
      double listIndent = firstCharacter(editor, 1).getMinX();
      check(List.of(2, 3, 4, 5).stream().allMatch(row -> Math.abs(firstCharacter(editor, row).getMinX() - listIndent) <= 1),
          "Bullets, numbers and checkboxes must share the marker gutter");
      var quote = (javafx.scene.layout.Region) editor.area().lookup(".md-quote");
      var border = quote.getBorder().getStrokes().getFirst();
      check(quote.getBackground().getFills().getFirst().getFill().equals(FxTheme.EDITOR_BACKGROUND)
          && border.getLeftStroke().equals(Color.web("#FF6B6B")) && border.getWidths().getLeft() == 3,
          "The quote must have a tinted surface and an accent rule");
      check(editor.area().getParagraphGraphic(6) == null && quote.getPadding().getLeft() == 16,
          "Quote spacing belongs inside the styled paragraph");
      var image = root.snapshot(null, null);
      check(brightMarkerPixels(root, image, pendingBox) == 0 && brightMarkerPixels(root, image, finishedBox) >= 3,
          "Rendered pixels must show the check only on the selected item");
      check(pending.getAccessibleText().equals("Checklist item unchecked") && finished.isSelected(),
          "Checklist controls must retain a native, named selection state");
      pending.fire();
      check(editor.markdown().equals(source.replace("- [ ] A pending task", "- [x] A pending task")),
          "The visible native control must still toggle the corresponding Markdown task");
      editor.area().insertText(editor.area().getLength(), " edited");
      check(editor.markdown().endsWith("> A short quotation edited\n"),
          "Editing a styled quote must retain the Markdown quote marker on save");
    } finally { editor.dispose(); stage.close(); }
  }

  private static Bounds firstCharacter(FxWritingPane editor, int row) {
    int start = editor.area().getAbsolutePosition(row, 0);
    return editor.area().getCharacterBoundsOnScreen(start, start + 1).orElseThrow();
  }

  private static void quoteAlignment() {
    var editor = new FxWritingPane("> A short quotation.\n\n"
        + "> Your words stay in ordinary Markdown files, in a folder you control. "
        + "The quote should stay centered when it wraps.\n");
    var root = new StackPane(editor.node());
    root.getStyleClass().add("workspace-root");
    FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
    root.getStylesheets().add(FxWritingPane.class.getResource("/fx-workspace.css").toExternalForm());
    var stage = new Stage();
    stage.setScene(new Scene(root, 430, 320));
    stage.show();
    root.applyCss(); root.layout();
    try {
      var quotes = editor.area().lookupAll(".md-quote").stream().map(Region.class::cast).toList();
      check(quotes.size() == 2, "The quote fixture must show both quote frames");
      check(Math.abs(quotes.getFirst().getHeight() - quotes.getLast().getHeight()) > 24,
          "The quote fixture must include a genuinely wrapped quote");
      for (Region quote : quotes) {
        var border = quote.getBorder().getStrokes().getFirst();
        double frameCenter = (quote.getHeight() + border.getInsets().getTop() - border.getInsets().getBottom()) / 2;
        var text = quote.getChildrenUnmodifiable().stream().filter(TextExt.class::isInstance).findFirst().orElseThrow();
        check(Math.abs(text.getBoundsInParent().getCenterY() - frameCenter) <= 1,
            "Quote text must be vertically centered in its visible frame");
      }
    } finally { editor.dispose(); stage.close(); }
  }

  private static int brightMarkerPixels(StackPane root, javafx.scene.image.WritableImage image, Node box) {
    Bounds region = root.sceneToLocal(box.localToScene(box.getLayoutBounds()));
    int pixels = 0;
    for (int y = (int) Math.ceil(region.getMinY() + 2); y < region.getMaxY() - 2; y++) {
      for (int x = (int) Math.ceil(region.getMinX() + 2); x < region.getMaxX() - 2; x++) {
        Color color = image.getPixelReader().getColor(x, y);
        if (color.getRed() > .85 && color.getGreen() > .85 && color.getBlue() > .85) pixels++;
      }
    }
    return pixels;
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
    check(mask.isVisible() && mask.getOpacity() == 1 && !editor.area().isLineHighlighterOn(),
        "Full focus emphasizes the caret line with the fade and without a separate line fill");
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
            boolean opaque = mask.getFill().equals(Color.BLACK);
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
