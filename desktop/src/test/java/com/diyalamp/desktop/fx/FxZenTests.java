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
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.Region;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/** Exercises real window-layout transitions, interruption, selection, and file safety. */
public final class FxZenTests {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] arguments) throws Exception {
    CountDownLatch ready = new CountDownLatch(1);
    Platform.startup(ready::countDown);
    if (!ready.await(10, TimeUnit.SECONDS)) throw new AssertionError("FX startup timed out");
    Fixture fixture = null;
    try {
      fixture = fx(Fixture::new);
      reversesWithoutJump(fixture); hidesAndRestores(fixture); preservesCollapsedSidebar(fixture); pendingInputSurvives(fixture);
    } finally {
      if (fixture != null) {
        Fixture current = fixture; fx(() -> { current.close(); return null; });
      }
      Platform.exit();
    }
    System.out.println("Passed " + checks + " JavaFX Zen checks.");
  }

  private static void reversesWithoutJump(Fixture fixture) throws Exception {
    double initial = fx(() -> fixture.explorer.getWidth());
    fx(() -> { fixture.zenButton().fire(); return null; });
    Thread.sleep(150);
    double middle = fx(() -> fixture.explorer.getWidth());
    check(middle > 0 && middle < initial, "Entering Zen must reveal intermediate sidebar geometry");
    double[] beforeAfter = fx(() -> {
      double before = fixture.explorer.getPrefWidth();
      fixture.zenMenu().fire();
      return new double[] {before, fixture.explorer.getPrefWidth()};
    });
    check(Math.abs(beforeAfter[0] - beforeAfter[1]) < .01,
        "Reversing an in-flight transition must begin at the current position, without a jump");
    Thread.sleep(430);
    fx(() -> {
      fixture.layout();
      check(!fixture.app.zenModeProperty().get() && fixture.explorer.isManaged(), "Reversal must restore normal layout");
      check(Math.abs(fixture.explorer.getWidth() - initial) < 1, "Sidebar width must return to its original value");
      return null;
    });
  }

  private static void hidesAndRestores(Fixture fixture) throws Exception {
    int anchor = fx(() -> fixture.editor.area().getAnchor());
    int caret = fx(() -> fixture.editor.area().getCaretPosition());
    double[] window = fx(() -> new double[] {fixture.stage.getX(), fixture.stage.getY(),
        fixture.stage.getWidth(), fixture.stage.getHeight()});
    fx(() -> { fixture.zenMenu().fire(); return null; });
    Thread.sleep(430);
    fx(() -> {
      fixture.layout();
      check(fixture.app.zenModeProperty().get() && fixture.app.zenProgressProperty().get() == 1,
          "Zen must settle completely");
      check(!fixture.navigation.isVisible() && !fixture.explorer.isManaged(), "Navigation and explorer must leave layout and focus traversal");
      check(!fixture.primaryChrome.isManaged() && !fixture.secondaryChrome.isManaged(), "Both pane headers must hide");
      check(!fixture.footer.isManaged(), "The status bar must hide in Zen");
      check(fixture.exit.isVisible() && !fixture.exit.isDisabled(), "A visible Exit Zen button must remain available");
      check(fixture.splitItems().size() == 2 && fixture.splitItems().get(0) == fixture.firstPane,
          "Zen must retain the same split-pane nodes");
      check(fixture.editor.area().getAnchor() == anchor && fixture.editor.area().getCaretPosition() == caret,
          "Zen must leave the current text selection unchanged");
      check(!fixture.stage.isFullScreen() && fixture.stage.getX() == window[0] && fixture.stage.getY() == window[1]
          && fixture.stage.getWidth() == window[2] && fixture.stage.getHeight() == window[3],
          "Zen is a workspace mode and cannot alter native window size or full-screen state");
      check(fixture.zenMenu().getAccelerator().equals(new KeyCodeCombination(KeyCode.F,
          KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)), "The View command must expose Shift-Command-F on macOS");
      fixture.exit.fire(); return null;
    });
    Thread.sleep(430);
    fx(() -> {
      fixture.layout();
      check(fixture.primaryChrome.isManaged() && fixture.secondaryChrome.isManaged() && fixture.footer.isManaged(),
          "Exiting must restore the same headers and status bar");
      check(!fixture.exit.isVisible() && !fixture.zenButton().isSelected(), "The Exit overlay and toolbar state must reset");
      return null;
    });
  }

  private static void preservesCollapsedSidebar(Fixture fixture) throws Exception {
    fx(() -> { call(fixture.app, "toggleSidebar", new Class<?>[0]); return null; });
    Thread.sleep(230);
    fx(() -> { fixture.zenButton().fire(); return null; });
    Thread.sleep(430);
    fx(() -> { fixture.exit.fire(); return null; });
    Thread.sleep(430);
    fx(() -> {
      fixture.layout();
      check(!fixture.explorer.isManaged() && !fixture.explorer.isVisible(),
          "A sidebar closed before Zen must remain closed afterward");
      check(fixture.navigation.isVisible(), "The activity rail must still return when the explorer stays closed");
      check(fixture.splitItems().size() == 2, "The split layout must survive repeated Zen entry and exit");
      return null;
    });
  }

  private static void pendingInputSurvives(Fixture fixture) throws Exception {
    fx(() -> {
      fixture.editor.area().appendText(" Pending text before Zen.");
      fixture.zenButton().fire();
      check((boolean) call(fixture.app, "saveAll", new Class<?>[] {boolean.class}, false),
          "Save must flush pending text during a Zen transition");
      check(Files.readString(fixture.activePath).contains("Pending text before Zen."),
          "Entering Zen must not discard keystrokes waiting for autosave");
      return null;
    });
  }

  private static final class Fixture implements AutoCloseable {
    final Path directory = Files.createTempDirectory("diyalamp-zen-test-").toRealPath();
    final Path activePath = directory.resolve("Reference.md");
    final Preferences preferences = Preferences.userRoot().node("com/diyalamp/tests/" + UUID.randomUUID());
    final FxDiyalamp app = new FxDiyalamp(preferences);
    final Stage stage = new Stage();
    final Region explorer, navigation, primaryChrome, secondaryChrome, footer;
    final Button exit;
    final Object primary, secondary;
    final javafx.scene.Node firstPane;
    final FxWritingPane editor;

    Fixture() throws Exception {
      Files.writeString(directory.resolve("Draft.md"), "# Draft\n\nFictional writing.\n");
      Files.writeString(activePath, "# Reference\n\n" + "A fictional paragraph for scrolling.\n\n".repeat(90));
      app.start(stage);
      call(app, "setFolder", new Class<?>[] {Path.class}, directory);
      call(app, "open", new Class<?>[] {Path.class, boolean.class}, directory.resolve("Draft.md"), false);
      call(app, "open", new Class<?>[] {Path.class, boolean.class}, activePath, true);
      primary = field(app, "primary"); secondary = field(app, "secondary");
      explorer = (Region) field(app, "explorerClip"); navigation = (Region) field(app, "navigationClip");
      primaryChrome = (Region) field(primary, "chromeClip"); secondaryChrome = (Region) field(secondary, "chromeClip");
      footer = (Region) field(app, "footerClip"); exit = (Button) field(app, "exitZen");
      firstPane = (javafx.scene.Node) field(primary, "container");
      editor = (FxWritingPane) field(field(app, "active"), "editor");
      layout(); editor.area().selectRange(4, 8);
    }

    ToggleButton zenButton() throws Exception {
      atlantafx.base.controls.TabLine tabs = (atlantafx.base.controls.TabLine) field(primary, "tabs");
      return (ToggleButton) ((javafx.scene.layout.HBox) tabs.getRightNode()).getChildren().stream()
          .filter(node -> "Zen mode".equals(node.getAccessibleText())).findFirst().orElseThrow();
    }
    MenuItem zenMenu() throws Exception {
      BorderPane root = (BorderPane) field(app, "root");
      Menu menu = ((MenuBar) root.getTop()).getMenus().stream().filter(item -> item.getText().equals("View")).findFirst().orElseThrow();
      return menu.getItems().stream().filter(item -> item.getText() != null && item.getText().contains("Zen mode")).findFirst().orElseThrow();
    }
    List<javafx.scene.Node> splitItems() throws Exception {
      return ((javafx.scene.control.SplitPane) field(app, "split")).getItems();
    }
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
  private static Object call(Object object, String name, Class<?>[] parameters, Object... arguments) throws Exception {
    Method method = object.getClass().getDeclaredMethod(name, parameters); method.setAccessible(true);
    return method.invoke(object, arguments);
  }
  private static <T> T fx(Callable<T> action) throws Exception {
    CountDownLatch finished = new CountDownLatch(1); AtomicReference<T> value = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.runLater(() -> { try { value.set(action.call()); } catch (Throwable error) { failure.set(error); } finally { finished.countDown(); } });
    if (!finished.await(10, TimeUnit.SECONDS)) throw new AssertionError("FX task timed out");
    if (failure.get() != null) throw new AssertionError("Zen regression", failure.get());
    return value.get();
  }
}
