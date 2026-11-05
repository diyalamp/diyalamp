package com.diyalamp.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exercises folder navigation and recovery against fictional files only. */
public final class NoteStoreFolderTests {
  private static int checks;

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  @FunctionalInterface
  private interface Operation {
    void run() throws IOException;
  }

  private static void rejected(Operation operation, String message) throws IOException {
    boolean rejected = false;
    try {
      operation.run();
    } catch (IOException expected) {
      rejected = true;
    }
    check(rejected, message);
  }

  private static Path fixture() throws IOException {
    return Files.createTempDirectory("diyalamp-folder-tests-").toRealPath();
  }

  public static void main(String[] args) throws Exception {
    explorerAndNestedCreation();
    fileAndFolderBoundaries();
    persistedNestedRecovery();
    restoreBoundaries();
    legacyAndInvalidRestoreRecords();
    existingFileSafety();
    System.out.println("Passed " + checks + " folder storage checks.");
  }

  private static void explorerAndNestedCreation() throws Exception {
    Path root = fixture();
    NoteStore store = new NoteStore(root);
    Path folder = Files.createDirectory(root.resolve("Research"));
    Path empty = Files.createDirectory(root.resolve("Empty"));
    Files.createDirectory(root.resolve(".hidden"));
    Files.writeString(root.resolve(".hidden/private.md"), "Hidden fixture\n");
    Files.writeString(root.resolve("readme.txt"), "Not Markdown\n");
    Files.writeString(root.resolve(".hidden.md"), "Hidden fixture\n");
    Path rootNote = store.create("Zebra", "Root note\n");
    Path nested = store.create(folder, "Résumé.markdown", "Nested note\n");
    Path duplicate = store.create(folder, "Résumé", "Another note\n");
    check(store.children(root).equals(List.of(empty, folder, rootNote)),
        "Explorer children must list direct visible folders before Markdown files");
    check(store.children(folder).equals(List.of(duplicate, nested)),
        "Expanding a folder must reveal its own Markdown files with stable names");
    check(!Files.exists(root.resolve("Résumé.md")), "A selected-folder create must not write at root");
    check(store.children(empty).isEmpty(), "An empty folder must remain visible and expandable");
    check(store.isDirectory(root) && store.isDirectory(folder), "The root and child folders are valid");
    check(!store.isDirectory(nested), "A note must not be treated as a directory");
    check(!store.notes().contains(root.resolve(".hidden/private.md")),
        "Hidden directory subtrees must be excluded from recursive note discovery");
    check(store.notes().contains(nested), "Nested Markdown must remain available to search");
    Path deep = folder;
    for (int i = 0; i < 14; i++) deep = Files.createDirectory(deep.resolve("level"));
    Path deepNote = store.create(deep, "Deep", "Deep fixture\n");
    check(store.notes().contains(deepNote), "Recursive note search must not silently stop at depth 12");
    rejected(() -> store.create(folder, "../escape", "Bad\n"), "Nested names cannot escape their folder");
    rejected(() -> store.rename(duplicate, "Résumé"), "Rename collisions must not overwrite notes");
    check(Files.readString(nested).equals("Nested note\n"), "A rename collision keeps existing bytes");
    Path renamed = store.rename(duplicate, "Renamed");
    check(renamed.equals(folder.resolve("Renamed.md")), "Rename must stay in the note's folder");
    check(Files.readString(renamed).equals("Another note\n"), "Rename preserves the note contents");
  }

  private static void fileAndFolderBoundaries() throws Exception {
    Path root = fixture(), outside = fixture();
    NoteStore store = new NoteStore(root);
    Path insideFolder = Files.createDirectory(root.resolve("Inside"));
    Path outsideNote = Files.writeString(outside.resolve("outside.md"), "Outside fixture\n");
    Files.createSymbolicLink(root.resolve("Linked folder"), outside);
    Files.createSymbolicLink(root.resolve("Linked note.md"), outsideNote);
    Files.createSymbolicLink(root.resolve("Inside alias"), insideFolder);
    check(store.children(root).equals(List.of(insideFolder)), "Explorer must not follow file or directory symlinks");
    check(!store.isDirectory(root.resolve("Linked folder")), "A linked folder is not an editable directory");
    check(!store.isDirectory(outside), "A directory outside the open root is not editable");
    rejected(() -> store.children(outside), "Outside directories cannot be enumerated through the store");
    rejected(() -> store.children(root.resolve("Linked folder")), "Directory expansion must reject symlinks");
    rejected(() -> store.create(root.resolve("Linked folder"), "Escape", "Bad\n"),
        "Creating through a symlink cannot write outside root");
    rejected(() -> store.load(root.resolve("Linked note.md")), "Linked notes cannot be opened");
    rejected(() -> store.load(root.resolve("Linked folder/outside.md")),
        "A symlinked ancestor cannot bypass the live-note boundary");
    check(!Files.exists(outside.resolve("Escape.md")), "Rejected nested creation leaves outside files alone");
    Path hidden = Files.createDirectory(root.resolve(".private"));
    rejected(() -> store.create(hidden, "Hidden", "Bad\n"), "The explorer cannot create hidden internal notes");
    Path note = store.create("Kept", "Keep me\n");
    Files.createSymbolicLink(root.resolve(".diyalamp-trash"), outside);
    rejected(() -> store.trash(note), "A symlinked trash directory must be rejected before moving the note");
    check(Files.readString(note).equals("Keep me\n"), "Rejected trash operation preserves the original note");
    try (var files = Files.list(outside)) {
      check(files.count() == 1, "Rejected trash operation does not populate outside storage");
    }
  }

  private static void persistedNestedRecovery() throws Exception {
    Path root = fixture();
    NoteStore store = new NoteStore(root);
    Path firstFolder = Files.createDirectory(root.resolve("First"));
    Path secondFolder = Files.createDirectory(root.resolve("Second"));
    Path first = store.create(firstFolder, "Same name", "First bytes\n");
    Path second = store.create(secondFolder, "Same name", "Second bytes\n");
    NoteStore.Deleted firstDeleted = store.trash(first), secondDeleted = store.trash(second);
    check(!Files.exists(first) && !Files.exists(second), "Deleted notes leave the live tree");
    check(store.notes().isEmpty(), "Trash contents must not appear in recursive note search");
    NoteStore restarted = new NoteStore(root);
    List<NoteStore.Deleted> deleted = restarted.deleted();
    check(deleted.size() == 2, "A new store instance must discover both recovery records");
    check(deleted.contains(firstDeleted) && deleted.contains(secondDeleted),
        "Persisted recovery must preserve distinct nested paths for identical leaf names");
    check(restarted.restore(firstDeleted).equals(first), "First nested note returns to its original folder");
    check(restarted.restore(secondDeleted).equals(second), "Second nested note returns to its original folder");
    check(Files.readString(first).equals("First bytes\n") && Files.readString(second).equals("Second bytes\n"),
        "Nested recovery must preserve the exact respective contents");
    check(restarted.deleted().isEmpty(), "Restored entries disappear from the deleted-notes list");
    check(!Files.exists(firstDeleted.trashed().resolveSibling(firstDeleted.trashed().getFileName() + ".origin")),
        "Successful restore removes its recovery sidecar");
  }

  private static void restoreBoundaries() throws Exception {
    Path root = fixture(), outside = fixture();
    NoteStore store = new NoteStore(root);
    Path folder = Files.createDirectory(root.resolve("Nested"));
    Path original = store.create(folder, "Draft", "Recovery bytes\n");
    NoteStore.Deleted deleted = store.trash(original);
    Files.delete(folder);
    Files.createSymbolicLink(folder, outside);
    rejected(() -> store.restore(deleted), "Replacing a deleted note's parent with a symlink must block restore");
    check(Files.exists(deleted.trashed()), "A rejected restore keeps the recoverable note");
    check(!Files.exists(outside.resolve("Draft.md")), "Restore cannot write outside the open folder");
    Files.delete(folder);
    Files.createDirectory(folder);
    store.restore(deleted);
    NoteStore.Deleted collision = store.trash(original);
    Files.writeString(original, "New live version\n");
    rejected(() -> store.restore(collision), "Restore must not overwrite a new live note");
    check(Files.readString(original).equals("New live version\n"), "Restore collision keeps the live bytes");
    check(Files.readString(collision.trashed()).equals("Recovery bytes\n"), "Restore collision keeps the deleted bytes");
    rejected(() -> store.restore(new NoteStore.Deleted(outside.resolve("escape.md"), collision.trashed())),
        "A forged outside restore destination must be rejected");
    Path outsideFile = Files.writeString(outside.resolve("outside.md"), "Outside bytes\n");
    rejected(() -> store.restore(new NoteStore.Deleted(root.resolve("not-here.md"), outsideFile)),
        "A restore source outside the store's trash must be rejected");
    rejected(() -> store.trash(folder), "A note-delete operation must not move an entire folder");
  }

  private static void legacyAndInvalidRestoreRecords() throws Exception {
    Path root = fixture();
    NoteStore store = new NoteStore(root);
    Path trash = Files.createDirectory(root.resolve(".diyalamp-trash"));
    Path legacy = Files.writeString(trash.resolve("123-" + UUID.randomUUID() + "-Old note.md"), "Legacy bytes\n");
    NoteStore.Deleted old = store.deleted().getFirst();
    check(old.original().equals(root.resolve("Old note.md")), "Legacy trash entries retain their original leaf names");
    store.restore(old);
    check(Files.readString(root.resolve("Old note.md")).equals("Legacy bytes\n"), "Legacy deleted notes remain recoverable");
    check(!Files.exists(legacy), "Legacy restoration moves the old trash entry");
    Path live = store.create("Tamper probe", "Kept bytes\n");
    NoteStore.Deleted deleted = store.trash(live);
    Path sidecar = deleted.trashed().resolveSibling(deleted.trashed().getFileName() + ".origin");
    Files.writeString(sidecar, "diyalamp-trash-v1\n"
        + Base64.getUrlEncoder().encodeToString("../escape.md".getBytes(StandardCharsets.UTF_8)));
    rejected(store::deleted, "A traversal path in a recovery record must not be accepted");
    rejected(() -> store.restore(deleted), "Restore must reject invalid recovery metadata");
    check(Files.readString(deleted.trashed()).equals("Kept bytes\n"), "Invalid metadata leaves deleted note bytes untouched");
  }

  private static void existingFileSafety() throws Exception {
    Path root = fixture();
    NoteStore store = new NoteStore(root);
    Path note = store.create("Conflict", "Original\n");
    NoteStore.Loaded loaded = store.load(note);
    Files.writeString(note, "External change\n");
    rejected(() -> store.save(note, "Clobber", loaded.fingerprint()), "External edit conflict detection remains active");
    check(Files.readString(note).equals("External change\n"), "A conflict does not overwrite the external version");
    Path invalid = root.resolve("Invalid.md");
    byte[] original = {(byte) 0xc3, (byte) 0x28};
    Files.write(invalid, original);
    rejected(() -> store.load(invalid), "Invalid UTF-8 must be rejected rather than rewritten");
    check(Arrays.equals(Files.readAllBytes(invalid), original), "Rejected UTF-8 import leaves original bytes unchanged");
    rejected(() -> store.create(root, "Too large", "x".repeat((int) NoteStore.MAX_BYTES + 1)),
        "A newly created note cannot exceed the editor size limit");
    check(!Files.exists(root.resolve("Too large.md")), "Oversized creation must not leave an unreadable file");
    NoteStore.Loaded beforeGrowth = store.load(note);
    Files.write(note, new byte[(int) NoteStore.MAX_BYTES + 1]);
    rejected(() -> store.save(note, "Small", beforeGrowth.fingerprint()),
        "A file grown externally beyond the size limit must not be read or overwritten without bounds");
    check(Files.size(note) == NoteStore.MAX_BYTES + 1, "The oversized external version remains unchanged");
  }
}
