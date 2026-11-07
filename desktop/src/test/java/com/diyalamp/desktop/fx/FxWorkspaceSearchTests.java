package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.NoteStore;
import com.diyalamp.desktop.fx.FxWorkspaceSearch.Query;
import com.diyalamp.desktop.fx.FxWorkspaceSearch.Scope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FxWorkspaceSearchTests {
  public static void main(String[] args) throws Exception {
    Path root = Files.createTempDirectory("diyalamp-search-tests-").toRealPath();
    try {
      verifyTextAndOffsets(new NoteStore(root.resolve("text")));
      verifyGlobs(new NoteStore(root.resolve("globs")));
      verifySnapshotsAndBoundaries(new NoteStore(root.resolve("snapshots")));
      verifyLimits(new NoteStore(root.resolve("limits")));
      verifyRegexCancellation(new NoteStore(root.resolve("cancellation")));
    } finally {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
    System.out.println("Passed workspace search checks.");
  }

  private static void verifyRegexCancellation(NoteStore store) throws Exception {
    store.create("long", "");
    var reading = new CountDownLatch(1);
    var cancelled = new AtomicBoolean();
    String text = "a".repeat(3_000);
    Map<Path, String> snapshot = new AbstractMap<>() {
      @Override public Set<Entry<Path, String>> entrySet() { return Set.of(); }
      @Override public String get(Object path) { reading.countDown(); return text; }
    };
    Thread worker = Thread.ofVirtual().start(() -> {
      try {
        FxWorkspaceSearch.search(store, new Query("a*a*a*a*a*a*a*b", Scope.CONTENTS, true, false, true, "", ""), snapshot);
      } catch (CancellationException expected) {
        cancelled.set(true);
      } catch (java.io.IOException error) {
        throw new AssertionError(error);
      }
    });
    try {
      check(reading.await(5, TimeUnit.SECONDS), "The search reaches the note before it is cancelled");
      worker.interrupt();
      worker.join(5_000);
      check(!worker.isAlive() && cancelled.get(), "Cancellation interrupts regex backtracking");
    } finally {
      worker.interrupt();
    }
  }

  private static void verifyTextAndOffsets(NoteStore store) throws Exception {
    String markdown = "catch\r\nA CAT and cat.\rC++ and C++x\ncafé\n";
    Path note = store.create("Cat and résumé", markdown);
    var result = search(store, new Query("cat", false, true, false, "", ""));
    var group = result.notes().getFirst();
    check(!result.partial() && group.path().equals(note) && group.filenameMatch(), "Grouped filename and text hits");
    check(group.matches().size() == 2, "Whole words exclude catch");
    var first = group.matches().getFirst();
    check(first.line() == 2 && first.column() == 3 && first.start() == markdown.indexOf("CAT")
        && first.end() - first.start() == 3, "CRLF and raw Markdown offsets");
    check(first.excerpt().substring(first.highlightStart(), first.highlightEnd()).equals("CAT"), "Exact excerpt highlight");
    check(search(store, new Query("CAT", Scope.CONTENTS, true, true, false, "", ""))
        .notes().getFirst().matches().size() == 1, "Case-sensitive content search");
    check(search(store, new Query("C++", Scope.CONTENTS, true, true, false, "", ""))
        .notes().getFirst().matches().size() == 1, "Literal punctuation in a whole-word search");
    var spanning = search(store, new Query("cat\\.\\rC\\+\\+", Scope.CONTENTS, true, false, true, "", ""))
        .notes().getFirst().matches().getFirst();
    check(spanning.line() == 2 && spanning.column() == 11 && spanning.end() - spanning.start() == 8
        && spanning.excerpt().substring(spanning.highlightStart(), spanning.highlightEnd()).equals("cat."),
        "Multiline regex preserves offsets and previews its first line");
    var filenames = search(store, new Query("RÉSUMÉ", Scope.FILENAMES, false, false, false, "", ""));
    check(filenames.notes().size() == 1 && filenames.notes().getFirst().matches().isEmpty(), "Unicode filename search");
    check(search(store, new Query("[", false, false, true, "", "")).error().startsWith("Invalid regular expression"),
        "Invalid regex is reported as input error");
  }

  private static void verifyGlobs(NoteStore store) throws Exception {
    Path docs = Files.createDirectories(store.root().resolve("docs/inner"));
    Path apple = store.create(docs.getParent(), "apple", "marker");
    Path berry = store.create(docs, "berry", "marker");
    Path hiddenByGlob = store.create(Files.createDirectories(docs.resolve("archive")), "date", "marker");
    Path otherDocs = store.create(Files.createDirectories(store.root().resolve("other/docs")), "cherry", "marker");
    Path readme = Files.writeString(store.root().resolve("readme.markdown"), "marker");
    Path brackets = store.create(store.root(), "[x]", "marker");
    var result = search(store, new Query("marker", false, false, false,
        "./{docs/**/[a-b]*.md,**/*.markdown}, [[]x[]].md", "archive, ./docs/**/berry.md"));
    check(result.notes().stream().map(FxWorkspaceSearch.NoteResult::path).toList()
        .equals(List.of(brackets, apple, readme)), "Brace alternatives, root globs, zero segments, ranges, and exclusions");
    var nested = search(store, new Query("marker", false, false, false, "docs", ""));
    check(nested.notes().stream().map(FxWorkspaceSearch.NoteResult::path).toList()
        .equals(List.of(apple, hiddenByGlob, berry, otherDocs)), "Unanchored directory pattern applies to descendants");
    check(search(store, new Query("marker", false, false, false, "[abc", "")).error().startsWith("Invalid include glob"),
        "Invalid include glob reports which field needs attention");
    check(search(store, new Query("marker", false, false, false, "", "{docs,other")).error().startsWith("Invalid exclude glob"),
        "Invalid exclude glob reports which field needs attention");
  }

  private static void verifySnapshotsAndBoundaries(NoteStore store) throws Exception {
    Path draft = store.create("draft", "saved words");
    Path hidden = Files.writeString(store.root().resolve(".private.md"), "marker");
    Path outside = Files.writeString(store.root().resolveSibling("outside.md"), "marker");
    Path alias = Files.createSymbolicLink(store.root().resolve("alias.md"), outside);
    Path nonUtf8 = Files.write(store.root().resolve("broken.md"), new byte[] {(byte) 0xff});
    var result = FxWorkspaceSearch.search(store, new Query("marker", false, false, false, "", ""),
        Map.of(draft, "new marker", hidden, "marker", outside, "marker", alias, "marker"));
    check(result.partial() && result.notes().size() == 1 && result.notes().getFirst().path().equals(draft),
        "Snapshots override listed notes only, and unreadable notes do not stop the search");
    check(Files.readString(draft).equals("saved words") && Files.size(nonUtf8) == 1, "Search never writes files");
    var saved = FxWorkspaceSearch.search(store, new Query("saved", false, false, false, "", ""), Map.of(draft, ""));
    check(saved.notes().isEmpty(), "An empty snapshot also overrides saved text");
  }

  private static void verifyLimits(NoteStore store) throws Exception {
    String repeated = "a ".repeat(120);
    for (int i = 0; i < 12; i++) store.create("note " + i, repeated);
    var result = search(store, new Query("a", false, false, false, "", ""));
    check(result.partial() && result.notes().stream().mapToInt(n -> n.matches().size()).sum()
        == FxWorkspaceSearch.MAX_RESULTS, "Search caps total results and reports truncation");
    check(result.notes().getFirst().matches().size() == FxWorkspaceSearch.MAX_MATCHES_PER_NOTE,
        "A single note cannot crowd out other notes");
    check(store.notes(1, 100).partial() && store.notes(1, 100).paths().size() == 1, "Bounded listing reports omitted notes");
    check(store.notes(100, 1).partial() && store.notes(100, 1).paths().isEmpty(), "Discovery caps filesystem entries too");
    String longLine = "🙂".repeat(250) + "target" + "🙂".repeat(250);
    store.create("long", longLine);
    var excerpt = search(store, new Query("target", false, false, false, "", "")).notes().getFirst().matches().getFirst();
    check(excerpt.excerpt().startsWith("…") && excerpt.excerpt().endsWith("…") && excerpt.excerpt().length() <= 143
        && excerpt.excerpt().substring(excerpt.highlightStart(), excerpt.highlightEnd()).equals("target"),
        "Bounded excerpt around a match on a long line");
    Thread.currentThread().interrupt();
    try {
      search(store, new Query("missing", false, false, false, "", ""));
      throw new AssertionError("An interrupted search should cancel");
    } catch (CancellationException expected) {
      check(Thread.currentThread().isInterrupted(), "Cancellation leaves interruption set");
    } finally {
      Thread.interrupted();
    }
  }

  private static FxWorkspaceSearch.Result search(NoteStore store, Query query) throws Exception {
    return FxWorkspaceSearch.search(store, query, Map.of());
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
