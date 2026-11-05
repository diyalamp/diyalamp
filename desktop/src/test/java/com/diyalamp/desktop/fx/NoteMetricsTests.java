package com.diyalamp.desktop.fx;

/** Counts come from current text and Markdown, never a stale on-disk file. */
public final class NoteMetricsTests {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] args) {
    NoteMetrics empty = NoteMetrics.count("", "");
    check(empty.characters() == 0 && empty.words() == 0 && empty.lines() == 1 && empty.utf8Bytes() == 0,
        "An empty editor has no characters/words/bytes and one editable line");
    NoteMetrics note = NoteMetrics.count("A heading\nSome bold words.", "# A heading\nSome **bold** words.\n");
    check(note.characters() == 26 && note.words() == 5 && note.lines() == 2,
        "Reading counts exclude Markdown formatting characters");
    check(note.utf8Bytes() == 33, "Size includes the actual current Markdown formatting and newline bytes");
    NoteMetrics unicode = NoteMetrics.count("café 👩🏽‍💻\nनमस्ते", "café 👩🏽‍💻\nनमस्ते");
    check(unicode.characters() == 16, "Character counts must count Unicode code points rather than UTF-16 units");
    check(unicode.words() == 2 && unicode.lines() == 2 && unicode.utf8Bytes() == 40,
        "Unicode words, emoji bytes and logical lines remain distinct measurements");
    check(NoteMetrics.count("one\r\ntwo\rthree\nfour", "").lines() == 4,
        "CRLF counts as one line break, alongside CR and LF");
    check(NoteMetrics.count("don't stop keep-on", "").words() == 3, "Internal apostrophes and hyphens remain in their words");
    check(NoteMetrics.count("one", "one").fileSize().equals("3 B"), "Small sizes display exact bytes");
    check(NoteMetrics.count("", "x".repeat(1250)).fileSize().equals("1.3 kB"), "Larger size labels use explicit decimal units");
    check(NoteMetrics.count("", "x".repeat(2_000_000)).fileSize().equals("2.0 MB"), "Large note sizes remain compact");
    System.out.println("Passed " + checks + " note metric checks.");
  }
}
