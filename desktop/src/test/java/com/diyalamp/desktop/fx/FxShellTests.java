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
import javafx.css.PseudoClass;
import javafx.event.ActionEvent;
import javafx.scene.AccessibleRole;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
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
      check(tabs.getRightNode() == null, "New/Split actions belong outside the note view");
      Stage stage = new Stage(); set(fixture.app, "stage", stage);
      call(fixture.app, "buildExplorer", new Class<?>[0]); call(fixture.app, "refreshTree", new Class<?>[0]);
      var toolbar = (javafx.scene.control.ToolBar) call(fixture.app, "buildToolbar", new Class<?>[0]);
      Button accent = (Button) call(fixture.app, "accentButton", new Class<?>[0]);
      check(toolbar.getItems().contains(accent) && (accent.getText() == null || accent.getText().isEmpty())
          && accent.getAccessibleText().equals("Workspace accent color") && accent.getAccessibleRole() == AccessibleRole.BUTTON,
          "The global accent circle must retain an accessible name without a visible label");
      BorderPane root = (BorderPane) field(fixture.app, "root");
      root.setTop(toolbar);
      root.setCenter(new HBox((VBox) field(fixture.app, "explorer"), (Region) field(host, "container")));
      Label status = (Label) field(fixture.app, "status");
      HBox statusBar = new HBox(status); statusBar.getStyleClass().add("workspace-status"); root.setBottom(statusBar);
      root.getStyleClass().add("workspace-root");
      root.getStylesheets().add(FxDiyalamp.class.getResource("/fx-workspace.css").toExternalForm());
      call(fixture.app, "initializeAccent", new Class<?>[0]);
      tabs.getTabs().add((Tab) field(view, "tab")); tabs.getSelectionModel().selectFirst();
      stage.setScene(new Scene(root, 1100, 600)); stage.show(); root.applyCss(); root.layout();
      check(accent.getHeight() >= 36 && accent.getWidth() >= 36 && accent.getWidth() <= 48,
          "The global Accent control must have comfortable compact dimensions");
      Region swatch = (Region) accent.getGraphic();
      check(swatch.getWidth() == swatch.getHeight() && swatch.getWidth() >= 12
          && swatch.getBackground().getFills().getFirst().getRadii().getTopLeftHorizontalRadius() >= 6,
          "The current accent preview must render as a small circle");
      var uiFonts = new java.util.ArrayList<Font>();
      uiFonts.add(((Button) field(fixture.app, "folderControl")).getFont());
      var breadcrumb = (FxDocumentLocation) field(host, "breadcrumb");
      var crumbs = breadcrumb.lookupAll(".document-crumb").stream().map(node -> (Button) node).toList();
      check(crumbs.size() == 2 && crumbs.stream().allMatch(crumb -> crumb.getFont().getSize() == 16)
          && crumbs.stream().anyMatch(crumb -> crumb.getFont().getName().equals("Inter SemiBold")),
          "The current breadcrumb uses weight, at the same size as ordinary UI text");
      uiFonts.add(status.getFont()); uiFonts.add(accent.getFont());
      var fileLabels = root.lookupAll(".file-tree .tree-cell .label").stream()
          .filter(node -> node instanceof Label label && label.getText().equals(fixture.path.getFileName().toString()))
          .map(node -> ((Label) node).getFont()).toList();
      var tabLabels = root.lookupAll(".document-tabs .tab .label").stream()
          .filter(node -> node instanceof Label label && label.getText().equals(fixture.path.getFileName().toString()))
          .map(node -> ((Label) node).getFont()).toList();
      check(!fileLabels.isEmpty() && !tabLabels.isEmpty(), "Real file and tab labels must be laid out for the typography check");
      uiFonts.addAll(fileLabels); uiFonts.addAll(tabLabels);
      check(uiFonts.stream().allMatch(font -> font.getName().equals("Inter Regular") && font.getSize() == 16),
          "Every ordinary UI label must resolve the same real Inter Regular16 face: " + uiFonts);
      var prose = fixture.editor.area().lookupAll(".md-text").stream()
          .filter(node -> node instanceof TextExt text && text.getText().contains("Original"))
          .map(node -> ((TextExt) node).getFont()).toList();
      check(!prose.isEmpty() && prose.stream().allMatch(font -> font.getName().equals("Inter Regular") && font.getSize() == 16),
          "Rendered prose must use the exact same regular face and size as the app controls");
      accent.fire();
      var popup = (atlantafx.base.controls.Popover) field(fixture.app, "accentMenu");
      check(popup.isShowing() && popup.isAutoHide() && popup.isHideOnEscape() && !popup.isDetachable(),
          "Accent choices must use the real anchored AtlantaFX Popover, dismissible without a modal window");
      check(popup.getRoot().getOpacity() < 1 && popup.getRoot().getTranslateY() < 0,
          "The shared motion visibly fades and slides the anchored popup into place");
      popup.getRoot().applyCss(); popup.getRoot().layout();
      var choices = popup.getContentNode().lookupAll(".accent-choice").stream().map(node -> (Button) node).toList();
      var palette = choices.stream().collect(java.util.stream.Collectors.toMap(Button::getText, button -> button));
      check(palette.keySet().equals(java.util.Set.of("Red", "Blue", "Yellow", "Green", "White"))
              && popup.getContentNode().lookupAll(".color-picker").isEmpty(),
          "The accent bubble must contain exactly five named colors without a second popup picker");
      check(choices.stream().allMatch(button -> button.getFont().getName().equals("Inter Regular") && button.getFont().getSize() == 16),
          "Actual palette buttons must use the shared regular UI typography");
      var widths = choices.stream().map(Button::getWidth).toList();
      check(widths.getFirst() > 100 && widths.stream().allMatch(width -> Math.abs(width - widths.getFirst()) <= 1),
          "The accent picker uses two stable equal-width columns");
      PseudoClass selected = PseudoClass.getPseudoClass("selected");
      check(FxTheme.accentOf(root).equals(Color.web("#EE0000")) && palette.get("Red").getPseudoClassStates().contains(selected),
          "A clean workspace must begin with the red accent selected");
      Map<String, String> presets = Map.of("Red", "#EE0000", "Blue", "#489CFF", "Yellow", "#F7C843",
          "Green", "#4CD980", "White", "#FFFFFF");
      for (String name : java.util.List.of("Blue", "Yellow", "Green", "White", "Red")) {
        Button choice = palette.get(name);
        choice.fire();
        popup.getRoot().applyCss(); popup.getRoot().layout();
        check(widths.equals(choices.stream().map(Button::getWidth).toList()),
            "Selecting " + name + " must keep both columns stable");
        check(choice.getPseudoClassStates().contains(selected) && choice.getAccessibleText().endsWith(", selected")
                && choice.getText().equals(name),
            "The styled selection and accessible name must leave the visible label unchanged");
        check(fixture.preferences.get("accent", "").equals(presets.get(name)) && popup.isShowing(),
            "A preset must store its exact color and leave the bubble available");
      }
      TitledPane custom = (TitledPane) popup.getContentNode().lookup(".accent-custom");
      TextField hex = (TextField) custom.getContent().lookup(".accent-hex");
      Button apply = (Button) custom.getContent().lookup(".popover-action");
      check(hex != null && !custom.isExpanded(),
          "The custom hex field must be hidden until Custom opens it");
      custom.setExpanded(true);
      popup.getRoot().applyCss(); popup.getRoot().layout();
      check(custom.isExpanded() && "#EE0000".equals(hex.getText())
              && widths.equals(choices.stream().map(Button::getWidth).toList()),
          "Custom must reveal the current hex value without resizing the choices");
      hex.setText("#12G456");
      hex.fireEvent(new ActionEvent());
      check(apply.isDisabled() && fixture.preferences.get("accent", "").equals("#EE0000"),
          "An invalid hex value must be rejected without altering preferences");
      hex.setText("7b8fb7");
      hex.fireEvent(new ActionEvent());
      check(!apply.isDisabled() && fixture.preferences.get("accent", "").equals("#7B8FB7")
              && custom.getPseudoClassStates().contains(selected) && custom.getAccessibleText().endsWith(", selected"),
          "A valid custom hex without a hash must save canonically and select Custom");
      FxDiyalamp reopened = new FxDiyalamp(fixture.preferences);
      call(reopened, "initializeAccent", new Class<?>[0]);
      check(FxTheme.accentOf((BorderPane) field(reopened, "root")).equals(Color.web("#7B8FB7")),
          "An older valid accent value must survive reopening as a custom color");
      call(reopened, "disposeAll", new Class<?>[0]);
      popup.hide(javafx.util.Duration.ZERO); stage.close();
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
