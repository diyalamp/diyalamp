package com.diyalamp.desktop;

import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import javax.swing.*;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;

/** Exercises keyboard and native-menu commands against real styled Markdown documents. */
public final class WritingAccessibilityTests {
  private static int checks;

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            checklistKeyboardAndUndo();
            linksByKeyboard();
            linkEditingAndCancellation();
            nativeMenuAlternatives();
            focusableControls();
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    System.out.println("Passed " + checks + " writing accessibility checks.");
  }

  private static void press(WritingPane editor, int key, int modifiers) {
    Object command = editor.getInputMap().get(KeyStroke.getKeyStroke(key, modifiers));
    check(command != null, "Keyboard command must be bound");
    Action action = editor.getActionMap().get(command);
    check(action != null, "Keyboard command must resolve to an action");
    action.actionPerformed(
        new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, command.toString()));
  }

  private static void checklistKeyboardAndUndo() {
    DefaultStyledDocument document = Markdown.read("- [ ] First\n- [x] Second\nPlain text\n");
    UndoManager undo = new UndoManager();
    document.addUndoableEditListener(undo);
    WritingPane editor = new WritingPane(document, () -> {});
    editor.setCaretPosition(0);
    check(
        editor.getAccessibleContext().getAccessibleDescription().startsWith("Unchecked checklist item"),
        "An unchecked task must expose its state in the editor description");
    press(editor, KeyEvent.VK_ENTER, WritingCommands.shortcutMask() | InputEvent.SHIFT_DOWN_MASK);
    check(
        Markdown.write(document).equals("- [x] First\n- [x] Second\nPlain text\n"),
        "Keyboard toggling must update only the current task in exported Markdown");
    check(
        editor.getAccessibleContext().getAccessibleDescription().startsWith("Checked checklist item"),
        "Toggling must update the accessible task state");
    check(undo.canUndo(), "A keyboard checklist change must be undoable");
    undo.undo();
    check(
        Markdown.write(document).equals("- [ ] First\n- [x] Second\nPlain text\n"),
        "Undo must restore the task's previous state");
    check(
        editor.getAccessibleContext().getAccessibleDescription().startsWith("Unchecked checklist item"),
        "Undo must also restore the accessible state");
    editor.setCaretPosition(document.getLength() - 2);
    String unchanged = Markdown.write(document);
    press(editor, KeyEvent.VK_ENTER, WritingCommands.shortcutMask() | InputEvent.SHIFT_DOWN_MASK);
    check(Markdown.write(document).equals(unchanged), "Toggling plain text must not create a task");
    check(
        !editor.getAccessibleContext().getAccessibleDescription().contains("Unchecked checklist item"),
        "Moving to ordinary text must clear stale checklist context");
    editor.disposeView();
  }

  private static void linksByKeyboard() throws Exception {
    DefaultStyledDocument document = Markdown.read("Read this\n");
    WritingPane editor =
        new WritingPane(document, () -> {}, initial -> "https://example.com/notes");
    editor.select(5, 9);
    press(editor, KeyEvent.VK_K, WritingCommands.shortcutMask());
    check(
        Markdown.write(document).equals("Read [this](https://example.com/notes)\n"),
        "Command K must apply a link to the selected text");
    editor.replaceSelection(" afterwards");
    check(
        Markdown.write(document).equals("Read [this](https://example.com/notes) afterwards\n"),
        "Typing after inserting a link must not extend its destination to the next sentence");
    editor.disposeView();

    DefaultStyledDocument empty = Markdown.read("\n");
    WritingPane inserted =
        new WritingPane(empty, () -> {}, initial -> "  https://example.com  ");
    press(inserted, KeyEvent.VK_K, WritingCommands.shortcutMask());
    check(
        "https://example.com".equals(
            empty.getCharacterElement(0).getAttributes().getAttribute(Markdown.LINK)),
        "An unselected URL must be linked immediately, rather than style only future typing");
    check(
        Markdown.write(empty).equals("[https://example.com](https://example.com)\n"),
        "An inserted URL must round-trip as a Markdown link");
    inserted.replaceSelection(" more");
    check(
        Markdown.write(empty).equals("[https://example.com](https://example.com) more\n"),
        "A bare inserted link must leave later typing unlinked");
    inserted.disposeView();
  }

  private static void linkEditingAndCancellation() {
    DefaultStyledDocument document =
        Markdown.read("[a **bold** link](https://old.example) outside\n");
    String[] initial = new String[1];
    WritingPane editor =
        new WritingPane(
            document,
            () -> {},
            destination -> {
              initial[0] = destination;
              return "https://new.example";
            });
    editor.setCaretPosition(4);
    press(editor, KeyEvent.VK_K, WritingCommands.shortcutMask());
    check("https://old.example".equals(initial[0]), "Editing a link must prefill its destination");
    DefaultStyledDocument reopened = Markdown.read(Markdown.write(document));
    for (int offset : new int[] {0, 2, 5, 7, 10})
      check(
          "https://new.example".equals(
              reopened.getCharacterElement(offset).getAttributes().getAttribute(Markdown.LINK)),
          "Editing from within a link must update its whole label across formatting runs");
    check(
        reopened.getCharacterElement(12).getAttributes().getAttribute(Markdown.LINK) == null,
        "Editing a link must not include the following text");
    check(
        StyleConstants.isBold(document.getCharacterElement(4).getAttributes()),
        "Changing a link must preserve bold formatting inside it");
    editor.disposeView();

    DefaultStyledDocument cancelled = Markdown.read("Do not change this\n");
    WritingPane cancel = new WritingPane(cancelled, () -> {}, destination -> null);
    cancel.select(3, 6);
    press(cancel, KeyEvent.VK_K, WritingCommands.shortcutMask());
    check(
        Markdown.write(cancelled).equals("Do not change this\n"),
        "Cancelling a link dialog must leave the note unchanged");
    check(
        cancel.getSelectionStart() == 3 && cancel.getSelectionEnd() == 6,
        "Cancellation must keep the selection");
    cancel.disposeView();
  }

  private static JMenuItem named(JMenu menu, String name) {
    for (int i = 0; i < menu.getItemCount(); i++) {
      JMenuItem item = menu.getItem(i);
      if (item != null && name.equals(item.getText())) return item;
    }
    throw new AssertionError("Missing menu command: " + name);
  }

  private static void nativeMenuAlternatives() {
    DefaultStyledDocument document = Markdown.read("A heading\n");
    WritingPane editor = new WritingPane(document, () -> {});
    JMenu menu = WritingCommands.menu(() -> editor);
    editor.setCaretPosition(0);
    named(menu, "Heading 2").doClick(0);
    check(
        Markdown.write(document).equals("## A heading\n"),
        "Native menu must apply heading formatting");
    named(menu, "Checklist").doClick(0);
    named(menu, "Toggle checklist item").doClick(0);
    check(
        Markdown.write(document).equals("- [x] A heading\n"),
        "Native menu must create and toggle a task");
    check(
        named(menu, "Insert or edit link…")
            .getAccelerator()
            .equals(KeyStroke.getKeyStroke(KeyEvent.VK_K, WritingCommands.shortcutMask())),
        "Native link menu must advertise the actual keyboard shortcut");
    check(
        named(menu, "Toggle checklist item")
            .getAccelerator()
            .equals(
                KeyStroke.getKeyStroke(
                    KeyEvent.VK_ENTER,
                    WritingCommands.shortcutMask() | InputEvent.SHIFT_DOWN_MASK)),
        "Native checklist menu must advertise the actual keyboard shortcut");
    for (String command :
        new String[] {
          "Text", "Heading 1", "Heading 3", "Bullet list", "Numbered list", "Quote", "Code block",
          "Divider"
        })
      check(
          named(menu, command) != null,
          "Every slash block must also be discoverable in the native menu");
    named(WritingCommands.menu(() -> null), "Toggle checklist item").doClick(0);
    editor.disposeView();
  }

  private static void focusableControls() {
    JButton tool = Theme.tool("", "Paragraph style", () -> {});
    check(tool.isFocusable(), "Toolbar controls must be reachable without a mouse");
    check(
        "Paragraph style".equals(tool.getAccessibleContext().getAccessibleName()),
        "An icon-only control must retain its useful accessible name");
  }
}
