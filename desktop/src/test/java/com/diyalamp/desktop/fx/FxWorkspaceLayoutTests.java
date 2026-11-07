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
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
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
        Runnable close = () -> {
          try { current.close(); } catch (Throwable error) { failure.compareAndSet(null, error); }
          finished.countDown();
        };
        PauseTransition settle = new PauseTransition(Duration.millis(300));
        settle.setOnFinished(event -> {
          try {
            current.layout(); composition(current); compactWidth(current);
            searchInWorkspace(current, failure, close);
          } catch (Throwable error) {
            failure.compareAndSet(null, error);
            close.run();
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
    for (String name : new String[] {"Workspace accent color", "Pomodoro focus timer", "Zen mode", "Toggle right sidebar", "Open folder"}) {
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
    Region filesHeader = (Region) explorerList.getChildren().getFirst();
    FxSidebarSearch sidebarSearch = (FxSidebarSearch) field(fixture.app, "sidebarSearch");
    check(explorerList.getChildren().containsAll(java.util.List.of(sidebarSearch, tree))
        && filesHeader.lookupAll(".button").stream().anyMatch(node -> "New folder".equals(node.getAccessibleText()))
        && filesHeader.lookupAll(".button").stream().anyMatch(node -> "New note".equals(node.getAccessibleText())),
        "Search and both creation actions live in the files sidebar");
    check(toolbar.getItems().stream().noneMatch(node -> java.util.List.of("New folder", "New note", "Search notes", "Open note side by side")
        .contains(String.valueOf(node.getAccessibleText()))), "Sidebar controls, including Split View, are not repeated on the top bar");
    Region graphHeader = (Region) ((FxNoteGraph) field(fixture.app, "noteGraph")).node().lookup(".graph-heading-row");
    check(filesHeader.getHeight() == 52 && tabs.getHeight() == 52 && graphHeader.getHeight() == 52
        && filesHeader.localToScene(0, 0).getY() == tabs.localToScene(0, 0).getY()
        && filesHeader.localToScene(0, 0).getY() == graphHeader.localToScene(0, 0).getY(),
        "Files, tabs and Connections start on one 52 px band");
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
    Node tab = tabs.lookup(".tab:selected");
    var tabOrigin = tab.localToScene(0, 0).subtract(tabs.localToScene(0, 0));
    check(Math.abs(tabOrigin.getX() - 8) <= 1 && Math.abs(tabOrigin.getY() - 8) <= 1,
        "The first document tab uses the same 8 px top and left inset: " + tabOrigin);
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
    Button path = (Button) field(fixture.app, "folderControl");
    check(path.isVisible() && path.getText().equals(fixture.directory.toString())
        && path.getTooltip().getText().equals(path.getText()) && path.getGraphic() != null
        && path.getWidth() > 100 && path.getOnAction() != null,
        "One toolbar button exposes the full workspace path and opens the folder picker");
    String imagePath = System.getProperty("diyalamp.workspace.snapshot");
    if (imagePath != null && !imagePath.isBlank()) snapshot(fixture.stage, Path.of(imagePath));
    TextField search = (TextField) filesHeader.lookup(".sidebar-search-query-field");
    check(search.isVisible() && search.getHeight() == 36 && search.getFont().getSize() == 16
        && search.getBorder().getStrokes().getFirst().getRadii().getTopLeftHorizontalRadius() == 8,
        "The rounded sidebar search shares the first band's size and type");
    search.setText("Reference");
    check(!tree.isVisible() && sidebarSearch.hasQuery() && path.isVisible(),
        "Search replaces the sidebar list while leaving the top folder control in place");
    sidebarSearch.reset();
    check(tree.isVisible() && tree.getExpandedItemCount() == 2, "Clearing search restores the note tree");
    tree.getSelectionModel().select(0);
    fixture.layout();
    var rowActions = tree.lookupAll(".file-row-action");
    check(rowActions.stream().filter(Node::isVisible).count() == 6,
        "Both notes expose split, rename and delete without hovering");
    Button split = action(tree, "Open Draft.md side by side");
    split.fire(); fixture.layout();
    check(((javafx.scene.control.SplitPane) field(fixture.app, "split")).getItems().size() == 2,
        "The row action opens the note in the right editor pane");
    call(fixture.app, "singlePane", new Class<?>[0]);
    Button rename = action(tree, "Rename Draft.md");
    rename.fire(); fixture.layout();
    TextField renameInput = (TextField) tree.lookup(".file-rename-field");
    check(renameInput != null && renameInput.getSelectedText().equals("Draft")
        && fixture.stage.getScene().getFocusOwner() == renameInput,
        "Inline rename focuses the filename immediately; field=" + renameInput
            + ", selection=" + (renameInput == null ? "" : renameInput.getSelectedText())
            + ", owner=" + fixture.stage.getScene().getFocusOwner() + ", editing=" + tree.getEditingItem());
    renameInput.setText("Discard me");
    path.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
        false, false, false, false, true, false, false, false, false, false, null));
    check(tree.lookup(".file-rename-field") == null && Files.exists(fixture.directory.resolve("Draft.md")),
        "Clicking elsewhere cancels inline rename without changing the file");
    action(tree, "Rename Draft.md").fire(); fixture.layout();
    renameInput = (TextField) tree.lookup(".file-rename-field");
    renameInput.setText("Reference");
    renameInput.fireEvent(new ActionEvent());
    check(tree.lookup(".file-rename-field") == renameInput && Files.exists(fixture.directory.resolve("Draft.md")),
        "A rename collision leaves the inline field and both files intact");
    renameInput.setText("Renamed");
    renameInput.fireEvent(new ActionEvent()); fixture.layout();
    check(Files.exists(fixture.directory.resolve("Renamed.md")) && !Files.exists(fixture.directory.resolve("Draft.md")),
        "Enter saves the inline rename with the original extension");
    Button trash = action(tree, "Move Renamed.md to deleted notes");
    var trashRight = trash.localToScene(trash.getWidth(), 0).getX();
    check(trash.isVisible() && tree.localToScene(tree.getWidth(), 0).getX() - trashRight <= 18,
        "The selected note has a right-aligned delete action");
    Path draft = fixture.directory.resolve("Renamed.md");
    String original = Files.readString(draft);
    trash.fire();
    check(!Files.exists(draft) && tree.getExpandedItemCount() == 1, "The row action moves its note to deleted notes");
    call(fixture.app, "restoreLast", new Class<?>[0]);
    check(Files.readString(draft).equals(original), "The same note can be restored without changing its Markdown");
  }

  private static void compactWidth(Fixture fixture) throws Exception {
    ((FxNoteGraph) field(fixture.app, "noteGraph")).showingProperty().set(false);
    call(fixture.app, "layoutSidebars", new Class<?>[] {boolean.class}, false);
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

  private static Button action(TreeView<?> tree, String name) {
    return (Button) tree.lookupAll(".file-row-action").stream()
        .filter(node -> name.equals(node.getAccessibleText())).findFirst().orElseThrow();
  }

  private static void searchInWorkspace(Fixture fixture, AtomicReference<Throwable> failure, Runnable close) throws Exception {
    FxSidebarSearch sidebar = (FxSidebarSearch) field(fixture.app, "sidebarSearch");
    sidebar.focusQuery(FxWorkspaceSearch.Scope.CONTENTS);
    TextField query = (TextField) sidebar.queryControl().lookup(".text-field");
    query.setText("Return");
    TreeView<?> results = (TreeView<?>) sidebar.lookup(".sidebar-search-results");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
    PauseTransition checkResults = new PauseTransition(Duration.millis(40));
    checkResults.setOnFinished(event -> {
      if (results.getRoot() == null && System.nanoTime() < deadline) {
        checkResults.playFromStart();
        return;
      }
      try {
        check(results.getRoot() != null && results.getRoot().getChildren().size() == 1,
            "Typing in the sidebar reaches the background search worker and returns one grouped note");
        query.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        Object active = field(fixture.app, "active");
        FxWritingPane editor = (FxWritingPane) field(active, "editor");
        check(fixture.stage.getTitle().startsWith("Reference.md") && editor.area().getSelectedText().equals("Return"),
            "Enter opens the matching file and selects the exact text in the Markdown editor");
      } catch (Throwable error) { failure.compareAndSet(null, error); }
      finally { close.run(); }
    });
    checkResults.play();
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
      preferences.putBoolean("connectionsVisible", true);
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
