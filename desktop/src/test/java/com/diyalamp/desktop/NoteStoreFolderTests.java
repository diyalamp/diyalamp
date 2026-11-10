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
    documentDiscovery();
    fileAndFolderBoundaries();
    folderRenameAndRecovery();
    folderMutationBoundaries();
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

  private static void documentDiscovery() throws Exception {
    Path root = fixture(), outside = fixture();
    NoteStore store = new NoteStore(root);
    Path references = Files.createDirectory(root.resolve("References"));
    Path note = store.create("A note", "Editable note\n");
    Path pdf = Files.writeString(references.resolve("Book.PDF"), "PDF fixture");
    Path bmp = Files.writeString(root.resolve("Color.BMP"), "BMP fixture");
    Path text = Files.writeString(root.resolve("Context.txt"), "Plain-text fixture");
    Path gif = Files.writeString(root.resolve("Diagram.GiF"), "GIF fixture");
    Path png = Files.writeString(root.resolve("Drawing.png"), "PNG fixture");
    Path jpeg = Files.writeString(root.resolve("Photo.JPEG"), "JPEG fixture");
    Path jpg = Files.writeString(root.resolve("Thumbnail.jpg"), "JPEG fixture");
    Path unsupported = Files.writeString(root.resolve("Unsupported.zip"), "Unsupported fixture");
    Path hidden = Files.writeString(root.resolve(".private.txt"), "Hidden fixture");
    Path outsideFile = Files.writeString(outside.resolve("outside.pdf"), "Outside fixture");
    Path linkedFile = Files.createSymbolicLink(root.resolve("Linked.jpg"), outsideFile);
    Path linkedFolder = Files.createSymbolicLink(root.resolve("Link"), outside);

    check(NoteStore.documentKind(note) == NoteStore.DocumentKind.MARKDOWN
            && NoteStore.documentKind(text) == NoteStore.DocumentKind.TEXT
            && NoteStore.documentKind(pdf) == NoteStore.DocumentKind.PDF
            && List.of(png, jpeg, jpg, gif, bmp).stream().allMatch(
                path -> NoteStore.documentKind(path) == NoteStore.DocumentKind.IMAGE)
            && NoteStore.documentKind(unsupported) == NoteStore.DocumentKind.UNSUPPORTED,
        "Document kinds must recognize supported extensions case-insensitively");
    check(store.children(root).equals(List.of(references, note)) && store.notes().equals(List.of(note)),
        "Existing Markdown-only explorers and indexes must exclude preview documents");
    check(store.documents(root).equals(List.of(references, note, bmp, text, gif, png, jpeg, jpg))
            && store.documents(references).equals(List.of(pdf)),
        "Document explorers must show eligible direct files and folders in the existing order");
    var all = store.documents(50, 100);
    check(all.paths().equals(List.of(note, bmp, text, gif, png, jpeg, pdf, jpg)) && !all.partial(),
        "Filename discovery must find nested and direct documents without following hidden paths or symlinks");
    check(store.documents(2, 100).paths().size() == 2 && store.documents(2, 100).partial()
            && store.documents(50, 1).partial(),
        "Document discovery must obey both bounds and report partial results");
    check(store.checkedDocument(text).equals(text) && store.checkedDocument(pdf).equals(pdf),
        "Verified visible files must be available to the preview reader");
    rejected(() -> store.checkedDocument(root), "The workspace root cannot be previewed");
    rejected(() -> store.checkedDocument(references), "A directory cannot be previewed");
    rejected(() -> store.checkedDocument(unsupported), "Unsupported files cannot be previewed");
    rejected(() -> store.checkedDocument(hidden), "Hidden files cannot be previewed");
    rejected(() -> store.checkedDocument(root.resolve("Missing.txt")), "Missing files cannot be previewed");
    rejected(() -> store.checkedDocument(outsideFile), "Outside files cannot be previewed");
    rejected(() -> store.checkedDocument(linkedFile), "File symlinks cannot be previewed");
    rejected(() -> store.checkedDocument(linkedFolder.resolve("outside.pdf")),
        "A symlinked ancestor cannot bypass document preview validation");
    rejected(() -> store.load(text), "The editable Markdown loader must reject preview documents");
    check(Files.readString(outsideFile).equals("Outside fixture")
            && Files.readString(text).equals("Plain-text fixture"),
        "Document discovery and validation must leave source bytes untouched");
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

  private static void folderRenameAndRecovery() throws Exception {
    Path root = fixture(), outside = fixture();
    NoteStore store = new NoteStore(root);
    Path parent = store.createFolder(root, "Research");
    Path folder = store.createFolder(parent, "Drafts");
    Path nested = store.createFolder(folder, "Notes");
    store.create(nested, "Plan", "Keep the note\n");
    Files.write(nested.resolve("data.bin"), new byte[] {0, 1, -1});
    Files.writeString(folder.resolve(".hidden"), "Keep the hidden file\n");
    Files.createDirectory(folder.resolve("Empty"));
    Path outsideFile = Files.writeString(outside.resolve("reference.txt"), "Keep the outside file\n");
    Files.createSymbolicLink(folder.resolve("Reference"), outsideFile);
    NoteStore.Deleted deletedNote = store.trash(store.create("Temporary", "Note recovery\n"));

    check(store.renameFolder(folder, "Drafts").equals(folder), "An unchanged folder name is a no-op");
    Path renamed = store.renameFolder(folder, "drafts");
    check(store.children(parent).equals(List.of(renamed)) && renamed.getFileName().toString().equals("drafts"),
        "A case-only rename changes the directory's spelling on disk");
    renamed = store.renameFolder(renamed, "Journal.md");
    check(renamed.equals(parent.resolve("Journal.md")) && !Files.exists(folder)
            && store.notes().equals(List.of(renamed.resolve("Notes/Plan.md"))),
        "Renaming a folder preserves its contents and its requested name, including a dot");

    NoteStore.Deleted deletedFolder = store.deleteFolder(renamed);
    check(deletedFolder.folder() && deletedFolder.original().equals(renamed) && !Files.exists(renamed)
            && Files.isDirectory(deletedFolder.trashed()),
        "Deleting moves the whole folder to the recoverable workspace trash");
    check(store.notes().isEmpty() && store.children(parent).isEmpty(),
        "Neither the deleted folder nor its descendants appear in the workspace");
    NoteStore restarted = new NoteStore(root);
    check(restarted.deleted().size() == 2 && restarted.deleted().containsAll(List.of(deletedNote, deletedFolder)),
        "The same recovery list rediscovers notes and folders with their type and location");
    check(restarted.restore(deletedFolder).equals(renamed), "Recovery returns the folder to its original nested path");
    check(Files.readString(renamed.resolve("Notes/Plan.md")).equals("Keep the note\n")
            && Arrays.equals(Files.readAllBytes(renamed.resolve("Notes/data.bin")), new byte[] {0, 1, -1})
            && Files.readString(renamed.resolve(".hidden")).equals("Keep the hidden file\n")
            && Files.isDirectory(renamed.resolve("Empty")),
        "Recovery preserves Markdown, binary assets, hidden files and empty folders");
    check(Files.readSymbolicLink(renamed.resolve("Reference")).equals(outsideFile)
            && Files.readString(outsideFile).equals("Keep the outside file\n"),
        "Moving a folder preserves a contained symlink without touching its external target");
    check(restarted.deleted().equals(List.of(deletedNote)), "Restoring a folder clears only its own recovery entry");
  }

  private static void folderMutationBoundaries() throws Exception {
    Path root = fixture(), outside = fixture();
    NoteStore store = new NoteStore(root);
    Path parent = store.createFolder(root, "Parent");
    Path original = store.createFolder(parent, "Drafts");
    Path note = store.create(original, "Keep", "Original bytes\n");
    Path collision = store.createFolder(parent, "Collision");
    Path outsideFolder = Files.createDirectory(outside.resolve("Linked"));
    Files.createSymbolicLink(root.resolve("Alias"), outside);
    Files.createSymbolicLink(parent.resolve("Link"), outsideFolder);
    rejected(() -> store.renameFolder(root, "Other"), "Renaming cannot move the workspace root");
    rejected(() -> store.deleteFolder(root), "Deleting cannot move the workspace root");
    rejected(() -> store.renameFolder(outside, "Other"), "Renaming cannot move an outside folder");
    rejected(() -> store.deleteFolder(root.resolve("Alias/Linked")), "Deleting must reject symlinked ancestors");
    rejected(() -> store.deleteFolder(parent.resolve("Link")), "Deleting must reject a symlinked folder");
    rejected(() -> store.deleteFolder(note), "The folder action cannot delete a note by mistake");
    rejected(() -> store.renameFolder(original, "../Outside"), "Folder rename rejects traversing names");
    rejected(() -> store.renameFolder(original, "Collision"), "Folder rename rejects existing empty folders too");
    rejected(() -> store.renameFolder(original, "Link"), "Folder rename cannot overwrite a symlink");
    check(Files.readString(note).equals("Original bytes\n") && Files.isDirectory(collision) && Files.isDirectory(outsideFolder),
        "Rejected folder actions preserve the source and all existing destinations");

    NoteStore.Deleted deleted = store.deleteFolder(original);
    Files.createDirectory(original);
    rejected(() -> store.restore(deleted), "Restore cannot overwrite a new empty folder");
    Files.delete(original);
    rejected(() -> store.restore(new NoteStore.Deleted(parent.resolve("Forged"), deleted.trashed(), true)),
        "Folder recovery rejects a forged original path");
    rejected(() -> store.restore(new NoteStore.Deleted(original, deleted.trashed())),
        "A folder payload cannot be restored through a note record");
    Files.move(parent, root.resolve("Renamed parent"));
    Files.createSymbolicLink(parent, outside);
    rejected(() -> store.restore(deleted), "A symlinked restore parent is rejected");
    check(Files.readString(deleted.trashed().resolve("Keep.md")).equals("Original bytes\n")
            && !Files.exists(outside.resolve("Drafts")),
        "Rejected restores keep the whole folder and never write outside the workspace");
    Files.delete(parent);
    Files.move(root.resolve("Renamed parent"), parent);
    check(store.restore(deleted).equals(original), "The same entry can be safely retried when its parent returns");
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
