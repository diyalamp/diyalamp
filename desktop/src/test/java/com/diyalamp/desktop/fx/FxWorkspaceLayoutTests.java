package com.diyalamp.desktop.fx;

import atlantafx.base.controls.TabLine;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;

/** Checks the actual full-window composition and native control hit areas at desktop widths. */
public final class FxWorkspaceLayoutTests {
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
        fixture = new Fixture(); Fixture current = fixture;
        PauseTransition settle = new PauseTransition(Duration.millis(300));
        settle.setOnFinished(event -> {
          try { current.layout(); composition(current); compactWidth(current); }
          catch (Throwable error) { failure.compareAndSet(null, error); }
          finally {
            try { current.close(); } catch (Throwable error) { failure.compareAndSet(null, error); }
            finished.countDown();
          }
        });
        settle.play();
      } catch (Throwable error) {
        failure.compareAndSet(null, error);
        if (fixture != null) try { fixture.close(); } catch (Throwable closeError) { failure.compareAndSet(null, closeError); }
        finished.countDown();
      }
    });
    if (!finished.await(30, TimeUnit.SECONDS)) throw new AssertionError("Workspace layout tests timed out");
    Platform.exit();
    if (failure.get() != null) throw new AssertionError("Workspace layout regression", failure.get());
    System.out.println("Passed " + checks + " JavaFX workspace layout checks.");
  }

  private static void composition(Fixture fixture) throws Exception {
    check(Application.getUserAgentStylesheet().contains("cupertino-dark"),
        "The workspace must use the maintained Cupertino Dark control theme");
    ToolBar toolbar = (ToolBar) field(fixture.app, "toolbar");
    check(toolbar.getHeight() >= 52 && toolbar.getHeight() <= 64, "The shared toolbar must have a single comfortable control row");
    for (String name : new String[] {"Workspace accent color", "Pomodoro focus timer", "Zen mode", "Toggle right sidebar", "New note", "New folder", "Open note side by side", "Search notes"}) {
      check(toolbar.getItems().stream().filter(node -> name.equals(node.getAccessibleText())).count() == 1,
          "The shared toolbar must expose one accessible " + name + " control");
    }
    HBox footer = (HBox) field(fixture.app, "footer");
    check(footer.getChildren().stream().noneMatch(node -> "Pomodoro focus timer".equals(node.getAccessibleText())),
        "The focus timer must live in the top toolbar, rather than the save-status footer");
    Object primary = field(fixture.app, "primary");
    TabLine tabs = (TabLine) field(primary, "tabs");
    check(tabs.getRightNode() == null, "Note panes must not duplicate the workspace New/Split controls");
    VBox explorerList = (VBox) field(fixture.app, "explorer");
    TreeView<?> tree = (TreeView<?>) field(fixture.app, "tree");
    check(explorerList.getChildren().size() == 1 && explorerList.getChildren().getFirst() == tree
        && explorerList.lookupAll(".button").isEmpty(), "The files sidebar contains only the note tree");
    HBox metrics = (HBox) field(fixture.app, "metrics");
    check(metrics.getChildren().size() == 4 && metrics.isVisible(), "An open note must expose all four live statistics");
    check(((javafx.scene.control.Label) field(fixture.app, "characters")).getText().endsWith(" chars")
        && ((javafx.scene.control.Label) field(fixture.app, "words")).getText().endsWith(" words")
        && ((javafx.scene.control.Label) field(fixture.app, "lines")).getText().endsWith(" lines")
        && !((javafx.scene.control.Label) field(fixture.app, "fileSize")).getText().isBlank(),
        "Characters, words, logical lines and UTF-8 size must all render in the status bar");
    var tabLabels = tabs.lookupAll(".tab .label").stream()
        .filter(node -> node instanceof javafx.scene.control.Label label && label.getText().equals("Draft.md"))
        .toList();
    check(!tabLabels.isEmpty() && tabLabels.stream().allMatch(node -> node.isVisible() && node.isManaged()
        && node.getLayoutBounds().getWidth() > 40),
        "The selected document filename must remain visible after the tab's reveal animation");
    Region explorer = (Region) field(fixture.app, "explorerClip");
    Region pane = (Region) field(primary, "container");
    check(explorer.localToScene(0, 0).getX() == 0 && Math.abs(pane.localToScene(0, 0).getX() - explorer.getWidth()) <= 7,
        "The single files sidebar must lead directly into the editor without a second activity rail");
    for (Node node : toolbar.getItems()) {
      if (!(node instanceof javafx.scene.control.ButtonBase button)) continue;
      check(button.getHeight() >= 32 && button.getWidth() >= 32,
          "Every global toolbar action must retain a comfortable click target");
    }
    var brand = toolbar.lookupAll(".workspace-brand").stream().findFirst().orElseThrow();
    check(brand instanceof HBox && ((HBox) brand).getChildren().size() == 2,
        "The approved lamp and diyalamp name must share one horizontal brand row");
    check(toolbar.getItems().getFirst() == brand && "Toggle files sidebar".equals(toolbar.getItems().get(1).getAccessibleText()),
        "The sidebar toggle follows the lamp and diyalamp name");
    Label path = (Label) field(fixture.app, "folderLabel");
    check(path.isVisible() && path.getText().equals(fixture.directory.toString())
        && path.getTooltip().getText().equals(path.getText()), "The toolbar exposes the full workspace path");
    String imagePath = System.getProperty("diyalamp.workspace.snapshot");
    if (imagePath != null && !imagePath.isBlank()) snapshot(fixture.stage, Path.of(imagePath));
    ToggleButton find = (ToggleButton) toolbar.getItems().stream()
        .filter(node -> "Search notes".equals(node.getAccessibleText())).findFirst().orElseThrow();
    TextField search = (TextField) field(fixture.app, "search");
    check(!search.isVisible(), "Search starts as an icon");
    find.fire(); fixture.layout();
    search.setText("Reference");
    check(search.isVisible() && search.getFont().getSize() == 16 && tree.getExpandedItemCount() == 1,
        "The toolbar search filters the note tree at the shared font size");
    search.fireEvent(new ActionEvent());
    check(!search.isVisible() && tree.getExpandedItemCount() == 2 && fixture.stage.getTitle().startsWith("Reference.md"),
        "Enter opens the search result and restores the full note list and folder path");
  }

  private static void compactWidth(Fixture fixture) throws Exception {
    fixture.stage.setWidth(820); fixture.layout();
    Region pane = (Region) field(field(fixture.app, "primary"), "container");
    check(pane.getWidth() >= 500, "The default closed inspector must leave a useful writing area at the minimum window width");
    check(pane.localToScene(pane.getWidth(), 0).getX() <= fixture.stage.getScene().getWidth() + 1,
        "The editor must stay within the native window rather than overflowing horizontally");
    Node toggle = (Node) field(fixture.app, "connectionsControl");
    var bounds = toggle.localToScene(toggle.getBoundsInLocal());
    check(toggle.isVisible() && bounds.getMinX() >= 0 && bounds.getMaxX() <= fixture.stage.getScene().getWidth(),
        "The persistent right-sidebar toggle must fit the top bar even at the minimum width");
    HBox metrics = (HBox) field(fixture.app, "metrics");
    var stats = metrics.localToScene(metrics.getBoundsInLocal());
    check(stats.getMinX() >= 0 && stats.getMaxX() <= fixture.stage.getScene().getWidth(),
        "All four status statistics must fit within the window");
  }

  private static void snapshot(Stage stage, Path path) throws Exception {
    // The macOS system menu is outside the app's content; omit its headless fallback in exports.
    stage.getScene().getRoot().lookupAll(".menu-bar").forEach(menu -> {
      menu.setManaged(false); menu.setVisible(false);
    });
    stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
    WritableImage image = stage.getScene().getRoot().snapshot(null, null);
    BufferedImage bitmap = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
    for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++)
      bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
    ImageIO.write(bitmap, "png", path.toFile());
  }

  private static final class Fixture implements AutoCloseable {
    final Path parent = Files.createTempDirectory("diyalamp-workspace-test-").toRealPath();
    final Path directory = Files.createDirectory(parent.resolve("Notebook"));
    final Preferences preferences = Preferences.userRoot().node("com/diyalamp/tests/" + UUID.randomUUID());
    final FxDiyalamp app = new FxDiyalamp(preferences);
    final Stage stage = new Stage();
    Fixture() throws Exception {
      Files.writeString(directory.resolve("Draft.md"),
          "# A connected writing workspace\n\nWrite a draft beside its references. Link related notes with [[Reference]].\n\n"
          + "## Local Markdown files\n\n- Keep the files on your Mac\n- Open two notes side by side\n- Start a focus session when you are ready\n\n"
          + "```java\nString idea = \"A small beginning\";\nSystem.out.println(idea);\n```\n");
      Files.writeString(directory.resolve("Reference.md"), "# Reference\n\nReturn to [[Draft]].\n");
      app.start(stage);
      call(app, "setFolder", new Class<?>[] {Path.class}, directory);
      call(app, "open", new Class<?>[] {Path.class, boolean.class}, directory.resolve("Draft.md"), false);
      layout();
    }
    void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    @Override public void close() throws Exception {
      app.stop(); stage.hide(); preferences.removeNode();
      try (var paths = Files.walk(parent)) {
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
}
