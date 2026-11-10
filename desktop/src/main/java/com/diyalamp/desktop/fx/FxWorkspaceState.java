package com.diyalamp.desktop.fx;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/** An ordered view of one workspace, saved without note content in a single preferences value. */
record FxWorkspaceState(Path root, Pane primary, Pane secondary, boolean activeBeside, double divider) {
  private static final String KEY = "workspaceState";
  private static final String VERSION = "1";
  private static final int MAX_TABS = 24;

  FxWorkspaceState {
    if (!(divider > 0 && divider < 1)) divider = .5;
  }

  /** Tab and selection paths are relative to the canonical workspace root. */
  record Pane(List<Path> tabs, Path selected) {
    Pane { tabs = List.copyOf(tabs); }
  }

  static FxWorkspaceState read(Preferences preferences, Path root) {
    try {
      String value = preferences.get(KEY, "");
      if (value.length() > Preferences.MAX_VALUE_LENGTH) return null;
      String[] lines = value.split("\n", -1);
      if (lines.length != 6 || !VERSION.equals(lines[0]) || !encode(root).equals(lines[1])
          || (!lines[2].equals("0") && !lines[2].equals("1"))) return null;
      return new FxWorkspaceState(root, decodePane(lines[4]), decodePane(lines[5]), lines[2].equals("1"),
          Double.parseDouble(lines[3]));
    } catch (IllegalArgumentException | IllegalStateException | SecurityException | CharacterCodingException error) {
      return null;
    }
  }

  void write(Preferences preferences) {
    ArrayList<Path> left = boundedTabs(primary), right = boundedTabs(secondary);
    ArrayList<Path> active = activeBeside ? right : left, inactive = activeBeside ? left : right;
    Path activeSelection = activeBeside ? secondary.selected : primary.selected;
    Path inactiveSelection = activeBeside ? primary.selected : secondary.selected;
    String value = encodeState(left, right);
    while (value.length() > Preferences.MAX_VALUE_LENGTH) {
      if (!removeUnselected(inactive, inactiveSelection) && !removeUnselected(active, activeSelection)) {
        // Keep the active selection first if even the selected tabs exceed the preference budget.
        if (!inactive.isEmpty()) inactive.clear();
        else if (!active.isEmpty()) active.clear();
        else { clear(preferences); return; }
      }
      value = encodeState(left, right);
    }
    try {
      preferences.put(KEY, value);
      preferences.flush();
    } catch (IllegalStateException | SecurityException | BackingStoreException error) { reportWriteFailure(); }
  }

  static void clear(Preferences preferences) {
    try {
      preferences.remove(KEY);
      preferences.flush();
    } catch (IllegalStateException | SecurityException | BackingStoreException error) { reportWriteFailure(); }
  }

  private String encodeState(List<Path> left, List<Path> right) {
    return String.join("\n", VERSION, encode(root), activeBeside ? "1" : "0", Double.toString(divider),
        encodePane(left, primary.selected), encodePane(right, secondary.selected));
  }

  private static ArrayList<Path> boundedTabs(Pane pane) {
    List<Path> valid = pane.tabs.stream().filter(FxWorkspaceState::relativeFile).distinct().toList();
    ArrayList<Path> retained = new ArrayList<>(valid.subList(0, Math.min(MAX_TABS, valid.size())));
    if (valid.size() > MAX_TABS && pane.selected != null && !retained.contains(pane.selected) && valid.contains(pane.selected)) {
      retained.set(MAX_TABS - 1, pane.selected);
    }
    return retained;
  }

  private static boolean removeUnselected(ArrayList<Path> tabs, Path selected) {
    for (int index = tabs.size() - 1; index >= 0; index--) {
      if (!tabs.get(index).equals(selected)) {
        tabs.remove(index);
        return true;
      }
    }
    return false;
  }

  private static String encodePane(List<Path> paths, Path selected) {
    return paths.indexOf(selected) + ":" + String.join(",", paths.stream().map(FxWorkspaceState::encode).toList());
  }

  private static Pane decodePane(String line) throws CharacterCodingException {
    String[] fields = line.split(":", -1);
    if (fields.length != 2) throw new IllegalArgumentException("Invalid pane state");
    int selected = Integer.parseInt(fields[0]);
    String[] encoded = fields[1].isEmpty() ? new String[0] : fields[1].split(",", -1);
    if (encoded.length > MAX_TABS || selected < -1 || selected >= encoded.length)
      throw new IllegalArgumentException("Invalid tab list");
    List<Path> tabs = new ArrayList<>(encoded.length);
    for (String value : encoded) {
      byte[] bytes = Base64.getUrlDecoder().decode(value);
      Path path = Path.of(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
      if (!relativeFile(path) || tabs.contains(path)) throw new IllegalArgumentException("Invalid relative path");
      tabs.add(path);
    }
    return new Pane(tabs, selected < 0 ? null : tabs.get(selected));
  }

  private static boolean relativeFile(Path path) {
    return !path.isAbsolute() && !path.toString().isEmpty() && !path.startsWith("..") && path.equals(path.normalize());
  }

  private static String encode(Path path) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(path.toString().getBytes(StandardCharsets.UTF_8));
  }

  private static void reportWriteFailure() {
    System.err.println("diyalamp could not save the workspace layout.");
  }
}
