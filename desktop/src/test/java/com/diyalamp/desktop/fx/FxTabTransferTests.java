package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Tab;
import atlantafx.base.controls.TabLine;
import com.diyalamp.desktop.NoteStore;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.scene.control.SplitPane;
import javafx.scene.input.TransferMode;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Transfers real editor views and local files without recreating a writer or losing pending edits. */
public final class FxTabTransferTests {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] arguments) throws Exception {
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> failure.compareAndSet(null, error));
      Fixture fixture = null;
      try {
        fixture = new Fixture();
        transferModes(); moveToSplit(fixture); coalesceSameNote(fixture); moveToFolder(fixture);
        singlePaneTransfer(fixture);
        Fixture current = fixture;
        PauseTransition settle = new PauseTransition(Duration.millis(220));
        settle.setOnFinished(event -> {
          try {
            current.stage.getScene().getRoot().applyCss(); current.stage.getScene().getRoot().layout();
            check(tabs(current.primary).getTabs().size() == 1 && tabs(current.primary).getSelectionModel().getSelectedItem() != null,
                "The moved tab must remain selected after the old skin's removal animation completes");
          } catch (Throwable error) { failure.compareAndSet(null, error); }
          finally { close(current, failure); finished.countDown(); }
        });
        settle.play();
      } catch (Throwable error) {
        failure.compareAndSet(null, error); close(fixture, failure); finished.countDown();
      }
    });
    if (!finished.await(30, TimeUnit.SECONDS)) throw new AssertionError("Transfer tests timed out");
    Platform.exit();
    if (failure.get() != null) throw new AssertionError("Tab transfer regression", failure.get());
    System.out.println("Passed " + checks + " JavaFX tab transfer checks.");
  }

  private static void close(Fixture fixture, AtomicReference<Throwable> failure) {
    if (fixture == null) return;
    try { fixture.close(); } catch (Throwable error) { failure.compareAndSet(null, error); }
  }

  private static void transferModes() {
    check(FxDiyalamp.TAB_DRAG_MODES.contains(FxDiyalamp.paneDropMode(true)),
        "A native tab source and a pane target must negotiate MOVE; COPY-only targets reject tab drags");
    check(FxDiyalamp.NOTE_DRAG_MODES.contains(TransferMode.MOVE),
        "Explorer notes must offer MOVE so a folder can accept an on-disk move");
    check(FxDiyalamp.NOTE_DRAG_MODES.contains(FxDiyalamp.paneDropMode(false)),
        "Explorer notes must also offer COPY so opening a view cannot move its file");
    check(!FxDiyalamp.TAB_DRAG_MODES.contains(TransferMode.COPY),
        "Moving a tab cannot advertise a duplicate-view operation");
  }

  private static void moveToSplit(Fixture fixture) throws Exception {
    FxWritingPane editor = fixture.editor();
    editor.area().appendText(" unsaved before transfer");
    editor.area().selectRange(2, 5);
    Object view = fixture.active();
    Object session = field(view, "session");
    Tab tab = (Tab) field(view, "tab");
    check((boolean) call(fixture.app, "moveView", new Class<?>[] {view.getClass(), fixture.secondary.getClass()}, view, fixture.secondary),
        "A tab must move into the secondary pane");
    check(field(view, "editor") == editor && !((boolean) field(view, "disposed")),
        "Moving a tab must retain its editor instance and undo history");
    check(editor.area().getAnchor() == 2 && editor.area().getCaretPosition() == 5,
        "A split transfer must retain the selected text");
    check(tabs(fixture.primary).getTabs().isEmpty()
        && tabs(fixture.secondary).getTabs().contains((Tab) field(view, "tab"))
        && ((Tab) field(view, "tab")).getId().equals(tab.getId()),
        "A transferred tab must leave its source host and enter only its destination host");
    check(field(session, "pendingView") == null && ((String) field(session, "text")).contains("unsaved before transfer"),
        "The pending editor revision must be synchronized before a pane transfer");
    check(editor.canUndo(), "A transferred editor must still have its typing undo action");
    editor.undo();
    check(!editor.recoveryText().contains("unsaved before transfer"), "Undo must still edit the transferred note");
    editor.redo();
    check(editor.recoveryText().contains("unsaved before transfer"), "Redo must retain the same editor history");
    SplitPane split = (SplitPane) field(fixture.app, "split");
    check(split.getItems().size() == 2, "Moving into a split must reveal the secondary pane");
    check(tabs(fixture.primary).getTabDragPolicy() == Tab.DragPolicy.REORDER
        && tabs(fixture.secondary).getTabDragPolicy() == Tab.DragPolicy.REORDER,
        "Cross-pane transfers must preserve AtlantaFX's existing within-host reorder policy");
  }

  private static void coalesceSameNote(Fixture fixture) throws Exception {
    Object original = fixture.active(); FxWritingPane editor = fixture.editor();
    call(fixture.app, "open", new Class<?>[] {Path.class, boolean.class}, fixture.path, false);
    Object duplicate = fixture.active();
    check(duplicate != original, "Opening the same note in the other pane should initially create a reference view");
    call(fixture.app, "moveView", new Class<?>[] {original.getClass(), fixture.primary.getClass()}, original, fixture.primary);
    check(fixture.active() == original && fixture.editor() == editor && !((boolean) field(original, "disposed")),
        "Dropping onto the note's other host must keep the dragged editor, rather than disposing its history");
    check((boolean) field(duplicate, "disposed") && tabs(fixture.primary).getTabs().size() == 1,
        "Only the redundant destination view should close");
    check(((SplitPane) field(fixture.app, "split")).getItems().size() == 1,
        "An emptied secondary pane must collapse after moving its last tab");
  }

  private static void singlePaneTransfer(Fixture fixture) throws Exception {
    Object view = fixture.active(); FxWritingPane editor = fixture.editor();
    call(fixture.app, "moveView", new Class<?>[] {view.getClass(), fixture.secondary.getClass()}, view, fixture.secondary);
    Tab previous = (Tab) field(view, "tab"); editor.area().selectRange(1, 4);
    call(fixture.app, "singlePane", new Class<?>[0]);
    Tab current = (Tab) field(view, "tab");
    check(current != previous && current.getId().equals(previous.getId()),
        "Single pane must replace the cross-host tab model while retaining its identity");
    check(fixture.active() == view && fixture.editor() == editor && editor.canUndo(),
        "Single pane must retain the same editor and its undo history");
    check(editor.area().getAnchor() == 1 && editor.area().getCaretPosition() == 4,
        "Single pane must leave the text selection unchanged");
    check(((SplitPane) field(fixture.app, "split")).getItems().size() == 1,
        "Single pane must collapse the secondary viewport");
  }

  @SuppressWarnings("unchecked") private static void moveToFolder(Fixture fixture) throws Exception {
    FxWritingPane editor = fixture.editor();
    Object view = fixture.active(); Object session = field(view, "session");
    editor.area().appendText(" saved while moving folders");
    Path folder = fixture.store().createFolder(fixture.directory, "Ideas");
    check((boolean) call(fixture.app, "moveNote", new Class<?>[] {Path.class, Path.class}, fixture.path, folder),
        "A tab or tree row must move its local file into a folder");
    Path moved = folder.resolve(fixture.path.getFileName());
    check(!Files.exists(fixture.path) && Files.isRegularFile(moved) && moved.toString().endsWith(".markdown"),
        "Moving a note must retain its real Markdown extension and leave no stale source file");
    check(Files.readString(moved).contains("saved while moving folders"),
        "Folder moves must save the latest pending input before moving bytes");
    check(fixture.active() == view && fixture.editor() == editor && field(session, "path").equals(moved),
        "A file move must update the existing open session without changing the active editor");
    Map<Path, ?> sessions = (Map<Path, ?>) field(fixture.app, "sessions");
    check(!sessions.containsKey(fixture.path) && sessions.get(moved) == session,
        "The session map must follow the new path without retaining a stale key");
    check(field(session, "fingerprint").equals(fixture.store().load(moved).fingerprint()),
        "The moved file must preserve the optimistic-save fingerprint");
    editor.area().appendText(" after move");
    check((boolean) call(fixture.app, "saveAll", new Class<?>[] {boolean.class}, false)
        && Files.readString(moved).contains("after move"),
        "The first save after a move must target the new file and pass its conflict check");
    check(tabs(fixture.primary).getTabs().size() == 1,
        "Selecting a moved file in the explorer must not create another editor view");
  }

  private static final class Fixture implements AutoCloseable {
    final Path directory = Files.createTempDirectory("diyalamp-transfer-test-").toRealPath();
    final Path path = directory.resolve("Fictional.markdown");
    final Preferences preferences = Preferences.userRoot().node("com/diyalamp/tests/" + UUID.randomUUID());
    final FxDiyalamp app = new FxDiyalamp(preferences);
    final Stage stage = new Stage();
    final Object primary, secondary;
    Fixture() throws Exception {
      Files.writeString(path, "Original text\n");
      app.start(stage);
      call(app, "setFolder", new Class<?>[] {Path.class}, directory);
      call(app, "open", new Class<?>[] {Path.class, boolean.class}, path, false);
      primary = field(app, "primary"); secondary = field(app, "secondary");
      stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
    }
    Object active() throws Exception { return field(app, "active"); }
    FxWritingPane editor() throws Exception { return (FxWritingPane) field(active(), "editor"); }
    NoteStore store() throws Exception { return (NoteStore) field(app, "store"); }
    @Override public void close() throws Exception {
      app.stop(); stage.hide(); preferences.removeNode();
      try (var paths = Files.walk(directory)) {
        for (Path item : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(item);
      }
    }
  }

  private static TabLine tabs(Object host) throws Exception { return (TabLine) field(host, "tabs"); }
  private static Object field(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
  }
  private static Object call(Object object, String name, Class<?>[] parameters, Object... arguments) throws Exception {
    Method method = object.getClass().getDeclaredMethod(name, parameters); method.setAccessible(true);
    return method.invoke(object, arguments);
  }
}
