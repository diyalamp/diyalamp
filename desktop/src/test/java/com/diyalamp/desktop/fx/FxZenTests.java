package com.diyalamp.desktop.fx;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import javafx.animation.Animation;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Line focus changes reading contrast without moving controls or replacing the writer. */
public final class FxZenTests {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    CountDownLatch ready = new CountDownLatch(1); Platform.startup(ready::countDown);
    if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("FX startup timed out");
    Fixture fixture = null;
    try {
      fixture = fx(Fixture::new);
      reversible(fixture); preservesWorkspace(fixture); sidebarIsIndependent(fixture); pendingSave(fixture);
    } finally {
      if (fixture != null) { Fixture current = fixture; fx(() -> { current.close(); return null; }); }
      Platform.exit();
    }
    System.out.println("Passed " + checks + " JavaFX Zen checks.");
  }

  private static void reversible(Fixture f) throws Exception {
    fx(() -> {
      f.zenButton().fire();
      Timeline entering = (Timeline) field(f.app, "zenAnimation");
      check(entering != null && entering.getStatus() == Animation.Status.RUNNING,
          "Line focus must enter through a real animation");
      entering.jumpTo(Duration.millis(120));
      double middle = f.app.zenProgressProperty().get();
      check(middle > 0 && middle < 1, "The focus fade must expose intermediate contrast");
      f.zenButton().fire();
      check(Math.abs(f.app.zenProgressProperty().get() - middle) < .0001,
          "An interrupted transition must reverse from its current contrast without a jump");
      return null;
    });
    awaitZen(f, false);
  }

  private static void preservesWorkspace(Fixture f) throws Exception {
    Object[] original = fx(() -> {
      f.layout();
      return new Object[] {f.zenButton().localToScene(f.zenButton().getBoundsInLocal()),
          f.editor.area().getAnchor(), f.editor.area().getCaretPosition(),
          f.editor.markdown(), f.splitItems().getFirst(),
          f.toolbar.getHeight(), f.footer.getHeight(), f.explorer.getWidth()};
    });
    fx(() -> { f.zenButton().fire(); return null; }); awaitZen(f, true);
    fx(() -> {
      f.layout();
      check(f.zenButton().isSelected() && f.zenButton().isVisible(), "The same top-bar button must enter and exit line focus");
      check(f.zenButton().localToScene(f.zenButton().getBoundsInLocal()).equals(original[0]),
          "The Zen button must remain at exactly the same position and size");
      for (Region region : List.of(f.toolbar, f.footer, f.explorer, f.primaryChrome, f.secondaryChrome))
        check(region.isManaged() && region.isVisible() && !region.isMouseTransparent(),
            "Zen must keep the bars, sidebar, and note tabs visible and usable");
      check(f.toolbar.getHeight() == (double) original[5] && f.footer.getHeight() == (double) original[6]
          && f.explorer.getWidth() == (double) original[7], "Line focus cannot resize workspace chrome");
      check(f.splitItems().size() == 2 && f.splitItems().getFirst() == original[4],
          "Line focus must retain both existing split panes");
      check(f.editor.area().getAnchor() == (int) original[1] && f.editor.area().getCaretPosition() == (int) original[2],
          "Line focus cannot move the caret or change the selection");
      check(f.editor.markdown().equals(original[3]) && !f.editor.canUndo(),
          "Changing reading contrast must not edit the note or create Undo entries");
      check(f.stage.getScene().getRoot().lookupAll(".zen-exit").isEmpty(),
          "There must be no second exit control in a different position");
      check(f.zenMenu().getAccelerator().equals(new KeyCodeCombination(KeyCode.F,
          KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)), "Shift-Command-F remains available");
      f.zenButton().fire(); return null;
    });
    awaitZen(f, false);
    fx(() -> {
      check(f.zenButton().localToScene(f.zenButton().getBoundsInLocal()).equals(original[0]),
          "Exiting line focus must leave the button in its original position");
      return null;
    });
  }

  private static void sidebarIsIndependent(Fixture f) throws Exception {
    fx(() -> {
      System.setProperty("diyalamp.reduceMotion", "true");
      try {
        f.zenButton().fire();
        check(f.app.zenProgressProperty().get() == 1, "Reduced motion must settle the focus fade immediately");
        call(f.app, "toggleSidebar", new Class<?>[0]);
        check(!f.explorer.isManaged() && f.app.zenModeProperty().get(),
            "The sidebar toggle must work independently while line focus remains enabled");
        f.zenButton().fire();
        check(!f.explorer.isManaged() && f.app.zenProgressProperty().get() == 0,
            "Exiting line focus must not reopen a sidebar the user closed");
        call(f.app, "toggleSidebar", new Class<?>[0]);
        check(f.explorer.isManaged(), "The sidebar remains directly controllable");
      } finally { System.clearProperty("diyalamp.reduceMotion"); }
      return null;
    });
  }

  private static void pendingSave(Fixture f) throws Exception {
    fx(() -> {
      f.editor.area().appendText(" Pending text before line focus.");
      f.zenButton().fire();
      check((boolean) call(f.app, "saveAll", new Class<?>[] {boolean.class}, false),
          "Save must flush pending typing during the contrast animation");
      check(Files.readString(f.activePath).contains("Pending text before line focus."),
          "Entering line focus cannot lose unsaved input");
      return null;
    });
    awaitZen(f, true);
  }

  private static void awaitZen(Fixture f, boolean enabled) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
    do {
      if (fx(() -> {
        Animation animation = (Animation) field(f.app, "zenAnimation");
        return f.app.zenModeProperty().get() == enabled && f.app.zenProgressProperty().get() == (enabled ? 1 : 0)
            && (animation == null || animation.getStatus() == Animation.Status.STOPPED);
      })) return;
      Thread.sleep(20);
    } while (System.nanoTime() < deadline);
    throw new AssertionError("Line focus transition did not finish");
  }

  private static final class Fixture implements AutoCloseable {
    final Path directory = Files.createTempDirectory("diyalamp-line-focus-").toRealPath();
    final Path activePath = directory.resolve("Reference.md");
    final Preferences preferences = Preferences.userRoot().node("com/diyalamp/tests/" + UUID.randomUUID());
    final FxDiyalamp app = new FxDiyalamp(preferences);
    final Stage stage = new Stage();
    final Region explorer, toolbar, primaryChrome, secondaryChrome, footer;
    final Object primary, secondary;
    final FxWritingPane editor;
    Fixture() throws Exception {
      Files.writeString(directory.resolve("Draft.md"), "# Draft\n\nFictional writing.\n");
      Files.writeString(activePath, "# Reference\n\n" + "A fictional paragraph for scrolling.\n\n".repeat(30));
      app.start(stage);
      call(app, "setFolder", new Class<?>[] {Path.class}, directory);
      call(app, "open", new Class<?>[] {Path.class, boolean.class}, directory.resolve("Draft.md"), false);
      call(app, "open", new Class<?>[] {Path.class, boolean.class}, activePath, true);
      primary = field(app, "primary"); secondary = field(app, "secondary");
      explorer = (Region) field(app, "explorerClip"); toolbar = (Region) field(app, "toolbarClip");
      primaryChrome = (Region) field(primary, "chromeClip"); secondaryChrome = (Region) field(secondary, "chromeClip");
      footer = (Region) field(app, "footerClip");
      editor = (FxWritingPane) field(field(app, "active"), "editor");
      layout(); editor.area().selectRange(4, 8);
    }
    ToggleButton zenButton() throws Exception {
      return (ToggleButton) ((ToolBar) field(app, "toolbar")).getItems().stream()
          .filter(node -> "Zen mode".equals(node.getAccessibleText())).findFirst().orElseThrow();
    }
    MenuItem zenMenu() throws Exception {
      BorderPane root = (BorderPane) field(app, "root");
      MenuBar bar = (MenuBar) ((javafx.scene.layout.VBox) root.getTop()).getChildren().getFirst();
      Menu menu = bar.getMenus().stream().filter(item -> item.getText().equals("View")).findFirst().orElseThrow();
      return menu.getItems().stream().filter(item -> item.getText() != null && item.getText().contains("Zen mode")).findFirst().orElseThrow();
    }
    List<Node> splitItems() throws Exception { return ((javafx.scene.control.SplitPane) field(app, "split")).getItems(); }
    void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    @Override public void close() throws Exception {
      app.stop(); stage.hide(); preferences.removeNode();
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
  }
  private static Object field(Object object, String name) throws Exception {
    Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
  }
  private static Object call(Object object, String name, Class<?>[] parameters, Object... args) throws Exception {
    Method method = object.getClass().getDeclaredMethod(name, parameters); method.setAccessible(true); return method.invoke(object, args);
  }
  private static <T> T fx(Callable<T> action) throws Exception {
    CountDownLatch finished = new CountDownLatch(1); AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.runLater(() -> { try { value.set(action.call()); } catch (Throwable error) { failure.set(error); } finally { finished.countDown(); } });
    if (!finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("FX task timed out");
    if (failure.get() != null) throw new AssertionError("Zen regression", failure.get()); return value.get();
  }
}
