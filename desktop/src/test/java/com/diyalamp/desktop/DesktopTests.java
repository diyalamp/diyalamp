package com.diyalamp.desktop;

import java.nio.file.*;
import java.util.*;
import javax.swing.text.*;

public final class DesktopTests {
  private static int checks;

  private static void check(boolean ok, String why) {
    checks++;
    if (!ok) throw new AssertionError(why);
  }

  public static void main(String[] args) throws Exception {
    String source =
        "# A thought\n\n"
            + "Hello **bold** and *italic* with `code` and [a link](https://example.com).\n\n"
            + "- One\n"
            + "- [x] Done\n"
            + "- [ ] Later\n\n"
            + "2. Second\n\n"
            + "> A quote\n\n"
            + "```java\n"
            + "String value = \"**not bold**\";\n"
            + "```\n\n"
            + "---\n";
    StyledDocument doc = Markdown.read(source);
    String rendered = doc.getText(0, doc.getLength());
    check(!rendered.contains("# A thought"), "Heading markers must render as formatting");
    check(!rendered.contains("**bold**"), "Emphasis markers must render as formatting");
    check(rendered.contains("**not bold**"), "Code blocks must stay literal");
    String exported = Markdown.write(doc);
    check(
        source.equals(exported),
        "Common Markdown must round-trip without losing structures.\n" + exported);
    String hostile = "<script>alert('x')</script>\n![remote](https://example.com/private.png)\n";
    StyledDocument plain = Markdown.read(hostile);
    check(plain.getText(0, plain.getLength()).contains("<script>"), "HTML remains literal text");
    check(
        Markdown.write(plain).contains("<script>"),
        "Unsupported markup is preserved rather than executed or deleted");
    Path root = Files.createTempDirectory("diyalamp-store-tests-");
    NoteStore store = new NoteStore(root);
    Path note = store.create("Thought", "# Original\n");
    NoteStore.Loaded read = store.load(note);
    String next = store.save(note, "# Revised\n", read.fingerprint());
    check(
        Files.readString(note).equals("# Revised\n"),
        "Save must replace with the complete new note");
    check(store.load(note).fingerprint().equals(next), "Saved fingerprint must match disk");
    Files.writeString(note, "External edit\n");
    boolean conflict = false;
    try {
      store.save(note, "Clobbered", next);
    } catch (NoteStore.ConflictException e) {
      conflict = true;
    }
    check(conflict, "External edits must be detected");
    check(
        Files.readString(note).equals("External edit\n"),
        "Conflicting save must keep external changes intact");
    Path duplicate = store.create("Thought", "Second\n");
    check(!duplicate.equals(note), "New-note names must not overwrite an existing note");
    boolean renameConflict = false;
    try {
      store.rename(duplicate, "Thought");
    } catch (java.io.IOException e) {
      renameConflict = true;
    }
    check(renameConflict, "Rename must not overwrite existing notes");
    NoteStore.Deleted deleted = store.trash(note);
    check(!Files.exists(note) && Files.exists(deleted.trashed()), "Deletion must be recoverable");
    check(
        !store.notes().contains(deleted.trashed()),
        "Deleted notes must not appear in the live library");
    store.restore(deleted);
    check(Files.readString(note).equals("External edit\n"), "Restore must recover original bytes");
    boolean traversal = false;
    try {
      store.create("../escape", "bad");
    } catch (java.io.IOException e) {
      traversal = true;
    }
    check(traversal, "Names must not escape the note folder");
    Path outside = Files.createTempFile("diyalamp-outside-", ".md");
    try {
      Path link = root.resolve("linked.md");
      Files.createSymbolicLink(link, outside);
      boolean rejected = false;
      try {
        store.load(link);
      } catch (java.io.IOException e) {
        rejected = true;
      }
      check(rejected, "Symlinked notes must not cross the folder boundary");
    } finally {
      Files.deleteIfExists(outside);
    }
    check(
        Files.list(root).noneMatch(p -> p.getFileName().toString().startsWith(".diyalamp-save-")),
        "Atomic save temporary files must be cleaned up");
    System.out.println("Passed " + checks + " desktop checks.");
  }
}
