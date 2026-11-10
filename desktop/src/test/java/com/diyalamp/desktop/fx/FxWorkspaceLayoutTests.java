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
import javafx.beans.property.ObjectProperty;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.WritableImage;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
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
            current.layout(); composition(current);
            current.stage.setWidth(820);
            PauseTransition resize = new PauseTransition(Duration.millis(150));
            resize.setOnFinished(ignored -> Platform.runLater(() -> {
              try {
                current.layout(); compactWidth(current); nestedFileRow(current);
                folderControls(current);
                navigationAndPrivacy(current);
                sidebarAndTabNavigation(current);
                searchInWorkspace(current, failure, close);
              } catch (Throwable error) {
                failure.compareAndSet(null, error);
                close.run();
              }
            }));
            resize.play();
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
    for (String name : new String[] {"Workspace accent color", "Pomodoro timer, Focus, Ready", "Zen mode", "Toggle right sidebar", "Open folder"}) {
      check(toolbar.getItems().stream().filter(node -> name.equals(node.getAccessibleText())).count() == 1,
          "The shared toolbar must expose one accessible " + name + " control");
    }
    HBox footer = (HBox) field(fixture.app, "footer");
    check(footer.getChildren().stream().noneMatch(node -> "Pomodoro timer, Focus, Ready".equals(node.getAccessibleText())),
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
    Node graphPanel = ((FxNoteGraph) field(fixture.app, "noteGraph")).node();
    Region graphHeader = (Region) graphPanel.lookup(".graph-heading-row");
    check(filesHeader.getHeight() == 40 && tabs.getHeight() == 40 && graphHeader.getHeight() == 40
        && filesHeader.localToScene(0, 0).getY() == tabs.localToScene(0, 0).getY()
        && filesHeader.localToScene(0, 0).getY() == graphHeader.localToScene(0, 0).getY(),
        "Files, tabs and Connections start on one 40 px band");
    var bandDivider = (javafx.scene.shape.Line) fixture.stage.getScene().lookup("#workspace-top-divider");
    check(bandDivider.isMouseTransparent() && bandDivider.getStrokeWidth() == 1
        && bandDivider.getEndX() == fixture.stage.getScene().getWidth()
        && Math.abs(bandDivider.localToScene(0, 0).getY() - tabs.localToScene(0, 39.5).getY()) < .01,
        "A single one-pixel rule spans the workspace without intercepting the sidebar dividers");
    Region filesContext = (Region) explorerList.lookup(".files-search-row");
    Region documentLocation = (Region) tabs.getParent().lookup(".document-location");
    Region graphSummary = (Region) graphPanel.lookup(".graph-summary");
    double contextY = filesContext.localToScene(0, 0).getY();
    check(filesContext.getHeight() == 40 && documentLocation.getHeight() == 40 && graphSummary.getHeight() == 40
        && contextY == documentLocation.localToScene(0, 0).getY() && contextY == graphSummary.localToScene(0, 0).getY(),
        "Search, document breadcrumb, and current note share the second 40 px band");
    check(((Label) filesHeader.lookup(".workspace-section-title")).getText().equals("Files"), "The files header is labeled");
    var contextDivider = (javafx.scene.shape.Line) fixture.stage.getScene().lookup("#workspace-context-divider");
    check(contextDivider.isMouseTransparent() && contextDivider.getEndX() == fixture.stage.getScene().getWidth()
        && Math.abs(contextDivider.localToScene(0, 0).getY() - contextY - 39.5) < .01,
        "A single shared divider underlines all three context headers");
    paintedRows(tree, ".tree-cell", 2, 2);
    graphRows(fixture);
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
    Region tab = (Region) tabs.lookup(".tab:selected");
    Label tabTitle = (Label) tab.lookup(".tab-box .label");
    var tabIcon = tabTitle.getGraphic();
    var tabText = tabTitle.lookup(".text");
    check(tabTitle.getGraphicTextGap() == 8 && tabText.localToScene(tabText.getLayoutBounds()).getMinX()
            - tabIcon.localToScene(tabIcon.getLayoutBounds()).getMaxX() >= 7,
        "The tab title must have room to breathe beside its document icon");
    var tabOrigin = tab.localToScene(0, 0).subtract(tabs.localToScene(0, 0));
    check(Math.abs(tabOrigin.getX()) <= 1 && Math.abs(tabOrigin.getY()) <= 1 && tab.getHeight() == tabs.getHeight(),
        "The first document tab must touch the left, top, and bottom of the 40 px strip: " + tabOrigin);
    Region tabClose = (Region) tab.lookup(".close-button");
    double tabCenter = tab.localToScene(0, tab.getHeight() / 2).getY();
    check(tabClose.getHeight() == 28 && Math.abs(tabClose.localToScene(0, 14).getY() - tabCenter) <= 1,
        "The close target must retain its height and stay centered in the taller tab");
    Region explorer = (Region) field(fixture.app, "explorerClip");
    Region pane = (Region) field(primary, "container");
    check(explorer.localToScene(0, 0).getX() == 0 && Math.abs(pane.localToScene(0, 0).getX() - explorer.getWidth()) <= 7,
        "The single files sidebar must lead directly into the editor without a second activity rail");
    Label brand = (Label) toolbar.lookup(".workspace-brand");
    double center = brand.localToScene(0, brand.getHeight() / 2).getY();
    Region previous = null;
    for (Node node : toolbar.getItems()) {
      if (!(node instanceof javafx.scene.control.Labeled control)) { previous = null; continue; }
      Insets padding = control == brand ? new Insets(0, 8, 0, 8) : new Insets(8);
      check(control.getHeight() == 36 && control.getPadding().equals(padding)
          && control.getFont().getSize() == 16
          && control.getFont().getName().equals(control == brand ? "Inter SemiBold" : "Inter Regular")
          && Math.abs(control.localToScene(0, 18).getY() - center) <= 1,
          "The wordmark and toolbar controls share exact height, inset, type and center: " + control);
      if (previous != null) check(Math.abs(control.localToScene(0, 0).getX()
          - previous.localToScene(previous.getWidth(), 0).getX() - 4) <= 1,
          "Neighboring controls have a 4 px gap");
      previous = control;
    }
    Region sidebarToggle = (Region) toolbar.getItems().get(2);
    StackPane brandGraphic = (StackPane) brand.getGraphic();
    check(brandGraphic.getChildren().getFirst() instanceof ImageView icon && icon.getImage() == FxTheme.brandMark()
            && icon.getImage().getWidth() == 72 && icon.getImage().getHeight() == 72 && icon.isSmooth()
            && brandGraphic.getWidth() == sidebarToggle.getWidth()
            && brandGraphic.getHeight() == sidebarToggle.getHeight()
            && Math.abs(centerY(brandGraphic) - centerY(sidebarToggle)) <= 1 && brand.getText().equals("diyalamp"),
        "The Retina website mark must fill a centered slot the same size as the sidebar toggle");
    Node shine = brandGraphic.lookup("#toolbar-brand-shine");
    check(!shine.isVisible() && shine.getParent().isMouseTransparent(), "The idle shine must be hidden and pass pointer events through");
    String reducedMotion = System.getProperty("diyalamp.reduceMotion");
    try {
      System.setProperty("diyalamp.reduceMotion", "true");
      brandGraphic.getOnMouseEntered().handle(null);
      check(!shine.isVisible(), "Reduced motion must suppress the toolbar shine");
    } finally {
      if (reducedMotion == null) System.clearProperty("diyalamp.reduceMotion");
      else System.setProperty("diyalamp.reduceMotion", reducedMotion);
    }
    if (!FxMotion.reduced()) {
      brandGraphic.getOnMouseEntered().handle(null);
      check(shine.isVisible() && brandGraphic.getBoundsInParent().getWidth() == 36,
          "Hover must start the shine without resizing the wordmark");
    }
    check("Toggle files sidebar".equals(toolbar.getItems().get(2).getAccessibleText()) && toolbar.getItems().get(3) == brand,
        "The sidebar toggle precedes the lamp and diyalamp name");
    ToggleButton filesToggle = (ToggleButton) field(fixture.app, "filesControl");
    ToggleButton connectionsToggle = (ToggleButton) field(fixture.app, "connectionsControl");
    check(java.util.List.of(filesToggle, connectionsToggle).stream().allMatch(toggle -> toggle.isSelected()
        && toggle.getBackground().getFills().size() == 1
        && ((Color) toggle.getBackground().getFills().getFirst().getFill()).getSaturation() < .2),
        "Both open sidebars use the same quiet selected appearance");
    var toolbarDividers = toolbar.lookupAll(".workspace-toolbar-divider");
    check(toolbarDividers.size() == 2 && toolbarDividers.stream().allMatch(divider -> divider.isMouseTransparent()
        && divider.getLayoutBounds().getWidth() == 1 && divider.getLayoutBounds().getHeight() == 16
        && Math.abs(centerY(divider) - center) <= 1), "Toolbar groups have slim centered dividers");
    Button path = (Button) field(fixture.app, "folderControl");
    check(path.isVisible() && path.getText().equals(fixture.directory.toString())
        && path.getTooltip().getText().equals(fixture.directory.toString()) && path.getGraphic() != null
        && path.getWidth() > 100 && path.getOnAction() != null,
        "One toolbar button exposes the full workspace path and opens the folder picker");
    String imagePath = System.getProperty("diyalamp.workspace.snapshot");
    if (imagePath != null && !imagePath.isBlank()) snapshot(fixture.stage, Path.of(imagePath));
    Region searchChrome = (Region) filesContext.lookup(".sidebar-search-query");
    TextField search = (TextField) searchChrome.lookup(".sidebar-search-query-field");
    check(search.isVisible() && searchChrome.getHeight() == 32 && search.getFont().getSize() == 16
        && searchChrome.getBorder().getStrokes().getFirst().getRadii().getTopLeftHorizontalRadius() == 8,
        "The integrated search shares the workspace's control size and type");
    search.setText("Reference");
    check(!tree.isVisible() && sidebarSearch.hasQuery() && path.isVisible(),
        "Search replaces the sidebar list while leaving the top folder control in place");
    sidebarSearch.reset();
    check(tree.isVisible() && tree.getExpandedItemCount() == 2, "Clearing search restores the note tree");
    tree.getSelectionModel().select(0);
    fixture.layout();
    Region selectedRow = (Region) tree.lookup(".tree-cell:filled:selected");
    check(selectedRow.getBackground().getFills().size() == 1
        && ((Color) selectedRow.getBackground().getFills().getFirst().getFill()).getOpacity() < .2,
        "Selected notes use one quiet translucent fill across both workspace surfaces");
    var rowActions = tree.lookupAll(".file-row-action");
    var headerActions = filesHeader.lookupAll(".file-row-action");
    var headerCenters = headerActions.stream().map(node -> node.localToScene(node.getLayoutBounds()).getCenterX())
        .sorted().toList();
    var rowCenters = rowActions.stream().filter(node -> node.getAccessibleText().contains("Draft.md"))
        .map(node -> node.localToScene(node.getLayoutBounds()).getCenterX()).sorted().toList();
    check(headerActions.size() == 2 && headerActions.stream().allMatch(node ->
            node.getLayoutBounds().getWidth() == 28 && node.getLayoutBounds().getHeight() == 28)
            && Math.abs(headerCenters.getLast() - rowCenters.getLast()) <= 1,
        "Creation and file actions must share dimensions and a right edge: " + headerCenters + " / " + rowCenters);
    var noteBounds = selectedRow.localToScene(selectedRow.getLayoutBounds());
    double iconLeft = iconInk(selectedRow.lookup(".file-kind")).stream().mapToDouble(bounds -> bounds.getMinX()).min().orElseThrow();
    double actionRight = iconInk(action(tree, "Move Draft.md to deleted notes").getGraphic()).stream()
        .mapToDouble(bounds -> bounds.getMaxX()).max().orElseThrow();
    check(iconLeft - noteBounds.getMinX() <= 23 && noteBounds.getMaxX() - actionRight <= 16,
        "The file row uses a compact leading disclosure slot and a compact action gutter");
    double searchCenter = centerY(search);
    Node magnifier = ((Labeled) searchChrome.lookup(".sidebar-search-query-label")).getGraphic();
    Node advanced = sidebarSearch.optionsButton();
    check(Math.abs(inkCenterY(magnifier) - searchCenter) <= 1
        && Math.abs(centerY(advanced) - searchCenter) <= 1
        && Math.abs(inkCenterY(((Labeled) advanced).getGraphic()) - searchCenter) <= 1,
        "The magnifier and search options share the search field's centerline");
    sidebarSearch.optionsButton().fire();
    fixture.layout();
    var searchOptions = sidebarSearch.lookupAll(".sidebar-search-option");
    var searchModes = sidebarSearch.lookupAll(".sidebar-search-mode");
    check(searchOptions.size() == 4 && searchModes.size() == 2
        && searchOptions.stream().allMatch(node -> node.getLayoutBounds().getWidth() == 28
            && node.getLayoutBounds().getHeight() == 28)
        && java.util.stream.Stream.concat(searchOptions.stream(), searchModes.stream()).allMatch(node -> {
          Node graphic = ((Labeled) node).getGraphic();
          return graphic.getLayoutBounds().getWidth() == 16 && graphic.getLayoutBounds().getHeight() == 16
              && Math.abs(inkCenterY(graphic) - centerY(node)) <= 1;
        }), "Advanced search shares the workspace icon size, centered artwork, and compact action targets");
    sidebarSearch.optionsButton().fire();
    fixture.layout();
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
    check(trash.isVisible() && tree.localToScene(tree.getWidth(), 0).getX() - trashRight <= 21,
        "The selected note has a right-aligned delete action");
    Path draft = fixture.directory.resolve("Renamed.md");
    String original = Files.readString(draft);
    trash.fire();
    check(!Files.exists(draft) && tree.getExpandedItemCount() == 1, "The row action moves its note to deleted notes");
    call(fixture.app, "restoreLast", new Class<?>[0]);
    check(Files.readString(draft).equals(original), "The same note can be restored without changing its Markdown");
  }

  @SuppressWarnings("unchecked")
  private static void compactWidth(Fixture fixture) throws Exception {
    ((FxNoteGraph) field(fixture.app, "noteGraph")).showingProperty().set(false);
    call(fixture.app, "layoutSidebars", new Class<?>[] {boolean.class}, false);
    Button folder = (Button) field(fixture.app, "folderControl");
    Path longPath = Path.of("/workspace" + "/Department Archive".repeat(3), "Database Notes");
    var folderPath = (ObjectProperty<Path>) field(fixture.app, "workspacePath");
    folderPath.set(longPath);
    FxPomodoro pomodoro = (FxPomodoro) field(fixture.app, "pomodoro");
    fixture.layout();
    check(fixture.stage.getScene().getWidth() <= 822, "Compact checks run after the scene reaches its minimum width");
    Region sidebar = (Region) field(fixture.app, "explorer");
    Region queryChrome = (Region) sidebar.lookup(".sidebar-search-query");
    Region queryField = (Region) queryChrome.lookup(".sidebar-search-query-field");
    check(queryChrome.getWidth() >= 144 && queryField.getWidth() >= 80
        && sidebar.lookupAll(".files-heading-row .file-row-action").size() == 2,
        "At minimum window width the compact controls must leave room to type in the search field");
    Node toggle = (Node) field(fixture.app, "connectionsControl");
    var readyBounds = toggle.localToScene(toggle.getBoundsInLocal());
    check(pomodoro.button().getWidth() <= 160 && toggle.isVisible()
        && readyBounds.getMaxX() <= fixture.stage.getScene().getWidth(),
        "Showing the ready phase and countdown must leave the right-sidebar toggle visible at minimum width");
    ((Button) pomodoro.popover().getContentNode().lookup("#pomodoro-start")).fire();
    fixture.layout();
    Region pane = (Region) field(field(fixture.app, "primary"), "container");
    check(pane.getWidth() >= 500, "The default closed inspector must leave a useful writing area at the minimum window width");
    check(pane.localToScene(pane.getWidth(), 0).getX() <= fixture.stage.getScene().getWidth() + 1,
        "The editor must stay within the native window rather than overflowing horizontally");
    var bounds = toggle.localToScene(toggle.getBoundsInLocal());
    ToolBar toolbar = (ToolBar) field(fixture.app, "toolbar");
    Node overflow = toolbar.lookup(".tool-bar-overflow-button");
    Label brand = (Label) toolbar.lookup(".workspace-brand");
    check(toggle.isVisible() && toggle.getScene() == fixture.stage.getScene()
        && bounds.getMinX() >= 0 && bounds.getMaxX() <= fixture.stage.getScene().getWidth()
        && (overflow == null || !overflow.isVisible()) && brand.getGraphic().getBoundsInParent().getWidth() == 36,
        "The full-size wordmark and sidebar toggle stay on the bar with a long folder and running timer");
    check(folder.getText().equals("Database Notes") && folder.getTooltip().getText().equals(longPath.toString())
        && folder.getAccessibleHelp().equals(longPath.toString()),
        "Compact windows keep the workspace name visible and expose its complete path on hover and to assistive tools");
    folderPath.set(fixture.directory);
    fixture.layout();
    String longFilename = "Long fictional name ".repeat(5).strip() + ".md";
    Files.writeString(fixture.directory.resolve(longFilename), "Test note.");
    call(fixture.app, "refreshTree", new Class<?>[0]); fixture.layout();
    TreeView<?> tree = (TreeView<?>) field(fixture.app, "tree");
    Button trash = action(tree, "Move " + longFilename + " to deleted notes");
    check(trash.localToScene(trash.getWidth(), 0).getX() <= tree.localToScene(tree.getWidth(), 0).getX(),
        "Long filenames must leave the rightmost row action in view");
    HBox metrics = (HBox) field(fixture.app, "metrics");
    var stats = metrics.localToScene(metrics.getBoundsInLocal());
    check(stats.getMinX() >= 0 && stats.getMaxX() <= fixture.stage.getScene().getWidth(),
        "All four status statistics must fit within the window");
  }

  private static Button action(TreeView<?> tree, String name) {
    return (Button) tree.lookupAll(".file-row-action").stream()
        .filter(node -> name.equals(node.getAccessibleText())).findFirst().orElseThrow();
  }

  private static void nestedFileRow(Fixture fixture) throws Exception {
    Path folder = Files.createDirectory(fixture.directory.resolve("Folder"));
    Path second = Files.createDirectory(fixture.directory.resolve("Second folder"));
    Path child = Files.createDirectory(second.resolve("Nested folder"));
    Path note = Files.writeString(folder.resolve("A deliberately long filename that must leave room for all three actions.md"), "# Nested note");
    Files.writeString(child.resolve("Nested reference.md"), "# Another note");
    call(fixture.app, "refreshTree", new Class<?>[0]);
    TreeView<?> tree = (TreeView<?>) field(fixture.app, "tree");
    folderItem(tree.getRoot(), folder).setExpanded(true);
    folderItem(tree.getRoot(), second).setExpanded(true);
    folderItem(folderItem(tree.getRoot(), second), child).setExpanded(true);
    call(fixture.app, "refreshTree", new Class<?>[0]);
    check(folderItem(tree.getRoot(), folder).isExpanded() && folderItem(tree.getRoot(), second).isExpanded()
        && folderItem(folderItem(tree.getRoot(), second), child).isExpanded(),
        "Refreshing the explorer preserves multiple open branches and their nested expansion");
    folderItem(tree.getRoot(), second).setExpanded(false);
    call(fixture.app, "refreshTree", new Class<?>[0]);
    check(!folderItem(tree.getRoot(), second).isExpanded() && folderItem(tree.getRoot(), folder).isExpanded(),
        "Refreshing a collapsed branch keeps it closed without collapsing another open branch");
    fixture.layout();
    var cells = tree.lookupAll(".tree-cell").stream().filter(TreeCell.class::isInstance).map(TreeCell.class::cast).toList();
    var nested = cells.stream().filter(cell -> note.equals(cell.getItem())).findFirst().orElseThrow();
    var topLevel = cells.stream().filter(cell -> fixture.directory.resolve("Reference.md").equals(cell.getItem())).findFirst().orElseThrow();
    var folderRow = cells.stream().filter(cell -> folder.equals(cell.getItem())).findFirst().orElseThrow();
    check(Math.abs(centerY(folderRow.getDisclosureNode()) - centerY(folderRow.lookup(".file-kind"))) <= 1,
        "Folder chevrons stay centered with folder icons across the full row height");
    Node nestedIcon = nested.lookup(".file-kind"), topLevelIcon = topLevel.lookup(".file-kind");
    check(nestedIcon.localToScene(0, 0).getX() - topLevelIcon.localToScene(0, 0).getX() == 12,
        "Nested note content uses one compact indentation step");
    Button split = action(tree, "Open " + note.getFileName() + " side by side");
    Button remove = action(tree, "Move " + note.getFileName() + " to deleted notes");
    Button topRemove = action(tree, "Move Reference.md to deleted notes");
    Node title = nested.lookup(".label");
    check(Math.abs(remove.localToScene(remove.getWidth(), 0).getX()
            - topRemove.localToScene(topRemove.getWidth(), 0).getX()) <= 1
        && title.localToScene(title.getLayoutBounds()).getMaxX() < split.localToScene(0, 0).getX(),
        "A long nested title must leave the action strip aligned and clickable");
  }

  private static TreeItem<?> folderItem(TreeItem<?> parent, Path path) {
    return parent.getChildren().stream().filter(item -> path.equals(item.getValue())).findFirst().orElseThrow();
  }

  private static void folderControls(Fixture fixture) throws Exception {
    TreeView<?> tree = (TreeView<?>) field(fixture.app, "tree");
    Path folder = fixture.directory.resolve("Folder");
    Class<?>[] treePath = {TreeItem.class, Path.class};
    call(fixture.app, "selectTree", treePath, tree.getRoot(), folder);
    fixture.layout();
    Button create = action(tree, "New note in Folder");
    check(create.getParent().isVisible() && action(tree, "Rename folder Folder").getParent().isVisible()
        && action(tree, "Move folder Folder to deleted items").getParent().isVisible(),
        "The selected folder exposes creation, inline rename, and recoverable delete");
    acceptDialog(create, "New note", "New child");
    check(Files.isRegularFile(folder.resolve("New child.md")), "The folder's creation action writes inside that folder");
    call(fixture.app, "selectTree", treePath, tree.getRoot(), folder);
    fixture.layout();
    action(tree, "Rename folder Folder").fire();
    TextField rename = (TextField) tree.lookup(".file-rename-field");
    check(rename != null && rename.getText().equals("Folder"), "The folder name is selected inline for editing");
    rename.setText("Renamed folder");
    rename.fireEvent(new ActionEvent());
    fixture.layout();
    Path renamed = fixture.directory.resolve("Renamed folder");
    Path child = renamed.resolve("New child.md");
    Object active = field(fixture.app, "active");
    check(!Files.exists(folder) && Files.isRegularFile(child) && child.equals(field(field(active, "session"), "path")),
        "Renaming a folder retains its contents and updates open note paths");
    acceptDialog(action(tree, "Move folder Renamed folder to deleted items"), "Move folder to deleted items?", null);
    check(!Files.exists(renamed), "The folder action moves the whole directory out of the explorer");
    call(fixture.app, "restoreLast", new Class<?>[0]);
    check(Files.isRegularFile(child) && folderItem(tree.getRoot(), renamed).isExpanded(),
        "Restore returns the folder and nested notes and reveals them in the explorer");
  }

  private static void acceptDialog(Button action, String title, String name) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.runLater(() -> {
      DialogPane pane = null;
      try {
        pane = Window.getWindows().stream().filter(Window::isShowing).map(window -> window.getScene().getRoot())
            .filter(DialogPane.class::isInstance).map(DialogPane.class::cast).findFirst().orElseThrow();
        check(title.equals(pane.getHeaderText()), "The folder action opens the expected dialog");
        if (name != null) ((TextField) pane.lookup(".text-field")).setText(name);
        ((Button) pane.lookupButton(ButtonType.OK)).fire();
      } catch (Throwable error) {
        failure.set(error);
        if (pane != null && pane.lookupButton(ButtonType.CANCEL) instanceof Button cancel) cancel.fire();
      }
    });
    action.fire();
    if (failure.get() != null) throw new AssertionError("Folder action dialog failed", failure.get());
  }

  private static void navigationAndPrivacy(Fixture fixture) throws Exception {
    Path reference = fixture.directory.resolve("Reference.md");
    Path text = Files.writeString(fixture.directory.resolve("Transcript.txt"), "# A plain-text preview");
    Class<?>[] open = {Path.class, boolean.class};
    call(fixture.app, "open", open, reference, false);
    call(fixture.app, "refreshTree", new Class<?>[0]);
    call(fixture.app, "open", open, text, false);
    fixture.layout();
    Object preview = field(fixture.app, "active");
    check(field(preview, "preview") instanceof FxFilePreview && field(preview, "editor") == null,
        "A workspace text file opens in an actual read-only tab");
    ToolBar toolbar = (ToolBar) field(fixture.app, "toolbar");
    Button back = toolbarButton(toolbar, "Previous document"), forward = toolbarButton(toolbar, "Next document");
    check(!back.isDisabled(), "Opening files makes Back available");
    back.fire();
    check(fixture.stage.getTitle().startsWith("Reference.md") && !forward.isDisabled(),
        "Back opens the previous document and makes Forward available");
    forward.fire();
    check(field(fixture.app, "active") == preview, "Forward restores the existing preview tab");
    ToggleButton privacy = (ToggleButton) toolbar.getItems().stream()
        .filter(node -> "Privacy Mode".equals(node.getAccessibleText())).findFirst().orElseThrow();
    Button folder = (Button) field(fixture.app, "folderControl");
    Node workspace = (Node) field(fixture.app, "workspaceLayer");
    Node footer = (Node) field(fixture.app, "footerClip");
    privacy.fire();
    check(privacy.isSelected() && workspace.getScene() == null && footer.getScene() == null && !folder.isVisible()
        && fixture.stage.getTitle().equals("Privacy Mode · diyalamp") && back.isDisabled() && forward.isDisabled(),
        "Privacy Mode removes filenames, open content, status details, and navigation from the window");
    privacy.fire();
    check(workspace.getScene() == fixture.stage.getScene() && folder.isVisible() && field(fixture.app, "active") == preview
        && fixture.stage.getTitle().startsWith("Transcript.txt"), "Leaving Privacy Mode restores the same document tab");
    call(fixture.app, "open", open, reference, false);
  }

  private static Button toolbarButton(ToolBar toolbar, String name) {
    return (Button) toolbar.getItems().stream().filter(node -> name.equals(node.getAccessibleText())).findFirst().orElseThrow();
  }

  private static void sidebarAndTabNavigation(Fixture fixture) throws Exception {
    TreeView<?> tree = (TreeView<?>) field(fixture.app, "tree");
    TabLine tabs = (TabLine) field(field(fixture.app, "primary"), "tabs");
    Object reference = field(fixture.app, "active");
    int historyIndex = (int) field(fixture.app, "historyIndex"), tabCount = tabs.getTabs().size();
    tree.requestFocus();
    tree.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.DOWN, false, false, false, false));
    check(tree.getSelectionModel().getSelectedItem().getValue().equals(fixture.directory.resolve("Renamed.md"))
        && field(fixture.app, "active") == reference && tabs.getTabs().size() == tabCount
        && (int) field(fixture.app, "historyIndex") == historyIndex,
        "Arrow keys select a file without opening it or adding to document history");
    tree.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    check(fixture.stage.getTitle().startsWith("Renamed.md") && tree.getEditingItem() == null,
        "Enter opens the selected note without starting an inline rename");
    tree.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.F2, false, false, false, false));
    TextField rename = (TextField) tree.lookup(".file-rename-field");
    check(rename != null && rename.getSelectedText().equals("Renamed"), "F2 still starts inline rename");
    rename.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));

    fixture.layout();
    Path referencePath = fixture.directory.resolve("Reference.md");
    Node row = tree.lookupAll(".tree-cell").stream()
        .filter(node -> node instanceof TreeCell<?> cell && referencePath.equals(cell.getItem()))
        .findFirst().orElseThrow();
    row.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, MouseButton.PRIMARY, 1,
        false, false, false, false, false, false, false, false, false, true, null));
    check(field(fixture.app, "active") == reference && tree.getSelectionModel().getSelectedItem().getValue().equals(referencePath),
        "A row click opens the existing note and synchronizes the sidebar selection");

    Class<?>[] open = {Path.class, boolean.class};
    call(fixture.app, "open", open, fixture.directory.resolve("Transcript.txt"), true);
    Object beside = field(fixture.app, "active");
    historyIndex = (int) field(fixture.app, "historyIndex");
    var backgroundTab = tabs.getSelectionModel().getSelectedItem();
    tabs.getTabs().remove(backgroundTab);
    backgroundTab.getOnClosed().handle(new javafx.event.Event(backgroundTab, null, atlantafx.base.controls.Tab.CLOSED_EVENT));
    check(field(fixture.app, "active") == beside && fixture.stage.getTitle().startsWith("Transcript.txt")
        && (int) field(fixture.app, "historyIndex") == historyIndex,
        "Closing a tab in the background pane preserves the current document and history");
    var location = (FxDocumentLocation) field(field(fixture.app, "primary"), "breadcrumb");
    var replacement = tabs.getSelectionModel().getSelectedItem();
    check(replacement != null && location.getTooltip().getText().endsWith(replacement.getText()),
        "The background pane breadcrumb follows the tab revealed by closing a tab");

    TabLine secondary = (TabLine) field(field(fixture.app, "secondary"), "tabs");
    for (int remaining = tabs.getTabs().size() + secondary.getTabs().size(); remaining > 0; remaining--)
      call(fixture.app, "closeActive", new Class<?>[0]);
    check(tabs.getTabs().isEmpty() && secondary.getTabs().isEmpty() && fixture.stage.getTitle().equals("diyalamp"),
        "Closing the last document clears the native window title");
    call(fixture.app, "open", open, referencePath, false);
  }

  private static double centerY(Node node) {
    return node.localToScene(node.getLayoutBounds()).getCenterY();
  }

  private static double inkCenterY(Node icon) {
    var ink = iconInk(icon);
    double top = ink.stream().mapToDouble(bounds -> bounds.getMinY()).min().orElseThrow();
    double bottom = ink.stream().mapToDouble(bounds -> bounds.getMaxY()).max().orElseThrow();
    return (top + bottom) / 2;
  }

  private static java.util.List<javafx.geometry.Bounds> iconInk(Node icon) {
    return icon.lookupAll(".icon-stroke").stream().map(shape -> shape.localToScene(shape.getBoundsInLocal())).toList();
  }

  @SuppressWarnings("unchecked")
  private static void graphRows(Fixture fixture) throws Exception {
    FxNoteGraph graph = (FxNoteGraph) field(fixture.app, "noteGraph");
    var list = (ListView<Path>) field(graph, "incoming");
    var original = java.util.List.copyOf(list.getItems());
    try {
      list.getItems().setAll(fixture.directory.resolve("Draft.md"), fixture.directory.resolve("Reference.md"));
      fixture.layout();
      paintedRows(list, ".list-cell", 2, 2);
      check(list.getPadding().equals(new Insets(2, 8, 2, 8)),
          "Connection lists retain their 8 px side insets below full-width headings");
    } finally { list.getItems().setAll(original); }
  }

  private static void paintedRows(Region list, String selector, double outerPadding) {
    paintedRows(list, selector, outerPadding, 4);
  }

  private static void paintedRows(Region list, String selector, double outerPadding, double rowInset) {
    var rows = list.lookupAll(selector).stream()
        .filter(node -> node instanceof IndexedCell<?> cell && !cell.isEmpty())
        .map(node -> (IndexedCell<?>) node).sorted(java.util.Comparator.comparingInt(IndexedCell::getIndex))
        .limit(2).toList();
    check(rows.size() == 2 && list.getPadding().getTop() == outerPadding && list.getPadding().getBottom() == outerPadding,
        "The list must expose two painted rows and the expected wrapper spacing: " + list.getStyleClass());
    var first = rows.getFirst(); var second = rows.getLast();
    Insets firstPaint = first.getBackground().getFills().getFirst().getInsets();
    Insets secondPaint = second.getBackground().getFills().getFirst().getInsets();
    double listTop = list.localToScene(0, 0).getY();
    double firstTop = first.localToScene(0, firstPaint.getTop()).getY();
    double firstBottom = first.localToScene(0, first.getHeight() - firstPaint.getBottom()).getY();
    double secondTop = second.localToScene(0, secondPaint.getTop()).getY();
    check(firstPaint.equals(new Insets(rowInset, 0, rowInset, 0)) && secondPaint.equals(firstPaint)
        && Math.abs(firstTop - listTop - (outerPadding + rowInset)) < .01
        && Math.abs(secondTop - firstBottom - 2 * rowInset) < .01,
        "Rows share consistent paint insets and gaps: " + list.getStyleClass());
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
        fixture.layout(); paintedRows(results, ".tree-cell", 4);
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
      app.start(stage);
      stage.setWidth(1920);
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
