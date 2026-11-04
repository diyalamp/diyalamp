package com.diyalamp.desktop;

import java.awt.event.ActionEvent;
import javax.swing.*;
import javax.swing.text.*;

/** Exercises the actual writing actions, including loaded fenced-code continuation. */
public final class WritingActionsTests {
  public static void main(String[] args) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            String source = "~~~~java\none\n~~~~\n";
            DefaultStyledDocument document = Markdown.read(source);
            WritingPane editor = new WritingPane(document, () -> {});
            editor.setCaretPosition(3);
            editor
                .getActionMap()
                .get("page-enter")
                .actionPerformed(
                    new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, "page-enter"));
            editor.replaceSelection("two");
            String written = Markdown.write(document);
            if (!written.equals("~~~~java\none\ntwo\n~~~~\n"))
              throw new AssertionError(
                  "Enter must keep new code in its original fence and language:\n" + written);
            editor.disposeView();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    System.out.println("Passed 1 writing action check.");
  }
}
