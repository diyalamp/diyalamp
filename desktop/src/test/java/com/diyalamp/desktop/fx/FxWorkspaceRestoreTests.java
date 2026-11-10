package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Tab;
import atlantafx.base.controls.TabLine;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.scene.control.SplitPane;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

/** Runs real close and quit paths, then starts another workspace with the same preferences. */
public final class FxWorkspaceRestoreTests {
  private static int checks;

  public static void main(String[] arguments) throws Exception {
    Path folder = Files.createTempDirectory("diyalamp-restore-test-").toRealPath();
    Preferences prefs = Preferences.userRoot().node("com/diyalamp/tests/" + UUID.randomUUID());
    FutureTask<Void> test = new FutureTask<>(() -> {
      Platform.setImplicitExit(false);
      restoreAfterCloseAndQuit(folder, prefs);
      return null;
    });
    Platform.startup(test);
    try { test.get(20, TimeUnit.SECONDS); }
    finally {
      Platform.exit();
      prefs.removeNode();
      try (var files = Files.walk(folder)) {
        for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
    System.out.println("Passed " + checks + " JavaFX workspace restore checks.");
  }

  private static void restoreAfterCloseAndQuit(Path folder, Preferences prefs) throws Exception {
    Path notes = Files.createDirectory(folder.resolve("Notes"));
    Path draft = Files.writeString(notes.resolve("Draft.md"), "# Draft\n");
    Path archive = Files.writeString(folder.resolve("Archive.md"), "# Archive\n");
    Path broken = Files.writeString(folder.resolve("Broken.md"), "# Later invalid\n");
    Path text = Files.writeString(folder.resolve("Transcript.txt"), "Reference text\n");
    Path missing = Files.writeString(folder.resolve("Missing.md"), "# Later removed\n");
    Path linked = Files.writeString(folder.resolve("Linked.md"), "# Later replaced\n");
    prefs.put("folder", folder.toString());
    prefs.putBoolean("filesVisible", false);
    prefs.putBoolean("connectionsVisible", false);

    try (Workspace first = new Workspace(prefs)) {
      for (Path path : List.of(draft, archive, broken, linked)) first.open(path, false);
      for (Path path : List.of(draft, text, missing)) first.open(path, true);
      first.open(archive, false);
      first.open(text, true);
      first.split().setDividerPositions(.43);
      first.stage.fireEvent(new WindowEvent(first.stage, WindowEvent.WINDOW_CLOSE_REQUEST));
      check(field(first.app, "active") == null, "Window close must dispose the active view after remembering it");
    }
    Files.write(broken, new byte[] {(byte) 0xFF});
    Files.delete(missing);
    Files.delete(linked);
    Files.createSymbolicLink(linked, archive);

    try (Workspace restored = new Workspace(prefs)) {
      assertRestored(restored);
      check(((List<?>) field(restored.app, "history")).size() == 1,
          "Restoration must not treat every restored tab as a navigation visit");
      Object active = field(restored.app, "active");
      check(field(active, "preview") instanceof FxFilePreview && field(active, "editor") == null,
          "Read-only documents reopen through the same preview component");
      var leftDraft = restored.tabs(false).getTabs().getFirst().getUserData();
      var rightDraft = restored.tabs(true).getTabs().getFirst().getUserData();
      check(field(leftDraft, "session") == field(rightDraft, "session"),
          "The same file on both sides shares its editing session after restart");
    }

    try (Workspace nativeQuit = new Workspace(prefs)) {
      assertRestored(nativeQuit);
      ((BooleanProperty) field(nativeQuit.app, "privacyMode")).set(true);
    }
    try (Workspace afterPrivacy = new Workspace(prefs)) {
      check(afterPrivacy.tabs(false).getTabs().isEmpty() && afterPrivacy.tabs(true).getTabs().isEmpty()
          && afterPrivacy.split().getItems().size() == 1,
          "Quitting while Privacy Mode is on starts the next launch without exposing prior tabs");
    }
  }

  private static void assertRestored(Workspace restored) throws Exception {
    check(restored.titles(false).equals(List.of("Draft.md", "Archive.md"))
        && restored.titles(true).equals(List.of("Draft.md", "Transcript.txt")),
        "Restore keeps pane and tab order while skipping missing, invalid and symlinked files");
    check(restored.tabs(false).getSelectionModel().getSelectedItem().getText().equals("Archive.md")
        && restored.tabs(true).getSelectionModel().getSelectedItem().getText().equals("Transcript.txt")
        && restored.stage.getTitle().startsWith("Transcript.txt"),
        "Restore remembers both selections and the active pane");
    check(restored.split().getItems().size() == 2
        && Math.abs(restored.split().getDividers().getFirst().getPosition() - .43) <= .02,
        "Restore remembers the split width");
  }

  private static final class Workspace implements AutoCloseable {
    final FxDiyalamp app;
    final Stage stage = new Stage();

    Workspace(Preferences prefs) {
      app = new FxDiyalamp(prefs);
      app.start(stage);
    }

    void open(Path path, boolean beside) throws Exception {
      Method method = FxDiyalamp.class.getDeclaredMethod("open", Path.class, boolean.class);
      method.setAccessible(true);
      method.invoke(app, path, beside);
    }

    TabLine tabs(boolean beside) throws Exception {
      return (TabLine) field(field(app, beside ? "secondary" : "primary"), "tabs");
    }

    List<String> titles(boolean beside) throws Exception { return tabs(beside).getTabs().stream().map(Tab::getText).toList(); }
    SplitPane split() throws Exception { return (SplitPane) field(app, "split"); }
    @Override public void close() { app.stop(); stage.hide(); }
  }

  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }
}
