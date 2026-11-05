package com.diyalamp.desktop.fx;

import com.diyalamp.desktop.NoteStore;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import javafx.application.Platform;
import javafx.scene.AccessibleRole;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.fxmisc.richtext.TextExt;
import atlantafx.base.controls.Tab;
import javafx.stage.Stage;
import atlantafx.base.controls.Sidebar;
import atlantafx.base.controls.TabLine;

/** Real editor input followed by immediate shell file actions; no window or private notes. */
public final class FxShellTests {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] arguments) throws Exception {
    CountDownLatch finished = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      try { FxTheme.setup(); immediateSave(); immediateQuit(); pendingConflict(); continueSavingAfterConflict(); synchronizedPane(); accentAndNavigation(); }
      catch (Throwable error) { failure.set(error); }
      finally { finished.countDown(); }
    });
    if (!finished.await(45, TimeUnit.SECONDS)) throw new AssertionError("Shell tests timed out");
    Platform.exit();
    if (failure.get() != null) throw new AssertionError("Shell regression", failure.get());
    System.out.println("Passed " + checks + " JavaFX shell checks.");
  }

  private static void immediateSave() throws Exception {
    try (Fixture fixture = new Fixture()) {
      for (int i = 0; i < 100; i++) fixture.editor.area().appendText("x");
      check(field(fixture.session, "text").equals("Original\n"),
          "Typing must not synchronously serialize the complete note");
      check(field(fixture.session, "pendingView") != null, "The latest editor view must be pending");
      check((boolean) call(fixture.app, "saveAll", new Class<?>[] {boolean.class}, false),
          "Immediate Save must flush and persist pending text");
      check(Files.readString(fixture.path).contains("x".repeat(100)),
          "Save cannot lose a burst of keystrokes before debounce runs");
      check(field(fixture.session, "pendingView") == null, "Save must consume the pending snapshot");
      check(!((boolean) field(fixture.session, "dirty")), "A successful Save clears dirty state");
    }
  }

  private static void immediateQuit() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.editor.area().appendText(" just before quit");
      fixture.app.stop();
      check(Files.readString(fixture.path).contains("just before quit"),
          "Native Quit must save characters still waiting for the debounce timer");
    }
  }

  private static void pendingConflict() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Files.writeString(fixture.path, "Changed outside the editor\n");
      fixture.editor.area().appendText(" my unsaved words");
      check(!((boolean) call(fixture.app, "saveAll", new Class<?>[] {boolean.class}, false)),
          "Pending edits must still obey optimistic conflict checks");
      check(Files.readString(fixture.path).equals("Changed outside the editor\n"),
          "A conflict must leave external file content unchanged");
      check(((String) field(fixture.session, "text")).contains("my unsaved words"),
          "Conflicted pending input must remain available for recovery export");
      check((boolean) field(fixture.session, "dirty"), "A conflict cannot mark the note saved");
    }
  }

  private static void synchronizedPane() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Object otherView = fixture.newView();
      FxWritingPane other = (FxWritingPane) field(otherView, "editor");
      fixture.editor.area().appendText(" shared edit");
      check(!other.recoveryText().contains("shared edit"), "Synchronization should be coalesced while typing");
      call(fixture.app, "saveAll", new Class<?>[] {boolean.class}, false);
      check(other.recoveryText().contains("shared edit"),
          "Flushing before a file action must synchronize the reference pane");
      check(other.markdown().equals(fixture.editor.markdown()), "Both views must export the same note after flush");
    }
  }

  @SuppressWarnings("unchecked")
  private static void continueSavingAfterConflict() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Path otherPath = fixture.store.create("Another note", "Another original\n");
      Constructor<?> sessionConstructor = fixture.sessionType.getDeclaredConstructor(Path.class, NoteStore.Loaded.class);
      sessionConstructor.setAccessible(true);
      Object otherSession = sessionConstructor.newInstance(otherPath, fixture.store.load(otherPath));
      ((Map<Path, Object>) field(fixture.app, "sessions")).put(otherPath, otherSession);
      Constructor<?> hostConstructor = fixture.hostType.getDeclaredConstructor(FxDiyalamp.class);
      hostConstructor.setAccessible(true);
      Object otherHost = hostConstructor.newInstance(fixture.app);
      Constructor<?> viewConstructor = fixture.viewType.getDeclaredConstructor(FxDiyalamp.class,
          fixture.sessionType, fixture.hostType);
      viewConstructor.setAccessible(true);
      Object otherView = viewConstructor.newInstance(fixture.app, otherSession, otherHost);
      FxWritingPane otherEditor = (FxWritingPane) field(otherView, "editor");

      Files.writeString(fixture.path, "Changed outside the editor\n");
      fixture.editor.area().appendText(" my conflicted input");
      otherEditor.area().appendText(" my valid input");
      check(!((boolean) call(fixture.app, "saveAll", new Class<?>[] {boolean.class}, false)),
          "Save all must report failure when any note has an external conflict");
      check(Files.readString(otherPath).contains("my valid input"),
          "A conflict in an earlier note must not prevent later notes from saving");
      check(Files.readString(fixture.path).equals("Changed outside the editor\n"),
          "Continuing save all must still preserve the external version of the conflicted note");
      check((boolean) field(fixture.session, "dirty"),
          "The conflicted note must remain dirty and recoverable");
      check(((String) field(fixture.session, "text")).contains("my conflicted input"),
          "Continuing save all must retain the conflicted in-memory input");
      check(!((boolean) field(otherSession, "dirty")),
          "A successfully saved later note must clear its dirty state");
      check(field(otherSession, "pendingView") == null,
          "Saving a later note must flush its pending editor input");
    }
  }

  @SuppressWarnings("unchecked") private static void accentAndNavigation() throws Exception {
    try (Fixture fixture = new Fixture()) {
      Object view = ((java.util.List<?>) field(fixture.session, "views")).getFirst();
      Object host = field(view, "host");
      TabLine tabs = (TabLine) field(host, "tabs");
      HBox tools = (HBox) tabs.getRightNode();
      check(tools.getChildren().size() == 3, "New-note and split controls must remain beside the accent control");
      check(tools.getChildren().get(2) instanceof Button, "The toolbar accent control must be a standard accessible Button");
      Button accent = (Button) tools.getChildren().get(2);
      check(accent.getText().equals("Accent") && accent.getAccessibleText().equals("Workspace accent color")
          && accent.getAccessibleRole() == AccessibleRole.BUTTON,
          "The accent control must expose its visible name and a button role to accessibility");

      call(fixture.app, "buildNavigation", new Class<?>[0]);
      call(fixture.app, "buildExplorer", new Class<?>[0]);
      call(fixture.app, "refreshTree", new Class<?>[0]);
      Sidebar<?> navigation = (Sidebar<?>) field(fixture.app, "navigation");
      Region pane = (Region) field(host, "container");
      VBox explorer = (VBox) field(fixture.app, "explorer");
      BorderPane root = new BorderPane(new HBox(navigation, explorer, pane));
      Label status = (Label) field(fixture.app, "status");
      HBox statusBar = new HBox(status); statusBar.getStyleClass().add("workspace-status"); root.setBottom(statusBar);
      root.getStyleClass().add("workspace-root");
      FxTheme.accent(root, FxTheme.DEFAULT_ACCENT);
      root.getStylesheets().add(FxDiyalamp.class.getResource("/fx-workspace.css").toExternalForm());
      Stage stage = new Stage(); set(fixture.app, "stage", stage);
      tabs.getTabs().add((Tab) field(view, "tab")); tabs.getSelectionModel().selectFirst();
      stage.setScene(new Scene(root, 1000, 500)); stage.show();
      root.applyCss(); root.layout();
      check(accent.getHeight() == 32 && accent.getWidth() > 70 && accent.getWidth() < 115,
          "The labelled accent button must remain a compact 32-point toolbar control");
      Region swatch = (Region) accent.getGraphic();
      check(swatch.getWidth() == swatch.getHeight() && swatch.getWidth() == 12
          && swatch.getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius() >= 6,
          "The toolbar color preview must render as a small circle");
      var cells = navigation.lookupAll(".sidebar-cell").stream()
          .filter(node -> node.isVisible()).sorted(java.util.Comparator.comparingDouble(node -> node.getBoundsInParent().getMinY())).toList();
      check(cells.size() == 2, "Both existing navigation destinations must remain available");
      check(cells.stream().allMatch(cell -> cell.getLayoutBounds().getHeight() >= 40),
          "Navigation icons need comfortably sized click targets");
      var first = cells.get(0).localToScene(cells.get(0).getBoundsInLocal());
      var second = cells.get(1).localToScene(cells.get(1).getBoundsInLocal());
      check(second.getCenterY() - first.getCenterY() >= 48,
          "Navigation icons must have more vertical separation");
      check(navigation.getItems().stream().allMatch(item -> item.getGraphic().prefWidth(-1) == 20),
          "Both navigation icons should use the larger 20-point geometry");
      var uiFonts = new java.util.ArrayList<Font>();
      uiFonts.add(((javafx.scene.control.TextField) field(fixture.app, "search")).getFont());
      uiFonts.add(((Label) field(host, "breadcrumb")).getFont());
      uiFonts.add(status.getFont());
      var fileLabels = root.lookupAll(".file-tree .tree-cell .label").stream()
          .filter(node -> node instanceof Label label && label.getText().equals(fixture.path.getFileName().toString()))
          .map(node -> ((Label) node).getFont()).toList();
      var tabLabels = root.lookupAll(".document-tabs .tab .label").stream()
          .filter(node -> node instanceof Label label && label.getText().equals(fixture.path.getFileName().toString()))
          .map(node -> ((Label) node).getFont()).toList();
      check(!fileLabels.isEmpty() && !tabLabels.isEmpty(), "Actual file and tab labels must be laid out for the font check");
      check(uiFonts.get(0).getSize() == 14 && uiFonts.get(1).getSize() == 13 && uiFonts.get(2).getSize() == 13
          && fileLabels.stream().allMatch(font -> font.getSize() == 14)
          && tabLabels.stream().allMatch(font -> font.getSize() == 14),
          "Native UI text must use 14-point labels with 13-point breadcrumb and status text");
      uiFonts.addAll(fileLabels); uiFonts.addAll(tabLabels);
      check(uiFonts.stream().allMatch(font -> font.getFamily().equals(Font.getDefault().getFamily())
          && font.getStyle().contains("Bold")),
          "Small UI text must resolve a true bold native system face, rather than an ineffective semibold request: " + uiFonts);
      var prose = fixture.editor.area().lookupAll(".md-text").stream()
          .filter(node -> node instanceof TextExt text && text.getText().contains("Original"))
          .map(node -> ((TextExt) node).getFont()).toList();
      check(!prose.isEmpty() && prose.stream().allMatch(font -> font.getSize() == 18 && !font.getStyle().contains("Bold")),
          "Stronger UI labels must not change the approved 18-point regular reading font");

      Dialog<Color> dialog = (Dialog<Color>) call(fixture.app, "accentDialog", new Class<?>[0]);
      var choices = dialog.getDialogPane().getContent().lookupAll(".accent-choice").stream().map(node -> (Button) node).toList();
      check(choices.size() == 6, "The accent dialog must offer exactly six named preset buttons");
      check(dialog.getDialogPane().getContent().lookupAll(".color-picker").isEmpty()
          && !(dialog.getDialogPane().getContent() instanceof ColorPicker),
          "The preset dialog must not expose a full-spectrum color picker");
      Button sage = choices.stream().filter(button -> button.getText().equals("Sage")).findFirst().orElseThrow();
      sage.fire();
      check(sage.getAccessibleText().contains("selected") && sage.getText().contains("✓"),
          "Selecting a preset must update both its visible and accessible selected state");
      check(dialog.getResultConverter().call(ButtonType.APPLY).equals(Color.web("#7A9A85"))
          && dialog.getResultConverter().call(ButtonType.CANCEL) == null,
          "Apply must return the selected preset while Cancel leaves the workspace unchanged");
      check(dialog.getDialogPane().getContent().lookupAll(".accent-value").stream()
          .anyMatch(node -> ((Label) node).getText().contains("#7A9A85")),
          "The selected color value must remain visible to keyboard and screen-reader users");
      var value = (javafx.beans.property.ObjectProperty<Color>) field(fixture.app, "accent");
      value.set(Color.web("#BA929B"));
      check(swatch.getStyle().contains("#BA929B"), "Both toolbar previews must track workspace accent changes");
      stage.close();
    }
  }

  private static final class Fixture implements AutoCloseable {
    final Path directory = Files.createTempDirectory("diyalamp-shell-test-");
    final NoteStore store = new NoteStore(directory);
    final Path path = store.create("Fictional note", "Original\n");
    final Preferences preferences = Preferences.userRoot().node("com/diyalamp/tests/" + UUID.randomUUID());
    final FxDiyalamp app = new FxDiyalamp(preferences);
    final Class<?> sessionType = Class.forName(FxDiyalamp.class.getName() + "$Session");
    final Class<?> hostType = Class.forName(FxDiyalamp.class.getName() + "$PaneHost");
    final Class<?> viewType = Class.forName(FxDiyalamp.class.getName() + "$View");
    final Object session;
    final FxWritingPane editor;

    @SuppressWarnings("unchecked") Fixture() throws Exception {
      set(app, "store", store);
      Constructor<?> constructor = sessionType.getDeclaredConstructor(Path.class, NoteStore.Loaded.class);
      constructor.setAccessible(true); session = constructor.newInstance(path, store.load(path));
      ((Map<Path, Object>) field(app, "sessions")).put(path, session);
      editor = (FxWritingPane) field(newView(), "editor");
    }

    Object newView() throws Exception {
      Constructor<?> hostConstructor = hostType.getDeclaredConstructor(FxDiyalamp.class);
      hostConstructor.setAccessible(true); Object host = hostConstructor.newInstance(app);
      Constructor<?> viewConstructor = viewType.getDeclaredConstructor(FxDiyalamp.class, sessionType, hostType);
      viewConstructor.setAccessible(true); return viewConstructor.newInstance(app, session, host);
    }

    @Override public void close() throws Exception {
      call(app, "disposeAll", new Class<?>[0]); preferences.removeNode();
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }

  private static Object field(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
  }
  private static void set(Object object, String name, Object value) throws Exception {
    Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
  }
  private static Object call(Object object, String name, Class<?>[] parameters, Object... arguments) throws Exception {
    Method method = object.getClass().getDeclaredMethod(name, parameters); method.setAccessible(true);
    return method.invoke(object, arguments);
  }
}
