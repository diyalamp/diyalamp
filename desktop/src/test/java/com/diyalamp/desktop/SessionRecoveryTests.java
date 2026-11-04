package com.diyalamp.desktop;

import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** Headless regression checks for closing tabs, reopening files, and conflict recovery. */
public final class SessionRecoveryTests {
  private static int checks;

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  private static JScrollPane view(Diyalamp.Session session) {
    JScrollPane pane = new JScrollPane(new JPanel());
    pane.putClientProperty("session", session);
    return pane;
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
    System.out.println("Passed " + checks + " session recovery checks.");
  }

  private static void exercise() throws Exception {
    Path root = Files.createTempDirectory("diyalamp-session-tests-");
    NoteStore store = new NoteStore(root);
    Path file = store.create("Draft", "Original\n");
    Map<Path, Diyalamp.Session> sessions = new LinkedHashMap<>();
    JTabbedPane left = new JTabbedPane(), right = new JTabbedPane();
    Diyalamp.Session initial = Diyalamp.sessionFor(sessions, store, file);
    left.addTab("Draft", view(initial));
    right.addTab("Draft", view(initial));
    left.removeAll();
    Diyalamp.retireUnusedSessions(sessions, left, right);
    check(
        sessions.get(file) == initial && initial.viewCount == 1,
        "Closing one split view must retain the other view's shared session");
    Files.writeString(file, "Changed externally\n");
    check(
        Diyalamp.sessionFor(sessions, store, file) == initial,
        "Re-selecting a still-open note must not discard its shared editing session");
    right.removeAll();
    Diyalamp.retireUnusedSessions(sessions, left, right);
    check(
        !sessions.containsKey(file), "Closing the final saved view must retire its cached session");
    Diyalamp.Session reopened = Diyalamp.sessionFor(sessions, store, file);
    check(
        reopened != initial
            && reopened
                .document
                .getText(0, reopened.document.getLength())
                .contains("Changed externally"),
        "Reopening a closed note must read the current disk contents");
    check(
        reopened.fingerprint.equals(store.load(file).fingerprint()),
        "Reopened notes must track the new disk fingerprint");

    reopened.document.insertString(reopened.document.getLength(), "Unsaved local edits\n", null);
    reopened.dirty = true;
    Diyalamp.retireUnusedSessions(sessions, left, right);
    check(
        sessions.get(file) == reopened,
        "A session with unsaved edits must never be evicted by view cleanup");
    String unsaved = reopened.document.getText(0, reopened.document.getLength());
    Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
    boolean invalidRejected = false;
    try {
      Diyalamp.reloadSession(sessions, store, reopened);
    } catch (java.io.IOException expected) {
      invalidRejected = true;
    }
    check(
        invalidRejected && sessions.get(file) == reopened && reopened.dirty,
        "A failed reload must preserve the current unsaved session");
    check(
        reopened.document.getText(0, reopened.document.getLength()).equals(unsaved),
        "Invalid disk bytes must not replace the in-memory recovery copy");

    Files.writeString(file, "Disk version to recover\n");
    Diyalamp.Session recovered = Diyalamp.reloadSession(sessions, store, reopened);
    check(
        recovered != reopened && sessions.get(file) == recovered && !recovered.dirty,
        "A confirmed successful reload must replace the session and reset dirty state");
    check(
        recovered
            .document
            .getText(0, recovered.document.getLength())
            .contains("Disk version to recover"),
        "Reload must display the disk version rather than the stale cached document");
    recovered.document.insertString(recovered.document.getLength(), "Next edit\n", null);
    store.save(file, Markdown.write(recovered.document), recovered.fingerprint);
    check(
        Files.readString(file).contains("Next edit"),
        "The recovered session must be editable and save against its fresh fingerprint");
  }
}
