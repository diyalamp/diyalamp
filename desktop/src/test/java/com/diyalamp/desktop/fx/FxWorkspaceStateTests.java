package com.diyalamp.desktop.fx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;

/** Workspace layout persistence uses isolated preferences and synthetic paths. */
public final class FxWorkspaceStateTests {
  private static final Path ROOT = Path.of("/workspaces/Study Desk");
  private static final FxWorkspaceState.Pane EMPTY = new FxWorkspaceState.Pane(List.of(), null);
  private static int checks;

  public static void main(String[] arguments) {
    roundTripAndClear(); malformedState(); boundedTabs();
    System.out.println("Passed " + checks + " workspace state codec checks.");
  }

  private static void roundTripAndClear() {
    MemoryPreferences preferences = new MemoryPreferences();
    Path first = Path.of("Systems", "  Café, 東京\noutline : reference.md"), second = Path.of("Second.md");
    var left = new FxWorkspaceState.Pane(List.of(first, second), second);
    var right = new FxWorkspaceState.Pane(List.of(second, first), first);
    var saved = new FxWorkspaceState(ROOT, left, right, true, .73);
    check(FxWorkspaceState.read(preferences, ROOT) == null, "Absent preferences must not restore tabs");
    saved.write(preferences);
    check(preferences.values.size() == 1 && preferences.flushes == 1, "One preference value must be saved and flushed");
    check(saved.equals(FxWorkspaceState.read(preferences, ROOT)),
        "Ordered tabs, independent selections, duplicate views, special filenames and divider must round trip");
    check(FxWorkspaceState.read(preferences, ROOT.resolveSibling("Study Desk Copy")) == null,
        "A snapshot must belong to the exact canonical workspace root");

    var empty = new FxWorkspaceState(ROOT, EMPTY, EMPTY, false, Double.NaN);
    empty.write(preferences);
    check(empty.equals(FxWorkspaceState.read(preferences, ROOT)) && empty.divider() == .5,
        "An empty workspace replaces earlier tabs and invalid divider geometry defaults to the center");
    FxWorkspaceState.clear(preferences);
    check(FxWorkspaceState.read(preferences, ROOT) == null && preferences.flushes == 3,
        "Clearing the snapshot must remove the only preference value and flush the change");
  }

  private static void malformedState() {
    MemoryPreferences preferences = new MemoryPreferences();
    Path document = Path.of("Notebook.md");
    var pane = new FxWorkspaceState.Pane(List.of(document), document);
    new FxWorkspaceState(ROOT, pane, EMPTY, false, .5).write(preferences);
    String key = preferences.values.keySet().iterator().next();
    String correct = preferences.get(key, "");
    String documentToken = token(document.toString().getBytes(StandardCharsets.UTF_8));
    for (String damaged : List.of("garbage", "2" + correct.substring(1),
        correct.replace(documentToken, "$$"), correct.replace(documentToken, token(new byte[] {(byte) 0xff})),
        correct.replace(documentToken, token("../outside.md".getBytes(StandardCharsets.UTF_8))),
        correct.replace(documentToken, token("/outside.md".getBytes(StandardCharsets.UTF_8))),
        correct.replace("\n0:", "\n99:"), correct.replace("\n0.5\n", "\ninvalid\n"))) {
      preferences.put(key, damaged);
      check(FxWorkspaceState.read(preferences, ROOT) == null, "Unsafe or malformed state must be ignored");
    }
    preferences.put(key, correct.replace("\n0.5\n", "\nInfinity\n"));
    var restored = FxWorkspaceState.read(preferences, ROOT);
    check(restored != null && restored.divider() == .5, "A non-finite divider cannot reach the layout");
  }

  private static void boundedTabs() {
    MemoryPreferences preferences = new MemoryPreferences();
    List<Path> simple = documents("Short", 32);
    var many = new FxWorkspaceState.Pane(simple, simple.getLast());
    new FxWorkspaceState(ROOT, many, EMPTY, false, .5).write(preferences);
    var primary = FxWorkspaceState.read(preferences, ROOT).primary();
    check(primary.tabs().size() == 24 && primary.tabs().getLast().equals(many.selected())
        && primary.selected().equals(many.selected()), "The per-pane cap must keep the selected tab");

    List<Path> left = documents("Left-" + "a".repeat(240), 26), right = documents("Right-" + "b".repeat(240), 26);
    var leftPane = new FxWorkspaceState.Pane(left, left.getLast());
    var rightPane = new FxWorkspaceState.Pane(right, right.getLast());
    new FxWorkspaceState(ROOT, leftPane, rightPane, true, .5).write(preferences);
    var trimmed = FxWorkspaceState.read(preferences, ROOT);
    String value = preferences.values.values().iterator().next();
    check(value.length() <= Preferences.MAX_VALUE_LENGTH
        && trimmed.primary().tabs().size() + trimmed.secondary().tabs().size() < 48,
        "The preference byte budget must trim oversized snapshots");
    check(trimmed.primary().selected().equals(leftPane.selected()) && trimmed.secondary().selected().equals(rightPane.selected()),
        "Both selections survive trimming when they fit");
    check(left.stream().filter(trimmed.primary().tabs()::contains).toList().equals(trimmed.primary().tabs())
        && right.stream().filter(trimmed.secondary().tabs()::contains).toList().equals(trimmed.secondary().tabs()),
        "Trimming must preserve tab order in each pane");
  }

  private static List<Path> documents(String prefix, int count) {
    List<Path> paths = new ArrayList<>();
    for (int index = 0; index < count; index++) paths.add(Path.of(prefix + index + ".md"));
    return paths;
  }

  private static String token(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }

  private static final class MemoryPreferences extends AbstractPreferences {
    final Map<String, String> values = new HashMap<>();
    int flushes;

    MemoryPreferences() { super(null, ""); }
    @Override protected void putSpi(String key, String value) { values.put(key, value); }
    @Override protected String getSpi(String key) { return values.get(key); }
    @Override protected void removeSpi(String key) { values.remove(key); }
    @Override protected void removeNodeSpi() { values.clear(); }
    @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
    @Override protected String[] childrenNamesSpi() { return new String[0]; }
    @Override protected AbstractPreferences childSpi(String name) { throw new UnsupportedOperationException(); }
    @Override protected void syncSpi() {}
    @Override protected void flushSpi() { flushes++; }
  }
}
