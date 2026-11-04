package com.diyalamp.desktop;

import javax.swing.*;
import javax.swing.text.*;

/** Exercises the actual close-view path without constructing a native application window. */
public final class ViewDisposalTests {
  private static int checks;

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            exercise();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    System.out.println("Passed " + checks + " view disposal checks.");
  }

  private static void exercise() throws Exception {
    DefaultStyledDocument document = Markdown.read("Shared document\n");
    int baseline = document.getDocumentListeners().length;
    int[] closedEvents = {0}, liveEvents = {0};
    WritingPane closed = new WritingPane(document, () -> closedEvents[0]++);
    WritingPane live = new WritingPane(document, () -> liveEvents[0]++);
    JTabbedPane left = new JTabbedPane(), right = new JTabbedPane();
    left.addTab("Draft", new JScrollPane(closed));
    right.addTab("Draft", new JScrollPane(live));
    closedEvents[0] = liveEvents[0] = 0;
    Diyalamp.removeView(left, 0);
    check(
        closed.getDocument() != document,
        "A closed view must detach Swing's shared-document observers");
    check(
        live.getDocument() == document,
        "Closing one view must not detach its live split counterpart");
    int closedBeforeEdit = closedEvents[0];
    document.insertString(document.getLength(), "A live edit\n", null);
    check(
        closedEvents[0] == closedBeforeEdit, "Shared-document edits must not notify a closed view");
    check(liveEvents[0] > 0, "The remaining view must continue observing edits");
    String text = document.getText(0, document.getLength());
    Diyalamp.removeView(right, 0);
    check(
        document.getDocumentListeners().length == baseline,
        "Closing all views must return the source document to its original listener count");
    check(
        document.getText(0, document.getLength()).equals(text),
        "Closing views must not alter the shared note content");
    closed.disposeView();
    check(document.getDocumentListeners().length == baseline, "View disposal must be idempotent");
    for (int i = 0; i < 5; i++) {
      WritingPane reopened = new WritingPane(document, () -> {});
      left.addTab("Draft", new JScrollPane(reopened));
      Diyalamp.removeView(left, 0);
    }
    check(
        document.getDocumentListeners().length == baseline,
        "Repeatedly opening and closing a note must not accumulate document listeners");
  }
}
