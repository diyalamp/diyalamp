package com.diyalamp.desktop.fx;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** Reading counts use rendered text; file size uses the exact current Markdown bytes. */
record NoteMetrics(int characters, long words, int lines, int utf8Bytes) {
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*(?:['’_-][\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*)*");

  static NoteMetrics count(String text, String markdown) {
    int lines = 1;
    for (int i = 0; i < text.length(); i++) {
      char value = text.charAt(i);
      if (value == '\r') {
        lines++;
        if (i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
      } else if (value == '\n') lines++;
    }
    return new NoteMetrics(text.codePointCount(0, text.length()), WORD.matcher(text).results().count(),
        lines, markdown.getBytes(StandardCharsets.UTF_8).length);
  }

  String fileSize() {
    if (utf8Bytes < 1000) return utf8Bytes + " B";
    if (utf8Bytes < 1_000_000) return String.format(Locale.ROOT, "%.1f kB", utf8Bytes / 1000.0);
    return String.format(Locale.ROOT, "%.1f MB", utf8Bytes / 1_000_000.0);
  }
}
