package com.diyalamp.desktop;

import javax.swing.SwingUtilities;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;

/** Real UndoManager regressions for one formatting action across multiple runs and blocks. */
public final class InlineCodeUndoTests {
  private static int checks;

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            verify("one **two** three\n");
            verify("one **two** three\nAnother *line* with `code`\n```java\nint x = 1;\n```\n");
            verify("`one` and **`two`**\n`three`\n```java\nint y = 2;\n```\n");
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    System.out.println("Passed " + checks + " inline-code undo checks.");
  }

  private static void verify(String source) throws Exception {
    DefaultStyledDocument document = Markdown.read(source);
    check(document instanceof WritingDocument, "Every loaded app note must support atomic commands");
    WritingPane editor = new WritingPane(document, () -> {});
    UndoManager undo = new UndoManager();
    int[] events = {0};
    document.addUndoableEditListener(undo);
    document.addUndoableEditListener(event -> events[0]++);
    String before = signature(document);
    editor.select(0, document.getLength() - 1);
    editor.toggle("code");
    String after = signature(document);
    String formatted = Markdown.write(document);
    check(!before.equals(after), "The actual formatting action must change the selected runs");
    check(events[0] == 1, "One selection-format command must emit exactly one undoable edit");
    check(undo.canUndo(), "Formatting must be undoable");
    undo.undo();
    check(signature(document).equals(before), "One Undo must restore every run and paragraph");
    check(Markdown.write(document).equals(source), "One Undo must restore original Markdown");
    check(!undo.canUndo(), "No fragments of the same formatting action may remain to undo");
    check(undo.canRedo(), "The whole formatting action must be redoable");
    undo.redo();
    check(signature(document).equals(after), "One Redo must restore all changed formatting and fonts");
    check(Markdown.write(document).equals(formatted), "One Redo must restore the complete formatted source");
    check(!undo.canRedo(), "No fragments of the same formatting action may remain to redo");
    check(events[0] == 1, "Undo/Redo must not create new undo entries");
    editor.disposeView();
  }

  private static String signature(DefaultStyledDocument document) throws Exception {
    StringBuilder result = new StringBuilder(document.getText(0, document.getLength()));
    for (int offset = 0; offset < document.getLength(); offset++) {
      AttributeSet run = document.getCharacterElement(offset).getAttributes();
      AttributeSet block = document.getParagraphElement(offset).getAttributes();
      result.append('|')
          .append(StyleConstants.isBold(run)).append(':')
          .append(StyleConstants.isItalic(run)).append(':')
          .append(Boolean.TRUE.equals(run.getAttribute(Markdown.INLINE_CODE))).append(':')
          .append(document.getFont(run).getFamily()).append(':')
          .append(StyleConstants.getBackground(run)).append(':')
          .append(block.getAttribute(Markdown.KIND));
    }
    return result.toString();
  }
}
