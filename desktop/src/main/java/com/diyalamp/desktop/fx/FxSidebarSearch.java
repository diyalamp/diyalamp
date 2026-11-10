package com.diyalamp.desktop.fx;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.animation.FadeTransition;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

/** Search controls and a virtualized result tree. The workspace owns search execution and opening notes. */
public final class FxSidebarSearch extends VBox {
  public record OpenResult(Path path, FxWorkspaceSearch.Match match) {}

  private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
  private static final PseudoClass OPTIONS_ACTIVE = PseudoClass.getPseudoClass("options-active");
  private static final PseudoClass QUERY_FOCUS_VISIBLE = PseudoClass.getPseudoClass("query-focus-visible");
  private static final double SINGLE_ROW_OPTIONS_WIDTH = 360;
  private final HBox queryControl = new HBox(8);
  private final TextField query = new TextField();
  private final Label queryLabel = new Label(null, FxTheme.icon("search"));
  private final TextField include = new TextField(), exclude = new TextField();
  private final ToggleButton searchOptions = new ToggleButton(null, FxTheme.icon("settings-2"));
  private final RadioButton files = new RadioButton(), contents = new RadioButton();
  private final ToggleGroup mode = new ToggleGroup();
  private final ToggleButton matchCase = new ToggleButton("Case"),
      wholeWord = new ToggleButton("Whole word"), regex = new ToggleButton("Regex"),
      filters = new ToggleButton("Paths", FxTheme.icon("chevron-down", 12));
  private final TreeView<OpenResult> results = new TreeView<>();
  private final Label status = new Label();
  private final BooleanBinding hasQuery = query.textProperty().isNotEmpty();
  private Consumer<FxWorkspaceSearch.Query> onSearch = request -> {};
  private Consumer<OpenResult> onOpen = result -> {};
  private Runnable onDismiss = () -> {};
  private Function<Path, Node> rowActions = path -> null;
  private Path workspaceRoot;

  public FxSidebarSearch() {
    String stylesheet = FxSidebarSearch.class.getResource("/fx-sidebar-search.css").toExternalForm();
    getStylesheets().add(stylesheet);
    queryControl.getStylesheets().add(stylesheet);
    getStyleClass().add("sidebar-search");
    setMinWidth(0);
    setFillWidth(true);
    // The regular file tree can shrink; the option rows above it need their full height.
    minHeightProperty().bind(Bindings.when(hasQuery).then(0.0).otherwise(USE_PREF_SIZE));
    maxHeightProperty().bind(Bindings.when(hasQuery).then(Double.MAX_VALUE).otherwise(USE_PREF_SIZE));

    buildQueryControl();
    VBox options = buildOptions();
    status.getStyleClass().add("sidebar-search-status");
    status.setWrapText(true);
    status.setMinWidth(0);
    status.setMaxWidth(Double.MAX_VALUE);
    status.visibleProperty().bind(hasQuery.and(status.textProperty().isNotEmpty()));
    status.managedProperty().bind(status.visibleProperty());
    results.getStyleClass().addAll("sidebar-search-results", "edge-to-edge");
    results.setAccessibleText("Workspace search results grouped by file");
    results.setAccessibleHelp("Use arrow keys to move between results. Press Enter to open a result or Escape to clear search.");
    results.setShowRoot(false);
    results.setCellFactory(tree -> new ResultCell());
    results.setMinHeight(0);
    results.visibleProperty().bind(hasQuery.and(results.rootProperty().isNotNull()));
    results.managedProperty().bind(results.visibleProperty());
    results.setOnKeyPressed(event -> {
      if (event.getCode() == KeyCode.ENTER && open(results.getSelectionModel().getSelectedItem())) event.consume();
    });
    VBox.setVgrow(results, Priority.ALWAYS);
    getChildren().addAll(options, status, results);
    addEventFilter(KeyEvent.KEY_PRESSED, this::dismissOnEscape);
    queryControl.addEventFilter(KeyEvent.KEY_PRESSED, this::dismissOnEscape);

    query.textProperty().addListener(observable -> changed());
    include.textProperty().addListener(observable -> changed());
    exclude.textProperty().addListener(observable -> changed());
    for (ToggleButton toggle : new ToggleButton[] {matchCase, wholeWord, regex})
      toggle.selectedProperty().addListener(observable -> changed());
    mode.selectedToggleProperty().addListener(observable -> {
      describeQuery();
      changed();
    });
  }

  /** Mount the search field with its integrated clear and options controls. */
  public Region queryControl() { return queryControl; }
  public ToggleButton optionsButton() { return searchOptions; }
  public BooleanBinding hasQueryProperty() { return hasQuery; }
  public boolean hasQuery() { return hasQuery.get(); }
  public void setOnSearch(Consumer<FxWorkspaceSearch.Query> handler) { onSearch = Objects.requireNonNull(handler); }
  public void setOnOpen(Consumer<OpenResult> handler) { onOpen = Objects.requireNonNull(handler); }
  public void setOnDismiss(Runnable handler) { onDismiss = Objects.requireNonNull(handler); }
  public void setRowActions(Function<Path, Node> factory) {
    rowActions = Objects.requireNonNull(factory);
    results.refresh();
  }
  public void setWorkspaceRoot(Path root) {
    workspaceRoot = root == null ? null : root.toAbsolutePath().normalize();
    results.refresh();
  }

  public void focusQuery() {
    query.requestFocus();
    query.selectAll();
  }

  public void focusQuery(FxWorkspaceSearch.Scope scope) {
    mode.selectToggle(scope == FxWorkspaceSearch.Scope.CONTENTS ? contents : files);
    focusQuery();
  }

  public void reset() {
    query.clear();
    searchOptions.setSelected(false);
    render(null, "", false);
  }

  public void showSearching() { render(null, "Searching…", false); }
  public void showError(String error) { render(null, error, true); }

  public void showResults(FxWorkspaceSearch.Result result) {
    if (result.error() != null) {
      showError(result.error());
      return;
    }
    TreeItem<OpenResult> root = new TreeItem<>();
    root.setExpanded(true);
    int matches = 0;
    for (FxWorkspaceSearch.NoteResult note : result.notes()) {
      TreeItem<OpenResult> file = new TreeItem<>(new OpenResult(note.path(), null));
      for (FxWorkspaceSearch.Match match : note.matches())
        file.getChildren().add(new TreeItem<>(new OpenResult(note.path(), match)));
      file.setExpanded(true);
      root.getChildren().add(file);
      matches += note.matches().size();
    }
    int notes = result.notes().size();
    String count = notes == 0 ? "No matches found" : matches == 0 ? quantity(notes, "file")
        : quantity(matches, "match", "matches") + " in " + quantity(notes, "file");
    render(notes == 0 ? null : root, result.partial() ? count + " · Partial results" : count, false);
  }

  private void buildQueryControl() {
    searchOptions.getStyleClass().addAll("flat", "button-icon", "workspace-tool", "file-row-action", "sidebar-search-toggle");
    searchOptions.setAccessibleText("Search options");
    searchOptions.setAccessibleHelp("Show or hide search scope and filters.");
    Tooltip optionsTooltip = new Tooltip();
    optionsTooltip.textProperty().bind(Bindings.when(searchOptions.selectedProperty())
        .then("Hide search options").otherwise("Show search options"));
    searchOptions.setTooltip(optionsTooltip);
    queryControl.setAlignment(Pos.CENTER_LEFT);
    queryControl.setMinWidth(0);
    queryControl.getStyleClass().add("sidebar-search-query");
    query.getStyleClass().add("sidebar-search-query-field");
    query.setMinWidth(0);
    query.setMaxWidth(Double.MAX_VALUE);
    query.setMaxHeight(Double.MAX_VALUE);
    HBox.setHgrow(query, Priority.ALWAYS);
    queryLabel.getStyleClass().add("sidebar-search-query-label");
    queryLabel.setLabelFor(query);
    queryLabel.setOnMousePressed(event -> {
      if (event.getButton() == MouseButton.PRIMARY) query.requestFocus();
    });
    query.focusVisibleProperty().addListener((observable, previous, visible) ->
        queryControl.pseudoClassStateChanged(QUERY_FOCUS_VISIBLE, visible));
    describeQuery();
    query.setOnAction(event -> {
      TreeItem<OpenResult> first = firstResult();
      if (first != null && !first.isLeaf()) first = first.getChildren().getFirst();
      if (open(first)) event.consume();
    });
    query.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
      if (event.getTarget() == query && event.getCode() == KeyCode.DOWN && firstResult() != null) {
        results.getSelectionModel().select(0);
        results.requestFocus();
        event.consume();
      }
    });

    Button clear = FxTheme.iconButton("x", "Clear search", this::dismiss);
    clear.getStyleClass().add("sidebar-search-clear");
    clear.visibleProperty().bind(hasQuery);
    clear.managedProperty().bind(clear.visibleProperty());
    HBox controls = new HBox(2, clear, searchOptions);
    controls.setAlignment(Pos.CENTER_RIGHT);
    controls.getStyleClass().add("sidebar-search-tools");
    queryControl.getChildren().addAll(queryLabel, query, controls);
  }

  private VBox buildOptions() {
    for (RadioButton button : new RadioButton[] {files, contents}) {
      button.setToggleGroup(mode);
      button.setMinWidth(0);
      // Equal segments grow with the track; the radio skin uses this width for its text.
      button.setPrefWidth(80);
      button.getStyleClass().add("sidebar-search-mode");
    }
    files.setText("File names");
    files.setAccessibleText("Search filenames");
    contents.setText("Note text");
    contents.setAccessibleText("Search note contents");
    files.setSelected(true);
    HBox modes = new FxSegmentedControl(0, files, contents);
    modes.getStyleClass().add("sidebar-search-modes");

    styleToggle(matchCase, "Match case");
    styleToggle(wholeWord, "Match whole word");
    styleToggle(regex, "Use regular expression");
    styleToggle(filters, "Include and exclude files");
    filters.getStyleClass().add("sidebar-search-paths");
    filters.getGraphic().getStyleClass().add("sidebar-search-path-chevron");
    GridPane flags = new GridPane();
    flags.setMinWidth(0);
    flags.setHgap(8);
    flags.setVgap(6);
    flags.getChildren().addAll(matchCase, wholeWord, regex, filters);
    arrangeOptions(flags, 2);
    flags.widthProperty().addListener((observable, previous, width) -> {
      int columns = width.doubleValue() >= SINGLE_ROW_OPTIONS_WIDTH ? 4 : 2;
      if (columns != flags.getColumnConstraints().size()) arrangeOptions(flags, columns);
    });

    VBox globs = new VBox(8, filterField("Include files", "e.g. notes/**", include),
        filterField("Exclude files", "e.g. drafts/**", exclude));
    globs.getStyleClass().add("sidebar-search-path-fields");
    disclose(globs, filters);
    VBox options = new VBox(8, modes, flags, globs);
    options.getStyleClass().add("sidebar-search-options");
    disclose(options, searchOptions);
    return options;
  }

  private static void arrangeOptions(GridPane flags, int columns) {
    flags.getColumnConstraints().clear();
    for (int i = 0; i < columns; i++) {
      ColumnConstraints column = new ColumnConstraints();
      column.setPercentWidth(100.0 / columns);
      flags.getColumnConstraints().add(column);
    }
    for (int i = 0; i < flags.getChildren().size(); i++) {
      Node option = flags.getChildren().get(i);
      GridPane.setColumnIndex(option, i % columns);
      GridPane.setRowIndex(option, i / columns);
    }
  }

  private static void disclose(VBox panel, ToggleButton toggle) {
    panel.setVisible(false);
    panel.setOpacity(0);
    panel.managedProperty().bind(panel.visibleProperty());
    panel.disableProperty().bind(toggle.selectedProperty().not());
    FadeTransition fade = new FadeTransition(Duration.millis(140), panel);
    fade.setInterpolator(FxMotion.SIDEBAR_EASE);
    toggle.selectedProperty().addListener((observable, previous, open) -> {
      double opacity = panel.getOpacity();
      fade.stop();
      panel.setOpacity(opacity);
      if (FxMotion.reduced() || panel.getScene() == null || !open && opacity == 0) {
        panel.setOpacity(open ? 1 : 0);
        panel.setVisible(open);
        return;
      }
      if (open) panel.setVisible(true);
      fade.setFromValue(opacity);
      fade.setToValue(open ? 1 : 0);
      fade.setOnFinished(event -> panel.setVisible(toggle.isSelected()));
      fade.playFromStart();
    });
  }

  private static void styleToggle(ToggleButton toggle, String description) {
    toggle.getStyleClass().addAll("flat", "sidebar-search-option");
    toggle.setMinWidth(0);
    toggle.setMaxWidth(Double.MAX_VALUE);
    toggle.setAccessibleText(description);
  }

  private static VBox filterField(String name, String hint, TextField input) {
    Label label = new Label(name);
    label.setLabelFor(input);
    input.setPromptText(hint);
    input.getStyleClass().addAll("note-search-field", "sidebar-search-filter-field");
    input.setMinWidth(0);
    return new VBox(8, label, input);
  }

  private void describeQuery() {
    query.setPromptText(contents.isSelected() ? "Find text…" : "Find a note…");
    // Keep the accessible field value equal to the query, and name it through its label.
    queryLabel.setAccessibleText(contents.isSelected() ? "Search note contents" : "Search filenames");
    query.setAccessibleHelp("Press Down to browse results, Enter to open the first match, or Escape to clear search.");
  }

  private void changed() {
    boolean pathFilters = !include.getText().isEmpty() || !exclude.getText().isEmpty();
    filters.pseudoClassStateChanged(OPTIONS_ACTIVE, pathFilters);
    filters.setAccessibleHelp(pathFilters ? "File path filters are applied." : "Show or hide the file path filters.");
    boolean advanced = contents.isSelected() || matchCase.isSelected() || wholeWord.isSelected()
        || regex.isSelected() || pathFilters;
    searchOptions.pseudoClassStateChanged(OPTIONS_ACTIVE, advanced);
    searchOptions.setAccessibleHelp(advanced ? "Advanced search settings are active. Show or hide the controls."
        : "Show or hide search scope and filters.");
    if (hasQuery()) showSearching();
    else render(null, "", false);
    onSearch.accept(new FxWorkspaceSearch.Query(query.getText(),
        contents.isSelected() ? FxWorkspaceSearch.Scope.CONTENTS : FxWorkspaceSearch.Scope.FILENAMES,
        matchCase.isSelected(), wholeWord.isSelected(), regex.isSelected(), include.getText(), exclude.getText()));
  }

  private void render(TreeItem<OpenResult> root, String message, boolean error) {
    results.setRoot(root);
    status.setText(message);
    status.pseudoClassStateChanged(ERROR, error);
  }

  private void dismissOnEscape(KeyEvent event) {
    if (event.getCode() == KeyCode.ESCAPE) {
      dismiss();
      event.consume();
    }
  }

  private void dismiss() {
    reset();
    onDismiss.run();
  }

  private TreeItem<OpenResult> firstResult() {
    TreeItem<OpenResult> root = results.getRoot();
    return root == null || root.isLeaf() ? null : root.getChildren().getFirst();
  }

  private boolean open(TreeItem<OpenResult> item) {
    if (item == null || item.getValue() == null) return false;
    onOpen.accept(item.getValue());
    return true;
  }

  private Path relative(Path path) {
    return workspaceRoot != null && path.startsWith(workspaceRoot) ? workspaceRoot.relativize(path) : path.getFileName();
  }

  private static String quantity(int count, String singular) { return quantity(count, singular, singular + "s"); }
  private static String quantity(int count, String singular, String plural) {
    return count + " " + (count == 1 ? singular : plural);
  }

  private final class ResultCell extends TreeCell<OpenResult> {
    private static final double INLINE_ACTIONS_WIDTH = 270;
    private Node actions;
    private VBox fileGroup;
    private HBox fileHeading, actionStrip;

    ResultCell() {
      setText(null);
      // The tree supplies the viewport width. A long preview must wrap instead of growing the viewport.
      setPrefWidth(0);
      widthProperty().addListener(observable -> layoutFileActions());
      setOnMouseClicked(event -> {
        if (event.getButton() != MouseButton.PRIMARY || isEmpty()) return;
        for (Node node = event.getTarget() instanceof Node target ? target : null;
            node != null && node != this; node = node.getParent()) {
          if (node == actions || node == getDisclosureNode()) return;
        }
        if ((getTreeItem().isLeaf() || event.getClickCount() == 2) && open(getTreeItem())) event.consume();
      });
    }

    @Override protected void updateItem(OpenResult result, boolean empty) {
      super.updateItem(result, empty);
      actions = null;
      fileGroup = null;
      fileHeading = actionStrip = null;
      setGraphic(null);
      setTooltip(null);
      setAccessibleText(null);
      if (empty || result == null) return;

      Region row = result.match() == null ? fileRow(result.path()) : matchRow(result);
      row.setMinWidth(0);
      // Leave room for the tree's disclosure column, nesting indent, and cell insets.
      row.prefWidthProperty().bind(Bindings.max(0, widthProperty().subtract(result.match() == null ? 40 : 52)));
      setGraphic(row);
    }

    private VBox fileRow(Path path) {
      Path relative = relative(path);
      Label name = new Label(relative.getFileName().toString());
      name.getStyleClass().add("sidebar-search-file-name");
      name.setMinWidth(0);
      VBox labels = new VBox(name);
      labels.setMinWidth(0);
      HBox.setHgrow(labels, Priority.ALWAYS);
      if (relative.getParent() != null) {
        Label folder = new Label(relative.getParent().toString());
        folder.getStyleClass().add("sidebar-search-folder");
        folder.setMinWidth(0);
        labels.getChildren().add(folder);
      }
      fileHeading = new HBox(8, FxTheme.icon("file-text", 16), labels);
      fileHeading.getStyleClass().add("sidebar-search-file");
      fileGroup = new VBox(4, fileHeading);
      int count = getTreeItem().getChildren().size();
      setAccessibleText(relative + (count == 0 ? "" : ", " + quantity(count, "match", "matches")));
      setTooltip(new Tooltip(relative.toString()));
      if (count > 0) {
        Label badge = new Label(Integer.toString(count));
        badge.getStyleClass().add("sidebar-search-count");
        badge.visibleProperty().bind(widthProperty().greaterThanOrEqualTo(220));
        badge.managedProperty().bind(badge.visibleProperty());
        fileHeading.getChildren().add(badge);
      }
      actions = rowActions.apply(path);
      if (actions != null) {
        actionStrip = new HBox(actions);
        actionStrip.setAlignment(Pos.CENTER_RIGHT);
        layoutFileActions();
      }
      return fileGroup;
    }

    private void layoutFileActions() {
      if (actionStrip == null) return;
      if (getWidth() < INLINE_ACTIONS_WIDTH && actionStrip.getParent() != fileGroup) {
        fileHeading.getChildren().remove(actionStrip);
        fileGroup.getChildren().add(actionStrip);
      } else if (getWidth() >= INLINE_ACTIONS_WIDTH && actionStrip.getParent() != fileHeading) {
        fileGroup.getChildren().remove(actionStrip);
        fileHeading.getChildren().add(actionStrip);
      }
    }

    private HBox matchRow(OpenResult result) {
      FxWorkspaceSearch.Match match = result.match();
      Label number = new Label(match.line() + ":");
      number.getStyleClass().add("sidebar-search-line");
      TextFlow preview = preview(match);
      preview.setMinWidth(0);
      HBox.setHgrow(preview, Priority.ALWAYS);
      HBox row = new HBox(8, number, preview);
      // VirtualFlow measures the row before laying it out. Give the preview the
      // width remaining after the line number, with a fallback for unlaid cells.
      preview.prefWidthProperty().bind(Bindings.createDoubleBinding(
          () -> Math.max(60, row.getPrefWidth() - row.getSpacing() - number.prefWidth(-1)),
          row.prefWidthProperty(), row.spacingProperty(), number.fontProperty()));
      row.getStyleClass().add("sidebar-search-match");
      setAccessibleText(relative(result.path()) + ", line " + match.line() + ", column " + match.column() + ": " + match.excerpt());
      setTooltip(new Tooltip(match.excerpt()));
      return row;
    }
  }

  private static TextFlow preview(FxWorkspaceSearch.Match match) {
    String excerpt = match.excerpt();
    int start = Math.clamp(match.highlightStart(), 0, excerpt.length());
    int end = Math.clamp(match.highlightEnd(), start, excerpt.length());
    int left = codePointBoundary(excerpt, Math.max(0, start - 12));
    int highlightedEnd = codePointBoundary(excerpt, Math.min(end, start + 36));
    int right = codePointBoundary(excerpt, Math.min(excerpt.length(), end + 20));
    Text prefix = new Text((left > 0 ? "…" : "") + excerpt.substring(left, start));
    Text highlight = new Text(start == end ? "│" : excerpt.substring(start, highlightedEnd));
    Text suffix = new Text(highlightedEnd < end ? "…" : excerpt.substring(end, right) + (right < excerpt.length() ? "…" : ""));
    prefix.getStyleClass().add("sidebar-search-preview-text");
    suffix.getStyleClass().add("sidebar-search-preview-text");
    highlight.getStyleClass().add("sidebar-search-highlight");
    return new TextFlow(prefix, highlight, suffix);
  }

  private static int codePointBoundary(String text, int offset) {
    return offset > 0 && offset < text.length() && Character.isLowSurrogate(text.charAt(offset)) ? offset - 1 : offset;
  }
}
