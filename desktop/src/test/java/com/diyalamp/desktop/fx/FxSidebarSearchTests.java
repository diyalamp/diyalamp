package com.diyalamp.desktop.fx;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
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

  private static void search() {
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

    HBox header = new HBox(8, panel.queryControl(), FxTheme.iconButton("folder-plus", "New folder", () -> {}),
        FxTheme.iconButton("plus", "New note", () -> {}));
    header.setPadding(new Insets(8));
    header.setAlignment(Pos.CENTER_LEFT);
    HBox.setHgrow(panel.queryControl(), Priority.ALWAYS);
    VBox.setVgrow(panel, Priority.ALWAYS);
    Label files = new Label("Fictional file tree");
    files.visibleProperty().bind(panel.hasQueryProperty().not());
    files.managedProperty().bind(files.visibleProperty());
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
      TextField input = (TextField) panel.queryControl().lookup(".text-field");
      check(header.getHeight() == 52 && input.getHeight() == 36,
          "The query must fit alongside creation controls in the shared 52-pixel header");
      check(!panel.hasQuery() && files.isVisible(), "The empty query must leave the files view visible");

      input.setText("Original");
      check(request.get().scope() == FxWorkspaceSearch.Scope.FILENAMES,
          "The default search must start with filenames");
      check(!files.isVisible(), "Typing a query must switch from files to search results");
      panel.focusQuery(FxWorkspaceSearch.Scope.CONTENTS);
      check(request.get().scope() == FxWorkspaceSearch.Scope.CONTENTS,
          "Switching to content search must refresh the active query");

      toggle(panel, "Match case").fire();
      toggle(panel, "Match whole word").fire();
      toggle(panel, "Use regular expression").fire();
      toggle(panel, "Include and exclude files").fire();
      field(panel, "Include files").setText("Research/**");
      field(panel, "Exclude files").setText("Drafts/**");
      check(request.get().equals(new FxWorkspaceSearch.Query("Original", FxWorkspaceSearch.Scope.CONTENTS,
              true, true, true, "Research/**", "Drafts/**")),
          "Case, word, regex, and glob controls must reach the same request as the query");

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
      check(panel.lookupAll(".fixture-row-action").size() == 1,
          "Only file group rows may get note actions");
      check(panel.lookup(".sidebar-search-highlight") != null,
          "A rendered line preview must expose its highlighted segment");
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
      press(input, KeyCode.ESCAPE);
      check(dismissals.get() == 1 && !panel.hasQuery() && files.isVisible(),
          "Escape must clear search and restore the files view");
      input.setText(" ");
      check(panel.hasQuery() && request.get().text().equals(" "),
          "Literal spaces must remain searchable");
    } finally {
      stage.close();
    }
  }

  private static ToggleButton toggle(FxSidebarSearch panel, String name) {
    return panel.lookupAll(".sidebar-search-option").stream().filter(node -> name.equals(node.getAccessibleText()))
        .map(ToggleButton.class::cast).findFirst().orElseThrow();
  }

  private static TextField field(FxSidebarSearch panel, String name) {
    return panel.lookupAll(".sidebar-search-filter-field").stream().filter(node -> name.equals(node.getAccessibleText()))
        .map(TextField.class::cast).findFirst().orElseThrow();
  }

  private static void press(javafx.scene.Node node, KeyCode key) {
    Event.fireEvent(node, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
  }
}
