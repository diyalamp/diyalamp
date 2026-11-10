package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.NoteStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Searches raw Markdown and visible filenames on a cancellable background worker. */
final class FxWorkspaceSearch {
  static final int MAX_RESULTS = 1_000, MAX_MATCHES_PER_NOTE = 100;
  private static final int MAX_NOTES = 5_000, MAX_ENTRIES = 30_000;
  private static final int MAX_TEXT_CHARS = 24 * 1024 * 1024, MAX_EXCERPT = 140;
  private static final int MAX_EXPRESSION = 1_024;
  private static final int WORD_FLAGS = Pattern.UNICODE_CHARACTER_CLASS;
  private static final String WORD = "[\\p{L}\\p{M}\\p{N}\\p{Pc}]";

  enum Scope { BOTH, FILENAMES, CONTENTS }

  record Query(String text, Scope scope, boolean matchCase, boolean wholeWord, boolean regex,
      String include, String exclude) {
    Query(String text, boolean matchCase, boolean wholeWord, boolean regex, String include, String exclude) {
      this(text, Scope.BOTH, matchCase, wholeWord, regex, include, exclude);
    }
  }

  /** Lines and columns are one-based. Offsets and excerpt highlights use UTF-16 indices. */
  record Match(int line, int column, int start, int end, String excerpt, int highlightStart,
      int highlightEnd) {}

  record NoteResult(Path path, boolean filenameMatch, List<Match> matches) {
    NoteResult { matches = List.copyOf(matches); }
  }

  record Result(List<NoteResult> notes, String error, boolean partial) {
    Result { notes = List.copyOf(notes); }
  }

  private FxWorkspaceSearch() {}

  static Result search(NoteStore store, Query query, Map<Path, String> unsaved) throws IOException {
    Budget budget = new Budget();
    budget.check();
    if (query.text().isEmpty()) return new Result(List.of(), null, false);
    if (query.text().length() > MAX_EXPRESSION) return error("The search expression is too long.");

    Pattern term;
    try {
      String expression = query.regex() ? query.text() : Pattern.quote(query.text());
      if (query.wholeWord()) expression = "(?<!" + WORD + ")(?:" + expression + ")(?!" + WORD + ")";
      int flags = WORD_FLAGS | Pattern.MULTILINE | (query.matchCase() ? 0 : Pattern.CASE_INSENSITIVE);
      term = Pattern.compile(expression, flags);
    } catch (PatternSyntaxException invalid) {
      return error("Invalid regular expression. " + invalid.getDescription());
    }

    Pattern include, exclude;
    try { include = globList(query.include()); }
    catch (IllegalArgumentException invalid) { return error("Invalid include glob. " + invalid.getMessage()); }
    try { exclude = globList(query.exclude()); }
    catch (IllegalArgumentException invalid) { return error("Invalid exclude glob. " + invalid.getMessage()); }

    var listing = query.scope() == Scope.CONTENTS ? store.notes(MAX_NOTES, MAX_ENTRIES)
        : store.documents(MAX_NOTES, MAX_ENTRIES);
    List<NoteResult> results = new ArrayList<>();
    boolean partial = listing.partial();
    int hits = 0, remainingChars = MAX_TEXT_CHARS;
    try {
      for (Path path : listing.paths()) {
        budget.check();
        if (hits >= MAX_RESULTS) { partial = true; break; }
        String relative = store.root().relativize(path).toString();
        if ((include != null && !include.matcher(budget.text(relative)).matches())
            || (exclude != null && exclude.matcher(budget.text(relative)).matches())) continue;

        boolean filename = query.scope() != Scope.CONTENTS
            && term.matcher(budget.text(path.getFileName().toString())).find();
        if (filename) hits++;
        List<Match> matches = new ArrayList<>();
        boolean searchText = query.scope() != Scope.FILENAMES && NoteStore.isMarkdown(path);
        if (searchText && hits < MAX_RESULTS && remainingChars > 0) {
          try {
            String markdown = unsaved.get(path);
            if (markdown == null) markdown = store.load(path).text();
            if (markdown.length() > NoteStore.MAX_BYTES || markdown.length() > remainingChars) {
              partial = true;
            } else {
              remainingChars -= markdown.length();
              int limit = Math.min(MAX_MATCHES_PER_NOTE, MAX_RESULTS - hits);
              Matcher matcher = term.matcher(budget.text(markdown));
              Excerpts excerpts = new Excerpts(markdown, budget);
              while (matcher.find()) {
                budget.check();
                if (matches.size() >= limit) { partial = true; break; }
                matches.add(excerpts.at(matcher.start(), matcher.end()));
              }
              hits += matches.size();
            }
          } catch (IOException unreadable) {
            partial = true;
          } catch (SearchLimit reached) {
            if (filename || !matches.isEmpty()) results.add(new NoteResult(path, filename, matches));
            return new Result(results, null, true);
          }
        } else if (searchText) {
          partial = true;
        }
        if (filename || !matches.isEmpty()) results.add(new NoteResult(path, filename, matches));
      }
    } catch (SearchLimit reached) {
      partial = true;
    } catch (StackOverflowError deepPattern) {
      return error("The search pattern is too complex for this text.");
    }
    return new Result(results, null, partial);
  }

  private static Result error(String message) { return new Result(List.of(), message, false); }

  /** Comma-separated workspace globs. Braces can contain alternative paths. */
  private static Pattern globList(String source) {
    if (source.isBlank()) return null;
    if (source.length() > MAX_EXPRESSION) throw new IllegalArgumentException("The list is too long.");
    List<String> parts = new ArrayList<>();
    int start = 0, braces = 0;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (c == '\\') { i++; continue; }
      if (c == '[') { i = rangeEnd(source, i); continue; }
      if (c == '{') braces++;
      if (c == '}') braces--;
      if (c == ',' && braces == 0) { parts.add(source.substring(start, i)); start = i + 1; }
    }
    parts.add(source.substring(start));
    List<String> alternatives = new ArrayList<>();
    for (String part : parts) {
      String glob = part.strip();
      if (!glob.isEmpty()) alternatives.add(globExpression(glob));
    }
    if (alternatives.isEmpty()) return null;
    int flags = Pattern.DOTALL;
    if (System.getProperty("os.name", "").startsWith("Mac")) flags |= Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    try { return Pattern.compile(String.join("|", alternatives), flags); }
    catch (PatternSyntaxException invalid) { throw new IllegalArgumentException(invalid.getDescription()); }
  }

  private static String globExpression(String glob) {
    boolean anchored = glob.startsWith("./");
    if (anchored) glob = glob.substring(2);
    if (glob.startsWith("/") || glob.isEmpty())
      throw new IllegalArgumentException("Use paths relative to the open folder.");
    if (glob.endsWith("/")) glob = glob.substring(0, glob.length() - 1);
    StringBuilder expression = new StringBuilder(anchored ? "(?:" : "(?:.*/)?(?:");
    int braces = 0;
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      switch (c) {
        case '\\' -> {
          if (++i == glob.length()) throw new IllegalArgumentException("Incomplete escape.");
          expression.append(Pattern.quote(glob.substring(i, i + 1)));
        }
        case '*' -> {
          if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
            if (++i + 1 < glob.length() && glob.charAt(i + 1) == '/') {
              expression.append("(?:.*/)?");
              i++;
            } else expression.append(".*");
          } else expression.append("[^/]*");
        }
        case '?' -> expression.append("[^/]");
        case '[' -> {
          int end = rangeEnd(glob, i);
          String range = glob.substring(i + 1, end);
          boolean negative = range.startsWith("!");
          if (negative) range = range.substring(1);
          if (range.isEmpty()) throw new IllegalArgumentException("Empty character range.");
          expression.append("(?:(?!/)[");
          if (negative) expression.append('^');
          expression.append(range.replace("\\", "\\\\").replace("[", "\\[")
              .replace("]", "\\]").replace("&", "\\&").replace("^", "\\^")).append("])");
          i = end;
        }
        case '{' -> { expression.append("(?:"); braces++; }
        case '}' -> {
          if (--braces < 0) throw new IllegalArgumentException("Unexpected closing brace.");
          expression.append(')');
        }
        case ',' -> expression.append(braces > 0 ? '|' : ',');
        default -> expression.append(Pattern.quote(String.valueOf(c)));
      }
    }
    if (braces != 0) throw new IllegalArgumentException("Missing closing brace.");
    return expression.append(")(?:/.*)?").toString();
  }

  private static int rangeEnd(String glob, int start) {
    int first = start + 1;
    if (first < glob.length() && glob.charAt(first) == '!') first++;
    int end = glob.indexOf(']', first + 1);
    if (end < 0) throw new IllegalArgumentException("Incomplete character range.");
    return end;
  }

  private static final class Excerpts {
    private final String text;
    private final Budget budget;
    private int line = 1, lineStart, lineEnd = -1, nextStart;

    Excerpts(String text, Budget budget) { this.text = text; this.budget = budget; }

    Match at(int start, int end) {
      if (lineEnd < 0) readLine();
      while (nextStart <= start) {
        if ((++line & 1023) == 0) budget.check();
        lineStart = nextStart;
        readLine();
      }
      int from = Math.max(lineStart, Math.min(start, lineEnd) - 36);
      int to = Math.min(lineEnd, from + MAX_EXCERPT);
      if (from > lineStart && Character.isLowSurrogate(text.charAt(from))) from--;
      if (to < lineEnd && Character.isLowSurrogate(text.charAt(to))) to--;
      String left = from > lineStart ? "…" : "";
      String right = to < lineEnd ? "…" : "";
      return new Match(line, start - lineStart + 1, start, end,
          left + text.substring(from, to) + right,
          left.length() + Math.min(start, to) - from, left.length() + Math.min(end, to) - from);
    }

    private void readLine() {
      lineEnd = lineStart;
      while (lineEnd < text.length() && text.charAt(lineEnd) != '\n' && text.charAt(lineEnd) != '\r') {
        if ((++lineEnd & 1023) == 0) budget.check();
      }
      nextStart = lineEnd + 1;
      if (lineEnd < text.length() - 1 && text.charAt(lineEnd) == '\r' && text.charAt(nextStart) == '\n')
        nextStart++;
    }
  }

  private static final class SearchLimit extends RuntimeException {}

  /** Checking inside regex backtracking prevents a slow expression from monopolizing the worker. */
  private static final class Budget {
    private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    private int reads;

    void check() {
      if (Thread.currentThread().isInterrupted()) throw new CancellationException();
      if (System.nanoTime() - deadline >= 0) throw new SearchLimit();
    }

    CharSequence text(String source) { return new CheckedText(source); }

    private final class CheckedText implements CharSequence {
      private final String source;

      CheckedText(String source) { this.source = source; }
      @Override public int length() { return source.length(); }
      @Override public char charAt(int index) {
        if ((++reads & 1023) == 0) check();
        return source.charAt(index);
      }
      @Override public CharSequence subSequence(int start, int end) { return text(source.substring(start, end)); }
      @Override public String toString() { return source; }
    }
  }
}
