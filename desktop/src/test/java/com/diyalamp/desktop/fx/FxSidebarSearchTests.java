package com.diyalamp.desktop.fx;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;

/** Exercises the sidebar's normal keyboard, filtering, and result rendering paths. */
public final class FxSidebarSearchTests {
  private static int checks;

  private static void check(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  public static void main(String[] args) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      try {
        System.setProperty("diyalamp.reduceMotion", "true");
        FxTheme.setup();
        search();
      } catch (Throwable error) {
        failure.set(error);
      } finally {
        done.countDown();
      }
    });
    if (!done.await(15, TimeUnit.SECONDS)) throw new AssertionError("Search tests timed out");
    Platform.exit();
    if (failure.get() != null) throw new AssertionError("Sidebar search regression", failure.get());
    System.out.println("Passed " + checks + " sidebar search checks.");
  }

  private static void search() throws ReflectiveOperationException {
    FxSidebarSearch panel = new FxSidebarSearch();
    AtomicReference<FxWorkspaceSearch.Query> request = new AtomicReference<>();
    AtomicReference<FxSidebarSearch.OpenResult> opened = new AtomicReference<>();
    AtomicInteger dismissals = new AtomicInteger();
    panel.setOnSearch(request::set);
    panel.setOnOpen(opened::set);
    panel.setOnDismiss(dismissals::incrementAndGet);
    panel.setRowActions(path -> {
      Button action = new Button("…");
      action.setAccessibleText("Actions for " + path.getFileName());
      action.getStyleClass().add("fixture-row-action");
      return action;
    });
    Path rootPath = Path.of("/fictional-workspace");
    Path notePath = rootPath.resolve("Research/Original note.md");
    panel.setWorkspaceRoot(rootPath);

    HBox header = new HBox(panel.queryControl());
    header.getStyleClass().addAll("workspace-band", "files-search-row");
    header.setAlignment(Pos.CENTER_LEFT);
    HBox.setHgrow(panel.queryControl(), Priority.ALWAYS);
    VBox.setVgrow(panel, Priority.ALWAYS);
    TreeView<String> files = new TreeView<>(new TreeItem<>("Fictional file tree"));
    files.setPrefHeight(480);
    files.visibleProperty().bind(panel.hasQueryProperty().not());
    files.managedProperty().bind(files.visibleProperty());
    VBox.setVgrow(files, Priority.ALWAYS);
    VBox workspace = new VBox(header, panel, files);
    workspace.getStyleClass().add("workspace-root");
    workspace.getStylesheets().add(FxSidebarSearchTests.class.getResource("/fx-workspace.css").toExternalForm());
    FxTheme.accent(workspace, FxTheme.DEFAULT_ACCENT);
    Stage stage = new Stage();
    stage.setScene(new Scene(workspace, 272, 520));
    stage.show();
    try {
      workspace.applyCss();
      workspace.layout();
      Region chrome = panel.queryControl();
      TextField input = (TextField) chrome.lookup(".sidebar-search-query-field");
      ToggleButton advanced = panel.optionsButton();
      var options = panel.lookup(".sidebar-search-options");
      HBox tools = (HBox) chrome.lookup(".sidebar-search-tools");
      Button clear = (Button) tools.lookup(".sidebar-search-clear");
      check(header.getHeight() == 40 && chrome.getHeight() == 32 && chrome.getWidth() >= 240
          && input.getWidth() >= 160,
          "The query fills the compact 40-pixel search band");
      Label queryLabel = (Label) input.queryAccessibleAttribute(AccessibleAttribute.LABELED_BY);
      check(queryLabel != null && queryLabel.getGraphic().lookup(".icon-stroke") != null
          && "Search filenames".equals(queryLabel.queryAccessibleAttribute(AccessibleAttribute.TEXT))
          && tools.getParent() == input.getParent() && tools.getChildren().contains(advanced)
          && !clear.isVisible() && !clear.isManaged(),
          "The integrated surface separates the named field and its controls for accessibility");
      check(advanced.getHeight() == 28 && advanced.getWidth() == 28
              && advanced.getGraphic().lookup(".icon-stroke") != null
              && "Search options".equals(advanced.getAccessibleText()),
          "The options icon must share the compact file-action size and have an accessible name");
      check(!options.isVisible() && !options.isManaged(), "Advanced search must start collapsed");
      check(!panel.hasQuery() && files.isVisible(), "The empty query must leave the files view visible");
      panel.focusQuery();
      press(input, KeyCode.TAB);
      check(workspace.getScene().getFocusOwner() == advanced,
          "Tab from an empty search field must reach Search options");
      panel.focusQuery();
      input.setText("Original");
      check(clear.isVisible() && clear.isManaged() && "Clear search".equals(clear.getAccessibleText()),
          "A nonempty query must expose a separate clear button");
      check("Original".equals(input.queryAccessibleAttribute(AccessibleAttribute.TEXT)),
          "Assistive technology must receive the typed value instead of the field name");
      press(input, KeyCode.TAB);
      check(workspace.getScene().getFocusOwner() == clear, "Tab must reach Clear when the field contains a query");
      press(clear, KeyCode.TAB);
      check(workspace.getScene().getFocusOwner() == advanced, "Tab must reach Search options after Clear");
      check(!options.isVisible() && request.get().scope() == FxWorkspaceSearch.Scope.FILENAMES,
          "Focusing and typing in the collapsed field must perform basic filename search without expanding options");
      input.clear();
      advanced.fire();
      check(advanced.isSelected() && "Hide search options".equals(advanced.getTooltip().getText()),
          "The options icon must expose its expanded state");
      double compactTreeY = treeBelowOptions(workspace, panel, files);
      var modeIcons = panel.lookupAll(".sidebar-search-mode");
      var filterIcons = panel.lookupAll(".sidebar-search-option");
      check(modeIcons.size() == 2 && modeIcons.stream().map(Labeled.class::cast).map(Labeled::getText)
              .collect(java.util.stream.Collectors.toSet()).equals(java.util.Set.of("Files", "Text"))
              && filterIcons.size() == 4
              && java.util.stream.Stream.concat(modeIcons.stream(), filterIcons.stream()).allMatch(node ->
                  node instanceof Labeled item && item.getGraphic() != null
                      && item.getGraphic().lookup(".icon-stroke") != null && item.getAccessibleText() != null),
          "Scope choices must have readable labels, and all options must have an icon and an accessible name");
      scopeIndicator(panel);
      toggle(panel, "Include and exclude files").fire();
      check(treeBelowOptions(workspace, panel, files) > compactTreeY + 36,
          "Expanding the include and exclude fields must make room above the files view");
      toggle(panel, "Include and exclude files").fire();
      check(Math.abs(treeBelowOptions(workspace, panel, files) - compactTreeY) <= 1,
          "Collapsing filters must return their space to the files view");

      input.setText("Original");
      check(request.get().scope() == FxWorkspaceSearch.Scope.FILENAMES,
          "The default search must start with filenames");
      check(!files.isVisible(), "Typing a query must switch from files to search results");
      panel.focusQuery(FxWorkspaceSearch.Scope.CONTENTS);
      check(request.get().scope() == FxWorkspaceSearch.Scope.CONTENTS
          && "Search note contents".equals(queryLabel.queryAccessibleAttribute(AccessibleAttribute.TEXT))
          && "Original".equals(input.queryAccessibleAttribute(AccessibleAttribute.TEXT)),
          "Switching to content search must refresh the query and update its label separately");

      toggle(panel, "Match case").fire();
      toggle(panel, "Match whole word").fire();
      toggle(panel, "Use regular expression").fire();
      toggle(panel, "Include and exclude files").fire();
      field(panel, "Include files").setText("Research/**");
      field(panel, "Exclude files").setText("Drafts/**");
      check(request.get().equals(new FxWorkspaceSearch.Query("Original", FxWorkspaceSearch.Scope.CONTENTS,
              true, true, true, "Research/**", "Drafts/**")),
          "Case, word, regex, and glob controls must reach the same request as the query");
      check("Research/**".equals(field(panel, "Include files").queryAccessibleAttribute(AccessibleAttribute.TEXT))
          && "Drafts/**".equals(field(panel, "Exclude files").queryAccessibleAttribute(AccessibleAttribute.TEXT)),
          "Advanced fields must expose their typed values independently of their labels");

      FxWorkspaceSearch.Match match = new FxWorkspaceSearch.Match(3, 6, 19, 27, "Some Original text", 5, 13);
      var grouped = new FxWorkspaceSearch.Result(
          List.of(new FxWorkspaceSearch.NoteResult(notePath, false, List.of(match))), null, false);
      panel.showResults(grouped);
      workspace.applyCss();
      workspace.layout();
      @SuppressWarnings("unchecked")
      TreeView<FxSidebarSearch.OpenResult> results = (TreeView<FxSidebarSearch.OpenResult>) panel.lookup(".sidebar-search-results");
      check(results.isVisible() && results.getExpandedItemCount() == 2,
          "A content hit must render under its file group");
      check(((Label) panel.lookup(".sidebar-search-status")).getPadding().equals(new Insets(6, 16, 6, 16)),
          "The result count aligns with the Files heading and clears the options divider");
      check(panel.lookupAll(".fixture-row-action").size() == 1,
          "Only file group rows may get note actions");
      check(panel.lookup(".sidebar-search-highlight") != null,
          "A rendered line preview must expose its highlighted segment");
      var activeRequest = request.get();
      var activeResults = results.getRoot();
      advanced.fire();
      workspace.applyCss();
      workspace.layout();
      check(!options.isVisible() && !options.isManaged() && request.get() == activeRequest
              && results.isVisible() && results.getRoot() == activeResults && input.getText().equals("Original"),
          "Collapsing advanced options must preserve the query, settings, and current results");
      check(!advanced.isSelected() && "Show search options".equals(advanced.getTooltip().getText())
              && advanced.getAccessibleHelp().contains("are active"),
          "The collapsed options icon must communicate that advanced settings remain active");
      advanced.fire();
      check(options.isVisible() && toggle(panel, "Use regular expression").isSelected()
              && field(panel, "Include files").getText().equals("Research/**")
              && field(panel, "Exclude files").getText().equals("Drafts/**"),
          "Reopening must retain the advanced settings");
      advanced.fire();
      check(opened.get() == null, "Toggling search options must never open the first result");
      press(input, KeyCode.ENTER);
      check(new FxSidebarSearch.OpenResult(notePath, match).equals(opened.get()),
          "Enter from the query must open the first exact text match");
      press(input, KeyCode.DOWN);
      check(results.getSelectionModel().getSelectedIndex() == 0,
          "Down from the query must select the first result group");
      opened.set(null);
      results.getSelectionModel().select(1);
      press(results, KeyCode.ENTER);
      check(new FxSidebarSearch.OpenResult(notePath, match).equals(opened.get()),
          "Enter on a selected match must return its original offsets and path");

      opened.set(null);
      input.setText("[");
      press(input, KeyCode.ENTER);
      check(opened.get() == null && !results.isVisible(), "A new query must immediately clear stale results");
      panel.showError("Invalid regular expression");
      check(((Label) panel.lookup(".sidebar-search-status")).getText().equals("Invalid regular expression"),
          "Search errors must appear as a readable status");
      advanced.requestFocus();
      press(advanced, KeyCode.ESCAPE);
      check(dismissals.get() == 1 && !panel.hasQuery() && files.isVisible(),
          "Escape from a trailing action must clear search and restore the files view");
      check(tools.getParent() == chrome && !clear.isVisible() && !clear.isManaged(),
          "Clearing frees the clear action's space while keeping filters available");
      workspace.applyCss(); workspace.layout();
      check(Math.abs(files.localToScene(files.getLayoutBounds()).getMinY()
              - header.localToScene(header.getLayoutBounds()).getMaxY()) <= 1,
          "Dismissing search must return the full sidebar below the header to the files view");
      input.setText(" ");
      check(panel.hasQuery() && request.get().text().equals(" "),
          "Literal spaces must remain searchable");
      workspace.resize(200, workspace.getHeight());
      workspace.layout();
      workspace.applyCss();
      workspace.layout();
      check(chrome.getWidth() >= 176 && input.getWidth() >= 72 && advanced.getWidth() == 28
              && advanced.localToScene(advanced.getBoundsInLocal()).getMaxX()
                  < header.localToScene(header.getBoundsInLocal()).getMaxX(),
          "At 200 pixels the integrated controls must leave a useful search field: "
              + input.getWidth() + " / " + advanced.getWidth());
      snippetRows(workspace, panel, results, notePath);
    } finally {
      stage.close();
    }
  }

  private static void scopeIndicator(FxSidebarSearch panel) throws ReflectiveOperationException {
    ToggleButton files = toggle(panel, "Search filenames"), contents = toggle(panel, "Search note contents");
    Region indicator = (Region) panel.lookup(".segmented-indicator");
    check(indicator.isMouseTransparent() && !indicator.isManaged() && indicator.getBackground() != null
            && indicator.getLayoutX() == files.getLayoutX() && indicator.getWidth() == files.getWidth(),
        "The indicator must paint behind the selected segment without taking layout or pointer input");

    String preference = System.getProperty("diyalamp.reduceMotion");
    try {
      System.clearProperty("diyalamp.reduceMotion");
      if (!FxMotion.reduced()) {
        double left = files.getLayoutX(), right = contents.getLayoutX();
        contents.fire();
        var motion = FxSegmentedControl.class.getDeclaredField("motion");
        motion.setAccessible(true);
        Timeline slide = (Timeline) motion.get(files.getParent());
        slide.jumpTo(slide.getTotalDuration().divide(2));
        double middle = indicator.getLayoutX();
        check(middle > left && middle < right && contents.isSelected(),
            "The selection must update immediately while the decorative indicator slides");
        files.fire();
        check(indicator.getLayoutX() == middle && files.isSelected(),
            "Reversing the selection must start at the current indicator position");
      }
      System.setProperty("diyalamp.reduceMotion", "true");
      contents.fire();
      check(indicator.getLayoutX() == contents.getLayoutX() && indicator.getWidth() == contents.getWidth(),
          "Reduced motion must place the indicator on the newly selected scope immediately");
      files.fire();
    } finally {
      if (preference == null) System.clearProperty("diyalamp.reduceMotion");
      else System.setProperty("diyalamp.reduceMotion", preference);
    }
  }

  private static void snippetRows(VBox workspace, FxSidebarSearch panel, TreeView<?> results, Path notePath) {
    String excerpt = "Find the Original passage inside this longer result excerpt.";
    var matches = IntStream.rangeClosed(1, 100).mapToObj(line -> line == 1
        ? new FxWorkspaceSearch.Match(line, 1, 0, 8, "Original", 0, 8)
        : new FxWorkspaceSearch.Match(line, 10, 0, 8, excerpt, 9, 17)).toList();
    workspace.resize(272, workspace.getHeight());
    panel.showResults(new FxWorkspaceSearch.Result(
        List.of(new FxWorkspaceSearch.NoteResult(notePath, false, matches)), null, false));
    workspace.applyCss(); workspace.layout();
    check(previewHeight(results, 1) < previewHeight(results, 2),
        "A short result stays on one line and a longer result wraps within the sidebar");

    workspace.resize(200, workspace.getHeight());
    results.scrollTo(90);
    workspace.applyCss(); workspace.layout();
    check(previewHeight(results, 90) > 36,
        "A recycled result still wraps normally after narrowing the sidebar and scrolling");
  }

  private static double previewHeight(TreeView<?> results, int index) {
    var cell = results.lookupAll(".tree-cell:filled").stream()
        .map(node -> (TreeCell<?>) node).filter(row -> row.getIndex() == index).findFirst().orElseThrow();
    HBox row = (HBox) cell.getGraphic();
    TextFlow preview = (TextFlow) row.getChildren().getLast();
    double height = preview.prefHeight(preview.getWidth());
    check(cell.getHeight() >= height && cell.getHeight() - height < 22,
        "Result row height should follow the wrapped preview plus padding: " + cell.getHeight() + " / " + height);
    return height;
  }

  private static double treeBelowOptions(VBox workspace, FxSidebarSearch panel, TreeView<?> files) {
    workspace.applyCss(); workspace.layout();
    var options = panel.lookup(".sidebar-search-options");
    double treeY = files.localToScene(files.getLayoutBounds()).getMinY();
    check(options.isVisible() && files.isVisible()
            && options.localToScene(options.getLayoutBounds()).getMaxY() <= treeY + 1,
        "An empty focused query must reserve all visible option height before the first file");
    return treeY;
  }

  private static ToggleButton toggle(FxSidebarSearch panel, String name) {
    return java.util.stream.Stream.concat(panel.lookupAll(".sidebar-search-option").stream(),
            panel.lookupAll(".sidebar-search-mode").stream()).filter(node -> name.equals(node.getAccessibleText()))
        .map(ToggleButton.class::cast).findFirst().orElseThrow();
  }

  private static TextField field(FxSidebarSearch panel, String name) {
    return panel.lookupAll(".sidebar-search-filter-field").stream().filter(node ->
            node.queryAccessibleAttribute(AccessibleAttribute.LABELED_BY) instanceof Label label
                && name.equals(label.getText()))
        .map(TextField.class::cast).findFirst().orElseThrow();
  }

  private static void press(javafx.scene.Node node, KeyCode key) {
    Event.fireEvent(node, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
  }
}
