package com.diyalamp.desktop;

import java.nio.file.*;
import java.io.IOException;
import java.util.Comparator;

public final class NoteStoreMoveTests {
  private static int checks;
  private static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
  private interface Action { void run() throws IOException; }
  private static void rejects(Action action, String message) throws IOException {
    boolean rejected = false; try { action.run(); } catch (IOException expected) { rejected = true; }
    check(rejected, message);
  }
  public static void main(String[] args) throws Exception {
    Path fixture = Files.createTempDirectory("diyalamp-move-test-").toRealPath();
    try {
      Path root = Files.createDirectory(fixture.resolve("Workspace")); NoteStore store = new NoteStore(root);
      Path folder = store.createFolder(root, "Research");
      Path note = store.create("Draft", "Original [[Reference]] bytes\n");
      Path moved = store.move(note, folder);
      check(moved.equals(folder.resolve("Draft.md")) && !Files.exists(note), "Move changes the file's parent");
      check(Files.readString(moved).equals("Original [[Reference]] bytes\n"), "Move preserves note bytes");
      check(store.move(moved, folder).equals(moved), "Dropping into the same folder is a no-op");
      Path collision = store.create("Draft", "Existing file\n");
      rejects(() -> store.move(moved, root), "Move cannot overwrite an existing filename");
      check(Files.readString(collision).equals("Existing file\n") && Files.exists(moved), "Collision preserves both files");
      rejects(() -> store.createFolder(root, "Research"), "Folder creation cannot overwrite");
      rejects(() -> store.createFolder(root, "../Outside"), "New folders stay in the workspace");
      rejects(() -> store.createFolder(root, ".hidden"), "Hidden internal folders cannot be user destinations");
      Path outside = Files.createDirectory(fixture.resolve("Outside"));
      rejects(() -> store.move(moved, outside), "Move rejects outside destination");
      Path link = Files.createSymbolicLink(root.resolve("Shortcut"), outside);
      rejects(() -> store.move(moved, link), "Move rejects symlink destinations");
      Path fileLink = Files.createSymbolicLink(root.resolve("Linked.md"), moved);
      rejects(() -> store.move(fileLink, root), "Move rejects symlink source notes");
      Path markdown = folder.resolve("Literal.markdown"); Files.writeString(markdown, "Extension\n");
      check(store.move(markdown, root).getFileName().toString().equals("Literal.markdown"), "Move retains .markdown extension");
      System.out.println("Passed " + checks + " note move checks.");
    } finally {
      try (var paths = Files.walk(fixture)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }
}
