package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Tab;
import atlantafx.base.controls.TabLine;
import atlantafx.base.controls.TabSkin;
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
import javafx.animation.Timeline;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
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
        transferModes(); reorderHeaders(fixture); narrowDropGuide(); moveToSplit(fixture); coalesceSameNote(fixture); moveToFolder(fixture);
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

  private static void reorderHeaders(Fixture fixture) throws Exception {
    Object original = fixture.active(); FxWritingPane editor = fixture.editor();
    Tab tab = (Tab) field(original, "tab");
    FxDocumentTabs tabs = (FxDocumentTabs) tabs(fixture.primary);
    tabs.setAnimated(false);
    Path second = fixture.store().create("Second", "Second note\n");
    Path third = fixture.store().create("Third", "Third note\n");
    call(fixture.app, "open", new Class<?>[] {Path.class, boolean.class}, second, false);
    Tab secondTab = (Tab) field(fixture.active(), "tab");
    call(fixture.app, "open", new Class<?>[] {Path.class, boolean.class}, third, false);
    Tab thirdTab = (Tab) field(fixture.active(), "tab");
    tabs.setAnimated(true);
    var root = fixture.stage.getScene().getRoot();
    root.resize(1450, root.getLayoutBounds().getHeight());
    root.applyCss(); root.layout();
    var firstHeader = header(tabs, tab);
    var thirdHeader = header(tabs, thirdTab);
    double firstLeft = firstHeader.localToScene(0, 0).getX();
    double thirdLeft = thirdHeader.localToScene(0, 0).getX();
    check(tabs.insertionAt(thirdLeft + 1) == 2 && tabs.insertionAt(thirdLeft + thirdHeader.getWidth()) == 3,
        "The visible header midpoint must separate the before and after drop positions");
    tabs.showInsertion(2);
    Region guide = (Region) tabs.lookup(".tab-insertion-guide");
    check(guide.isVisible() && Math.abs(guide.localToScene(1, 0).getX() - thirdLeft) <= 1
        && tabs.getTabs().getLast() == thirdTab,
        "A live insertion guide must precede the target without mutating the order during drag");
    tabs.showInsertion(3);
    var strip = thirdHeader.getParent();
    double stripRight = strip.localToScene(strip.getClip().getBoundsInParent().getMaxX() - 1, 0).getX();
    check(Math.abs(guide.localToScene(1, 0).getX() - Math.min(stripRight, thirdLeft + thirdHeader.getWidth())) <= 1,
        "The insertion guide must follow the pointer to the opposite edge");
    tabs.clearInsertion();
    check(!guide.isVisible() && tab.getTooltip() == null,
        "Drag exit must clear the guide, and tabs must omit redundant filename tooltips");
    boolean[] permuted = {false};
    javafx.collections.ListChangeListener<Tab> listener = change -> {
      while (change.next()) if (change.wasPermutated()) permuted[0] = true;
    };
    tabs.getTabs().addListener(listener);
    check(tabs.moveToInsertion(tab, 3) && tabs.getTabs().equals(List.of(secondTab, thirdTab, tab)),
        "A native header drop after the last tab must reorder the existing models");
    check(permuted[0], "Header reordering must use AtlantaFX's real permutation event, not detach and recreate tab skins");
    check(tabs.getSelectionModel().getSelectedItem() == tab && fixture.active() == original && fixture.editor() == editor,
        "Header reordering must retain the dragged editor and select its document");
    var movedHeader = header(tabs, tab);
    check(Math.abs(movedHeader.localToScene(0, 0).getX() - firstLeft) <= 1 && movedHeader.getTranslateX() < -1,
        "Reordered headers must begin at their previous painted positions");
    Timeline motion = (Timeline) field(tabs, "reorderMotion");
    double offset = movedHeader.getTranslateX();
    motion.jumpTo(Duration.millis(80));
    check(movedHeader.getTranslateX() > offset && movedHeader.getTranslateX() < 0,
        "Header positions must ease toward their new layout during the animation");
    motion.jumpTo(Duration.millis(160));
    check(movedHeader.getTranslateX() == 0, "Header animation must finish at the actual new layout position");
    check(tabs.moveToInsertion(tab, 0) && tabs.getTabs().getFirst() == tab,
        "A header drop before the first tab must also reorder backwards");
    tabs.getTabs().removeListener(listener);
    tabs.getSelectionModel().select(secondTab); call(fixture.app, "closeActive", new Class<?>[0]);
    tabs.getSelectionModel().select(thirdTab); call(fixture.app, "closeActive", new Class<?>[0]);
    tabs.getSelectionModel().select(tab);
  }

  private static void narrowDropGuide() {
    FxDocumentTabs tabs = new FxDocumentTabs();
    tabs.getStyleClass().addAll("floating", "dense", "document-tabs", "workspace-band");
    tabs.setMinWidth(0); tabs.setTabMinWidth(110); tabs.setAnimated(false);
    tabs.getTabs().addAll(new Tab("first", "A very long first document"), new Tab("second", "Second document"),
        new Tab("third", "Third document"));
    tabs.getSelectionModel().selectFirst();
    StackPane root = new StackPane(tabs);
    root.getStyleClass().add("workspace-root");
    root.getStylesheets().add(FxDocumentTabs.class.getResource("/fx-workspace.css").toExternalForm());
    Stage stage = new Stage(); stage.setScene(new Scene(root, 175, 70)); stage.show();
    try {
      root.applyCss(); root.layout();
      Region strip = (Region) tabs.lookup(".tabs");
      double edge = strip.localToScene(strip.getClip().getBoundsInParent().getMaxX(), 0).getX();
      int insertion = tabs.insertionAt(edge - 1);
      check(insertion > 0 && insertion < tabs.getTabs().size(),
          "A drop at the visible edge must insert after the visible header rather than jump past clipped tabs");
      tabs.showInsertion(insertion);
      Region guide = (Region) tabs.lookup(".tab-insertion-guide");
      check(guide.isVisible() && guide.localToScene(2, 0).getX() <= edge + .01,
          "The two-pixel insertion guide must stay inside the clipped tab viewport");
    } finally { stage.close(); }
  }

  private static TabSkin header(FxDocumentTabs tabs, Tab tab) {
    return tabs.lookupAll(".tab").stream().filter(TabSkin.class::isInstance).map(TabSkin.class::cast)
        .filter(skin -> skin.getTab() == tab).findFirst().orElseThrow();
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
    check(tabs(fixture.primary) instanceof FxDocumentTabs && tabs(fixture.secondary) instanceof FxDocumentTabs
        && tabs(fixture.primary).getTabDragPolicy() == Tab.DragPolicy.FIXED,
        "All headers must use one native drag gesture with AtlantaFX's reorder API, without its competing mouse-only drag handler");
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
