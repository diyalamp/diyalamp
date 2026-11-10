package com.diyalamp.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Local files, optimistic conflict checks, and recoverable deletion. No network access. */
public final class NoteStore {
  public record Loaded(String text, String fingerprint) {}

  public record Deleted(Path original, Path trashed, boolean folder) {
    public Deleted(Path original, Path trashed) { this(original, trashed, false); }
  }

  public record NoteListing(List<Path> paths, boolean partial) {}

  public static final long MAX_BYTES = 5 * 1024 * 1024;
  private final Path root;

  public NoteStore(Path root) throws IOException {
    Files.createDirectories(root);
    this.root = root.toRealPath();
  }

  public Path root() {
    return root;
  }

  public List<Path> notes() throws IOException {
    return listNotes(0, 0).paths();
  }

  /** Bounded discovery for an interruptible background search. */
  public NoteListing notes(int maxNotes, int maxEntries) throws IOException {
    if (maxNotes < 1 || maxEntries < 1) throw new IllegalArgumentException("Invalid listing limits");
    return listNotes(maxNotes, maxEntries);
  }

  private NoteListing listNotes(int maxNotes, int maxEntries) throws IOException {
    List<Path> found = new ArrayList<>();
    boolean bounded = maxEntries > 0;
    boolean[] partial = {false};
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          private int visited;

          private boolean withinBudget() {
            if (!bounded) return true;
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (++visited <= maxEntries) return true;
            partial[0] = true;
            return false;
          }

          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
            if (!withinBudget()) return FileVisitResult.TERMINATE;
            return directory.equals(root) || visible(directory)
                ? FileVisitResult.CONTINUE
                : FileVisitResult.SKIP_SUBTREE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
            if (!withinBudget()) return FileVisitResult.TERMINATE;
            if (attributes.isRegularFile() && visible(file) && isMarkdown(file)) {
              if (bounded && found.size() >= maxNotes) {
                partial[0] = true;
                return FileVisitResult.TERMINATE;
              }
              found.add(file);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
            if (!bounded || path.equals(root)) return super.visitFileFailed(path, error);
            partial[0] = true;
            return withinBudget() ? FileVisitResult.CONTINUE : FileVisitResult.TERMINATE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
            if (error == null || !bounded || directory.equals(root))
              return super.postVisitDirectory(directory, error);
            partial[0] = true;
            return FileVisitResult.CONTINUE;
          }
        });
    found.sort(pathOrder());
    return new NoteListing(List.copyOf(found), partial[0]);
  }

  /** Direct folder contents for a lazy explorer; no recursive scan is needed to expand a row. */
  public List<Path> children(Path directory) throws IOException {
    directory = checkedDirectory(directory);
    try (var entries = Files.list(directory)) {
      return entries
          .filter(this::visible)
          .filter(p -> !Files.isSymbolicLink(p))
          .filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)
              || (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && isMarkdown(p)))
          .sorted(Comparator.comparing((Path p) -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
              .thenComparing(pathOrder()))
          .toList();
    }
  }

  public boolean isDirectory(Path directory) {
    try {
      checkedDirectory(directory);
      return true;
    } catch (IOException | IllegalArgumentException error) {
      return false;
    }
  }

  private Comparator<Path> pathOrder() {
    return Comparator.comparing((Path p) -> root.relativize(p).toString().toLowerCase(Locale.ROOT))
        .thenComparing(Path::toString);
  }

  private boolean visible(Path path) {
    for (Path part : root.relativize(path))
      if (part.toString().startsWith(".")) return false;
    return true;
  }

  private Path checkedDirectory(Path path) throws IOException {
    return checkedDirectory(path, true);
  }

  private Path checkedDirectory(Path path, boolean allowRoot) throws IOException {
    path = checkedPath(path, allowRoot);
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Choose an existing folder inside the open notes folder.");
    return path;
  }

  public static boolean isMarkdown(Path p) {
    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
    return n.endsWith(".md") || n.endsWith(".markdown");
  }

  private Path checked(Path p) throws IOException {
    return checkedPath(p, false);
  }

  private Path checkedPath(Path path, boolean allowRoot) throws IOException {
    path = path.toAbsolutePath().normalize();
    if (!path.startsWith(root) || (!allowRoot && path.equals(root)) || !visible(path))
      throw new IOException("Choose a note or folder inside the open notes folder.");
    Path part = root;
    for (Path segment : root.relativize(path)) {
      part = part.resolve(segment);
      if (Files.isSymbolicLink(part))
        throw new IOException("Symbolic links are not opened in the notes folder.");
    }
    if (!path.equals(root) && !path.getParent().toRealPath().startsWith(root))
      throw new IOException("Choose a note or folder inside the open notes folder.");
    return path;
  }

  private Path checkedNote(Path path) throws IOException {
    path = checked(path);
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !isMarkdown(path))
      throw new IOException("Choose an existing Markdown note.");
    return path;
  }

  public Loaded load(Path p) throws IOException {
    p = checkedNote(p);
    if (Files.size(p) > MAX_BYTES)
      throw new IOException("This note is larger than the 5 MB editing limit.");
    byte[] b = readBounded(p, MAX_BYTES);
    return new Loaded(decode(b), hash(b));
  }

  private static String decode(byte[] bytes) throws IOException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (java.nio.charset.CharacterCodingException e) {
      throw new IOException(
          "This file is not valid UTF-8 Markdown. Its original bytes have not been changed.", e);
    }
  }

  public String save(Path p, String text, String expected) throws IOException {
    p = checked(p);
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_BYTES)
      throw new IOException(
          "This note is larger than the 5 MB editing limit. Export a copy before continuing.");
    if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
        || Files.size(p) > MAX_BYTES
        || !hash(readBounded(p, MAX_BYTES)).equals(expected))
      throw new ConflictException(
          "This file changed outside diyalamp. Your edits are still open. Export a copy to keep"
              + " them, then use File → Reload note from disk.");
    Path temp = Files.createTempFile(p.getParent(), ".diyalamp-save-", ".tmp");
    try {
      Files.write(temp, bytes);
      try {
        Files.move(temp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, p, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
    return hash(bytes);
  }

  public Path create(String name, String text) throws IOException {
    return create(root, name, text);
  }

  public Path create(Path directory, String name, String text) throws IOException {
    Path p = unique(directory, name);
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_BYTES) throw new IOException("This note exceeds the 5 MB editing limit.");
    Files.write(p, bytes, StandardOpenOption.CREATE_NEW);
    return p;
  }

  public Path unique(String name) throws IOException {
    return unique(root, name);
  }

  public Path unique(Path directory, String name) throws IOException {
    directory = checkedDirectory(directory);
    String base = cleanName(name);
    Path p = directory.resolve(base + ".md");
    for (int i = 2; Files.exists(p, LinkOption.NOFOLLOW_LINKS); i++)
      p = directory.resolve(base + " " + i + ".md");
    return p;
  }

  public Path rename(Path from, String name) throws IOException {
    from = checkedNote(from);
    String filename = from.getFileName().toString();
    Path to = from.resolveSibling(cleanName(name) + filename.substring(filename.lastIndexOf('.')));
    if (from.equals(to)) return from;
    return Files.move(from, to);
  }

  /** Move an existing note without overwriting a file or leaving the selected workspace. */
  public Path move(Path from, Path directory) throws IOException {
    from = checkedNote(from);
    directory = checkedDirectory(directory);
    Path destination = checked(directory.resolve(from.getFileName()));
    if (from.equals(destination)) return from;
    return Files.move(from, destination);
  }

  public Path createFolder(Path directory, String name) throws IOException {
    directory = checkedDirectory(directory);
    return Files.createDirectory(checked(directory.resolve(cleanFolderName(name))));
  }

  public Path renameFolder(Path folder, String name) throws IOException {
    folder = checkedDirectory(folder, false);
    Path destination = checked(folder.resolveSibling(cleanFolderName(name)));
    if (folder.equals(destination)) return folder;
    // On a case-insensitive volume, a normal Files.move treats these paths as the same file.
    if (folder.getFileName().toString().equalsIgnoreCase(destination.getFileName().toString())
        && Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
        && Files.isSameFile(folder, destination))
      return Files.move(folder, destination, StandardCopyOption.ATOMIC_MOVE);
    return moveDirectory(folder, destination);
  }

  private static String cleanFolderName(String name) throws IOException {
    name = name.strip();
    if (name.isBlank() || name.startsWith(".") || name.length() > 120
        || name.matches(".*[\\\\/:\\p{Cntrl}].*"))
      throw new IOException("Use a folder name of 1–120 characters without slashes or a leading dot.");
    return name;
  }

  private static String cleanName(String name) throws IOException {
    name = name.strip().replaceFirst("(?i)\\.(md|markdown)$", "");
    if (name.isBlank()
        || name.startsWith(".")
        || name.length() > 120
        || name.matches(".*[\\\\/:\\p{Cntrl}].*"))
      throw new IOException("Use a name of 1–120 characters without slashes or a leading dot.");
    return name;
  }

  public Deleted trash(Path path) throws IOException {
    return trash(checkedNote(path), false);
  }

  public Deleted deleteFolder(Path folder) throws IOException {
    return trash(checkedDirectory(folder, false), true);
  }

  private Deleted trash(Path path, boolean folder) throws IOException {
    Path trash = trashDirectory(true);
    String id = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
    Path dest = trash.resolve(folder ? id : id + "-" + path.getFileName());
    Path origin = originFile(dest);
    String relative = root.relativize(path).toString();
    Files.writeString(origin,
        trashMarker(folder) + Base64.getUrlEncoder().encodeToString(relative.getBytes(StandardCharsets.UTF_8)),
        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    try {
      trashDirectory(false);
      if (folder) moveDirectory(checkedDirectory(path, false), dest);
      else Files.move(checkedNote(path), dest);
    } catch (IOException error) {
      try {
        trashDirectory(false);
        Files.deleteIfExists(origin);
      } catch (IOException cleanup) {
        error.addSuppressed(cleanup);
      }
      throw error;
    }
    return new Deleted(path, dest, folder);
  }

  public Path restore(Deleted deleted) throws IOException {
    boolean folder = deleted.folder();
    Path source = checkedTrashItem(deleted.trashed(), folder);
    Path original = checked(deleted.original());
    if (!folder && !isMarkdown(original)) throw new IOException("A restored note must have a Markdown extension.");
    Path origin = originFile(source);
    if ((folder || Files.exists(origin, LinkOption.NOFOLLOW_LINKS))
        && !original.equals(readOriginal(source, folder)))
      throw new IOException("The original location does not match the restore record.");
    if (Files.exists(original, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("That name is already in use. Rename the existing item before restoring.");
    if (folder) moveDirectory(source, original);
    else Files.move(source, original);
    try {
      Files.deleteIfExists(origin);
    } catch (IOException ignored) {
      // The note is already restored; an orphaned metadata file must not report a failed restore.
    }
    return original;
  }

  /** Recoverable notes and folders, including their original nested location after the app restarts. */
  public List<Deleted> deleted() throws IOException {
    Path trash = trashDirectory(false);
    if (!Files.exists(trash, LinkOption.NOFOLLOW_LINKS)) return List.of();
    List<Deleted> records = new ArrayList<>();
    try (var files = Files.list(trash)) {
      for (Path item : files.sorted(Comparator.comparing(Path::toString).reversed()).toList()) {
        boolean folder = Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS);
        if (folder ? Files.exists(originFile(item), LinkOption.NOFOLLOW_LINKS) : isTrashNote(item))
          records.add(new Deleted(readOriginal(item, folder), item, folder));
      }
    }
    return List.copyOf(records);
  }

  private Path trashDirectory(boolean create) throws IOException {
    Path trash = root.resolve(".diyalamp-trash");
    if (!Files.exists(trash, LinkOption.NOFOLLOW_LINKS)) {
      if (!create) return trash;
      Files.createDirectory(trash);
    }
    if (Files.isSymbolicLink(trash) || !Files.isDirectory(trash, LinkOption.NOFOLLOW_LINKS)
        || !trash.toRealPath().equals(trash))
      throw new IOException("The deleted-notes folder must be a real folder inside the notes folder.");
    return trash;
  }

  private Path checkedTrashItem(Path path, boolean folder) throws IOException {
    Path trash = trashDirectory(false);
    path = path.toAbsolutePath().normalize();
    if (!trash.equals(path.getParent()) || !(folder ? Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) : isTrashNote(path)))
      throw new IOException("Choose a deleted note or folder from this workspace.");
    return path;
  }

  private static boolean isTrashNote(Path path) {
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && isMarkdown(path);
  }

  private static Path moveDirectory(Path source, Path destination) throws IOException {
    if (!Files.getFileStore(source).equals(Files.getFileStore(destination.getParent())))
      throw new IOException("The folder cannot be moved between volumes.");
    return Files.move(source, destination);
  }

  private static String trashMarker(boolean folder) {
    return folder ? "diyalamp-trash-folder-v1\n" : "diyalamp-trash-v1\n";
  }

  private Path readOriginal(Path trashed, boolean folder) throws IOException {
    Path origin = originFile(trashed);
    if (!Files.exists(origin, LinkOption.NOFOLLOW_LINKS)) {
      if (folder) throw new IOException("The deleted folder's restore record is missing.");
      String name = trashed.getFileName().toString().replaceFirst("^\\d+-[a-f0-9-]{36}-", "");
      return root.resolve(name);
    }
    if (!Files.isRegularFile(origin, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("The restore record is not a regular file.");
    try {
      String data = decode(readBounded(origin, 16384));
      String prefix = trashMarker(folder);
      if (!data.startsWith(prefix)) throw new IllegalArgumentException("Unknown restore record");
      Path relative = Path.of(decode(Base64.getUrlDecoder().decode(data.substring(prefix.length()))));
      if (relative.isAbsolute() || relative.toString().isBlank()
          || relative.normalize().startsWith("..") || !relative.equals(relative.normalize()))
        throw new IllegalArgumentException("Invalid restore path");
      Path original = root.resolve(relative);
      if (!visible(original) || (!folder && !isMarkdown(original)))
        throw new IllegalArgumentException("Invalid restore path");
      return original;
    } catch (IllegalArgumentException error) {
      throw new IOException("The restore record is invalid. The deleted item is unchanged.", error);
    }
  }

  private static Path originFile(Path trashed) {
    return trashed.resolveSibling(trashed.getFileName() + ".origin");
  }

  private static byte[] readBounded(Path path, long maximum) throws IOException {
    try (var input = Files.newInputStream(path)) {
      byte[] data = input.readNBytes((int) maximum + 1);
      if (data.length > maximum) throw new IOException("This file exceeds the supported size limit.");
      return data;
    }
  }

  public static String hash(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  public static final class ConflictException extends IOException {
    public ConflictException(String s) {
      super(s);
    }
  }
}
