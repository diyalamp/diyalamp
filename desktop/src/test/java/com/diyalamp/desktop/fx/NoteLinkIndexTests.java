package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.NoteStore;
import java.nio.file.*;
import java.util.*;

/** Fictional local notes only; link parsing never changes their bytes. */
public final class NoteLinkIndexTests {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] arguments) throws Exception {
    parser(); pathsAndDirection(); movedReplacement(); bounded();
    System.out.println("Passed " + checks + " note-link index checks.");
  }
  private static void parser() {
    String text = "[[First]] [[Second#Section|label]] [Markdown](nested/Third.md)\n"
        + "`[[Inline]]` \\[[Escaped]] and ![picture](Photo.md)\n"
        + "```md\n[[Fenced]] [No](Fenced.md)\n```\n\n"
        + "    [[Indented]]\n\n<!-- [[Comment]] -->\n"
        + "\\\\[[Even]] [[Last]]\n";
    List<NoteLinkIndex.Target> links = NoteLinkIndex.targets(text);
    check(links.contains(new NoteLinkIndex.Target("First", true)), "Plain wiki links are indexed");
    check(links.contains(new NoteLinkIndex.Target("Second#Section|label", true)), "Heading and alias remain resolvable");
    check(links.contains(new NoteLinkIndex.Target("nested/Third.md", false)), "Markdown file links are indexed");
    for (String ignored : List.of("Inline", "Escaped", "Fenced", "Indented", "Comment"))
      check(links.stream().noneMatch(link -> link.destination().contains(ignored)), ignored + " must remain literal");
    check(links.stream().noneMatch(link -> link.destination().equals("Photo.md")), "Image embeds are not note links");
    check(links.contains(new NoteLinkIndex.Target("Even", true)), "Even backslashes leave a live opening bracket");
    check(links.contains(new NoteLinkIndex.Target("Last", true)), "Multiple wiki links in one text run work");
    check(NoteLinkIndex.targets("[**visible**](Target.md)\n[reference][id]\n\n[id]: Referenced.md\n").size() == 2,
        "Formatted labels and reference-style Markdown links use CommonMark semantics");
  }
  private static void pathsAndDirection() throws Exception {
    Path directory = Files.createTempDirectory("diyalamp-graph-links-").toRealPath();
    try {
      Path root = Files.createDirectories(directory.resolve("notes"));
      Path sub = Files.createDirectories(root.resolve("nested"));
      Path source = Files.writeString(root.resolve("Origin.md"),
          "[[Target]] [[nested/Child|child]] [Space](Space%20name.md#part) [Plus](A+B.md)\n"
              + "[external](https://example.com) [[Missing]] [[Duplicate]]\n");
      Path target = Files.writeString(root.resolve("Target.md"), "[[Origin]]\n");
      Path child = Files.writeString(sub.resolve("Child.md"), "[[Target]] [up](../Origin.md)\n");
      Path space = Files.writeString(root.resolve("Space name.md"), "");
      Path plus = Files.writeString(root.resolve("A+B.md"), "");
      Files.writeString(root.resolve("Duplicate.md"), "");
      Files.writeString(sub.resolve("Duplicate.md"), "");
      Path outside = Files.writeString(directory.resolve("Outside.md"), "[[Origin]]");
      Files.createDirectories(root.resolve(".secret"));
      Path hidden = Files.writeString(root.resolve(".secret/Hidden.md"), "[[Origin]]");
      Files.createSymbolicLink(root.resolve("Escape.md"), outside);
      Files.createSymbolicLink(root.resolve("alias"), sub);
      NoteStore store = new NoteStore(root);
      NoteLinkIndex index = new NoteLinkIndex();
      byte[] before = Files.readAllBytes(source);
      NoteLinkIndex.Snapshot snapshot = index.scan(store);
      check(snapshot.outgoing(source).containsAll(List.of(target, child, space, plus)), "All safe outgoing destinations are resolved: " + snapshot.outgoing(source));
      check(snapshot.incoming(target).equals(List.of(source, child)), "Incoming flows are the reverse index, sorted");
      check(snapshot.outgoing(child).containsAll(List.of(target, source)), "Root-unique names and parent-relative Markdown links resolve");
      check(snapshot.edges().contains(new NoteLinkIndex.Edge(target, source)), "Reciprocal links retain both directions");
      check(snapshot.notes().size() == 7, "Hidden and symbolic-link note paths are excluded");
      check(Arrays.equals(before, Files.readAllBytes(source)), "Scanning preserves note bytes exactly");
      check(!snapshot.partial(), "A small valid workspace has a complete index");
      check(snapshot.unresolved().get(source) == 1, "Only missing wiki links are unresolved; local Duplicate wins");
      List<Path> notes = snapshot.notes();
      check(NoteLinkIndex.resolve(root, child, "Duplicate", true, notes).orElseThrow().equals(sub.resolve("Duplicate.md")),
          "Same-folder wiki target takes precedence");
      check(NoteLinkIndex.resolve(root, child, "Target#Heading|Title", true, notes).orElseThrow().equals(target),
          "Headings and labels do not change the target");
      for (String bad : List.of("../Outside.md", "https://example.com/A.md", "file:///etc/passwd", "/etc/passwd", ".secret/Hidden", "Escape.md", "alias/Child.md"))
        check(NoteLinkIndex.resolve(root, source, bad, true, notes).isEmpty(), "Unsafe destination rejected: " + bad);
      check(NoteLinkIndex.resolve(root, hidden, "Target", true, notes).isEmpty(), "Hidden source notes cannot resolve links");
      check(NoteLinkIndex.resolve(root, outside, "notes/Target", true, notes).isEmpty(), "Outside source notes cannot resolve links");
      Path different = Files.createDirectories(root.resolve("elsewhere"));
      Path other = Files.writeString(different.resolve("Other.md"), "[[Duplicate]]");
      snapshot = index.scan(store);
      check(snapshot.outgoing(other).isEmpty(), "Duplicate basename without a local match is ambiguous");
      // Same byte length and deliberately unchanged timestamp: successful autosave explicitly invalidates it.
      var modified = Files.getLastModifiedTime(target);
      Files.writeString(target, "[[Child]]\n"); Files.setLastModifiedTime(target, modified); index.invalidate(target);
      snapshot = index.scan(store);
      check(!snapshot.outgoing(target).contains(source) && snapshot.outgoing(target).contains(child), "Safe-save invalidation updates both directional lists");
      // A path that was previously safe must be revalidated at click time.
      Files.delete(space); Files.createSymbolicLink(space, outside);
      check(NoteLinkIndex.resolve(root, source, "Space%20name.md", false, notes).isEmpty(), "A replaced file symlink cannot escape via an old snapshot");
      check(snapshot.notes().stream().noneMatch(path -> path.equals(outside)), "No outside note content enters the index");
    } finally { remove(directory); }
  }
  private static void movedReplacement() throws Exception {
    Path root = Files.createTempDirectory("diyalamp-graph-move-").toRealPath();
    try {
      NoteStore store = new NoteStore(root);
      Path a = store.createFolder(root, "A"), b = store.createFolder(root, "B");
      Path one = store.create(root, "One", ""), two = store.create(root, "Two", "");
      Path first = store.create(a, "Cached", "[[One]]\n");
      Path second = store.create(b, "Cached", "[[Two]]\n");
      Path reference = store.create(b, "Reference", "[[Cached]]\n");
      Files.setLastModifiedTime(second, Files.getLastModifiedTime(first));
      check(Files.size(first) == Files.size(second)
          && Files.getLastModifiedTime(first).equals(Files.getLastModifiedTime(second)),
          "Replacement fixture must have identical size and preserved modification time");
      NoteLinkIndex index = new NoteLinkIndex();
      check(index.scan(store).outgoing(first).equals(List.of(one)), "The original path is cached with its own links");
      store.trash(first);
      Path moved = store.move(second, a);
      NoteLinkIndex.Snapshot result = index.scan(store);
      check(result.outgoing(moved).equals(List.of(two)), "A moved replacement cannot reuse another file's cached outgoing links");
      check(result.incoming(one).isEmpty() && result.incoming(two).equals(List.of(moved)),
          "Incoming links must reflect the replacement file identity too");
      check(result.outgoing(reference).equals(List.of(moved)), "A unique bare wiki link remains resolvable after the target moves");
      check(Files.readString(moved).equals("[[Two]]\n") && Files.readString(reference).equals("[[Cached]]\n"),
          "Index refresh and moving preserve file bytes without rewriting note links");
    } finally { remove(root); }
  }
  private static void bounded() throws Exception {
    Path root = Files.createTempDirectory("diyalamp-graph-limits-").toRealPath();
    try {
      Files.writeString(root.resolve("Good.md"), "[[Large]]");
      Files.writeString(root.resolve("Large.md"), "x".repeat((int) NoteLinkIndex.MAX_NOTE_BYTES + 1));
      Files.write(root.resolve("Invalid.md"), new byte[] {(byte) 0xc3, 0x28});
      NoteLinkIndex.Snapshot result = new NoteLinkIndex().scan(new NoteStore(root));
      check(result.partial(), "Large and invalid UTF-8 notes report a partial index");
      check(result.edges().size() == 1, "Other valid notes can still link toward an oversized target");
      Thread.currentThread().interrupt();
      try { new NoteLinkIndex().scan(new NoteStore(root)); throw new AssertionError("Expected cancellation"); }
      catch (java.util.concurrent.CancellationException expected) { check(true, "A canceled background scan stops promptly"); }
      finally { Thread.interrupted(); }
    } finally { remove(root); }
  }
  private static void remove(Path root) throws Exception {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }
}
