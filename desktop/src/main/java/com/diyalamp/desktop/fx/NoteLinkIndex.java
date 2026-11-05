package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.NoteStore;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** Read-only local-link index. Call scan off the application thread, never on each keystroke. */
public final class NoteLinkIndex {
  static final int MAX_NOTES = 5_000, MAX_ENTRIES = 30_000, MAX_LINKS = 100_000;
  static final long MAX_NOTE_BYTES = 1024 * 1024, MAX_TOTAL_BYTES = 32L * 1024 * 1024;
  private static final Parser PARSER = Parser.builder()
      .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build();

  public record Edge(Path from, Path to) { }
  public record Target(String destination, boolean wiki) { }
  public record Snapshot(Path root, List<Path> notes, Set<Edge> edges,
      Map<Path, Integer> unresolved, boolean partial) {
    public Snapshot {
      notes = List.copyOf(notes); edges = Set.copyOf(edges); unresolved = Map.copyOf(unresolved);
    }
    public List<Path> incoming(Path note) {
      return edges.stream().filter(e -> e.to().equals(note)).map(Edge::from).distinct().sorted().toList();
    }
    public List<Path> outgoing(Path note) {
      return edges.stream().filter(e -> e.from().equals(note)).map(Edge::to).distinct().sorted().toList();
    }
  }
  private record Cached(long size, FileTime modified, Object fileKey, List<Target> targets) { }
  private final Map<Path, Cached> cache = new HashMap<>();

  public void invalidate(Path note) { cache.remove(note); }
  public void clear() { cache.clear(); }

  public Snapshot scan(NoteStore store) throws IOException {
    Path root = store.root();
    List<Path> notes = new ArrayList<>();
    boolean[] partial = {false};
    int[] entries = {0};
    Files.walkFileTree(root, new SimpleFileVisitor<>() {
      @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
        interrupted();
        if (++entries[0] > MAX_ENTRIES) { partial[0] = true; return FileVisitResult.TERMINATE; }
        return directory.equals(root) || visible(root, directory)
            ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
      }
      @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
        interrupted();
        if (++entries[0] > MAX_ENTRIES || notes.size() >= MAX_NOTES) {
          partial[0] = true; return FileVisitResult.TERMINATE;
        }
        if (attributes.isRegularFile() && visible(root, path) && NoteStore.isMarkdown(path)) notes.add(path);
        return FileVisitResult.CONTINUE;
      }
      @Override public FileVisitResult visitFileFailed(Path path, IOException error) {
        partial[0] = true; return FileVisitResult.CONTINUE;
      }
    });
    notes.sort(Comparator.naturalOrder());
    cache.keySet().retainAll(notes);
    Resolver resolver = new Resolver(root, notes);
    Set<Edge> edges = new LinkedHashSet<>();
    Map<Path, Integer> unresolved = new HashMap<>();
    long total = 0;
    for (Path note : notes) {
      interrupted();
      try {
        BasicFileAttributes attributes = Files.readAttributes(note, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.size() > MAX_NOTE_BYTES || (total += attributes.size()) > MAX_TOTAL_BYTES) {
          partial[0] = true; continue;
        }
        Cached old = cache.get(note);
        List<Target> targets;
        // A different file can reuse this path, size, and timestamp after a move. If the
        // provider cannot identify files, reparse rather than guessing that it is unchanged.
        if (old != null && attributes.fileKey() != null && attributes.fileKey().equals(old.fileKey())
            && old.size() == attributes.size() && old.modified().equals(attributes.lastModifiedTime()))
          targets = old.targets();
        else {
          // NoteStore rechecks every path segment, UTF-8, and the bounded file read.
          targets = targets(store.load(note).text());
          cache.put(note, new Cached(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey(), targets));
        }
        for (Target target : targets) {
          if (edges.size() >= MAX_LINKS) { partial[0] = true; break; }
          Optional<Path> destination = resolver.resolve(note, target.destination(), target.wiki());
          if (destination.isPresent()) edges.add(new Edge(note, destination.get()));
          else if (target.wiki()) unresolved.merge(note, 1, Integer::sum);
        }
      } catch (IOException | IllegalArgumentException error) {
        partial[0] = true;
        cache.remove(note);
      }
    }
    return new Snapshot(root, notes, edges, unresolved, partial[0]);
  }

  /** Markdown file links and [[note]], [[folder/note#heading|label]]; code and escaped brackets are literal. */
  public static List<Target> targets(String markdown) {
    List<Target> result = new ArrayList<>();
    String[] lines = markdown.split("\\r\\n|\\r|\\n", -1);
    PARSER.parse(markdown).accept(new AbstractVisitor() {
      @Override public void visit(Link link) {
        if (result.size() < MAX_LINKS) result.add(new Target(link.getDestination(), false));
        // A Markdown link label is not a second wiki link.
      }
      @Override public void visit(Text text) {
        for (SourceSpan span : text.getSourceSpans()) {
          if (span.getLineIndex() >= lines.length) continue;
          String line = lines[span.getLineIndex()];
          int end = Math.min(line.length(), span.getColumnIndex() + span.getLength());
          String raw = line.substring(Math.min(span.getColumnIndex(), end), end);
          for (int i = 0; i + 3 < raw.length() && result.size() < MAX_LINKS; i++) {
            if (raw.charAt(i) != '[' || raw.charAt(i + 1) != '[' || escaped(raw, i)) continue;
            int close = i + 2;
            while ((close = raw.indexOf("]]", close)) >= 0 && escaped(raw, close)) close += 2;
            if (close < 0) break;
            String target = raw.substring(i + 2, close).strip();
            if (!target.isEmpty() && !target.contains("[")) result.add(new Target(target, true));
            i = close + 1;
          }
        }
      }
    });
    return List.copyOf(result);
  }

  /** The editor emits a target only. Resolution never writes or creates a note. */
  public static Optional<Path> resolve(Path root, Path source, String destination,
      boolean wiki, List<Path> indexedNotes) {
    return new Resolver(root, indexedNotes).resolve(source, destination, wiki);
  }

  private static final class Resolver {
    private final Path root;
    private final Set<Path> notes;
    private final Map<String, List<Path>> names = new HashMap<>();
    private final Map<String, List<Path>> relative = new HashMap<>();
    Resolver(Path root, List<Path> notes) {
      this.root = root.toAbsolutePath().normalize(); this.notes = Set.copyOf(notes);
      for (Path note : notes) {
        if (!note.startsWith(this.root)) continue;
        names.computeIfAbsent(stem(note.getFileName().toString()), unused -> new ArrayList<>()).add(note);
        relative.computeIfAbsent(stem(this.root.relativize(note).toString()), unused -> new ArrayList<>()).add(note);
      }
    }
    Optional<Path> resolve(Path source, String destination, boolean wiki) {
      if (!safe(root, source)) return Optional.empty();
      String name = destination.strip();
      if (wiki) name = name.split("\\|", 2)[0].strip();
      int fragment = name.indexOf('#'); if (fragment >= 0) name = name.substring(0, fragment);
      if (!wiki) {
        int query = name.indexOf('?'); if (query >= 0) name = name.substring(0, query);
        try { name = URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException error) { return Optional.empty(); }
      }
      if (name.isBlank() || name.contains(":") || name.contains("\\") || name.startsWith("/")
          || name.chars().anyMatch(Character::isISOControl)) return Optional.empty();
      try {
        Path local = source.getParent().resolve(name).normalize();
        Optional<Path> direct = candidate(local, wiki);
        if (direct.isPresent() || !wiki) return direct;
        if (name.contains("/")) {
          List<Path> atRoot = relative.getOrDefault(stem(name), List.of());
          return atRoot.size() == 1 && safe(root, atRoot.getFirst()) ? Optional.of(atRoot.getFirst()) : Optional.empty();
        }
        List<Path> matches = names.getOrDefault(stem(name), List.of());
        return matches.size() == 1 && safe(root, matches.getFirst()) ? Optional.of(matches.getFirst()) : Optional.empty();
      } catch (InvalidPathException error) { return Optional.empty(); }
    }
    private Optional<Path> candidate(Path candidate, boolean wiki) {
      if (notes.contains(candidate) && safe(root, candidate)) return Optional.of(candidate);
      if (!wiki || NoteStore.isMarkdown(candidate)) return Optional.empty();
      for (String extension : List.of(".md", ".markdown")) {
        Path withExtension = candidate.resolveSibling(candidate.getFileName() + extension);
        if (notes.contains(withExtension) && safe(root, withExtension)) return Optional.of(withExtension);
      }
      return Optional.empty();
    }
  }

  private static String stem(String name) {
    String lower = name.toLowerCase(Locale.ROOT).replace('\\', '/');
    return lower.endsWith(".markdown") ? lower.substring(0, lower.length() - 9)
        : lower.endsWith(".md") ? lower.substring(0, lower.length() - 3) : lower;
  }
  private static boolean escaped(String source, int offset) {
    int count = 0; for (int i = offset - 1; i >= 0 && source.charAt(i) == '\\'; i--) count++;
    return count % 2 != 0;
  }
  private static boolean visible(Path root, Path path) {
    if (!path.startsWith(root)) return false;
    for (Path segment : root.relativize(path)) if (segment.toString().startsWith(".")) return false;
    return true;
  }
  private static boolean safe(Path root, Path path) {
    path = path.toAbsolutePath().normalize();
    if (!visible(root, path)) return false;
    Path current = root;
    for (Path part : root.relativize(path)) {
      current = current.resolve(part); if (Files.isSymbolicLink(current)) return false;
    }
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && NoteStore.isMarkdown(path);
  }
  private static void interrupted() {
    if (Thread.currentThread().isInterrupted()) throw new CancellationException();
  }
}
