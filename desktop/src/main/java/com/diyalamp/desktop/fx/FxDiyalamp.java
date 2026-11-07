package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Tab;
import atlantafx.base.controls.Popover;
import atlantafx.base.controls.TabSkin;
import atlantafx.base.theme.Styles;
import com.diyalamp.desktop.DesktopLauncher;
import com.diyalamp.desktop.NoteStore;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.prefs.Preferences;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Interpolator;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Local Markdown workspace built from AtlantaFX and standard JavaFX controls. */
public final class FxDiyalamp extends Application {
  private static final DataFormat NOTE_DRAG = new DataFormat("application/x-diyalamp-note-path");
  private static final DataFormat TAB_DRAG = new DataFormat("application/x-diyalamp-tab-id");
  static final List<TransferMode> NOTE_DRAG_MODES = List.of(TransferMode.COPY, TransferMode.MOVE);
  static final List<TransferMode> TAB_DRAG_MODES = List.of(TransferMode.MOVE);
  static TransferMode paneDropMode(boolean fromTab) { return fromTab ? TransferMode.MOVE : TransferMode.COPY; }
  private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");
  private static final PseudoClass ACCENT_SELECTED = PseudoClass.getPseudoClass("selected");
  private static final double SIDEBAR_WIDTH = 272;
  private static final double CONNECTIONS_WIDTH = 304;
  private static final double MIN_WRITING_WIDTH = 240;
  private static final double SIDEBAR_DIVIDER_WIDTH = 6;
  private static final String[][] ACCENT_CHOICES = {
      {"Sage", "#7A9A85"}, {"Blue", "#297AA0"}, {"Lavender", "#A497BD"},
      {"Rose", "#BA929B"}, {"Amber", "#B5A073"}, {"Slate", "#8A929F"}
  };
  private final Preferences preferences;
  private final Map<Path, Session> sessions = new LinkedHashMap<>();
  private final TreeView<Path> tree = new TreeView<>();
  private final TextField search = new TextField();
  private final BooleanProperty searching = new SimpleBooleanProperty(false);
  private final Label folderLabel = new Label("No folder open"), status = new Label("Local Markdown files");
  private final Label characters = metricLabel("type", "Characters in the note text (Unicode code points)"),
      words = metricLabel("square-pen", "Words in the note text"),
      lines = metricLabel("list", "Logical text lines; wrapping does not change this count"),
      fileSize = metricLabel("file-text", "UTF-8 size of the current Markdown, including unsaved edits");
  private final HBox metrics = new HBox(16, characters, words, lines, fileSize);
  private final VBox explorer = new VBox();
  private final StackPane explorerClip = new StackPane(explorer);
  private final SplitPane body = new SplitPane();
  private final StackPane workspaceLayer = new StackPane(body);
  private final SplitPane split = new SplitPane();
  private final BorderPane root = new BorderPane();
  private final ToolBar toolbar = new ToolBar();
  private final StackPane toolbarClip = clipped(toolbar);
  private final Region toolbarDragHandle = new Region();
  private final HBox footer = new HBox();
  private final StackPane footerClip = clipped(footer);
  private final BooleanProperty zenMode = new SimpleBooleanProperty(false);
  private final DoubleProperty zenProgress = new SimpleDoubleProperty(0);
  private Timeline zenAnimation;
  private final PauseTransition autosave = new PauseTransition(Duration.millis(650));
  private final PauseTransition serialization = new PauseTransition(Duration.millis(120));
  private final ObjectProperty<Color> accent = new SimpleObjectProperty<>(FxTheme.DEFAULT_ACCENT);
  private final ObjectProperty<Color> displayedAccent = new SimpleObjectProperty<>(FxTheme.DEFAULT_ACCENT);
  private Timeline accentAnimation;
  private Button accentControl;
  private Popover accentMenu;
  private Stage stage;
  private NoteStore store;
  private PaneHost primary, secondary;
  private View active;
  private NoteStore.Deleted lastDeleted;
  private boolean persistentFolder = true, refreshing, sidebarOpen = true;
  private Timeline sidebarAnimation;
  private final PauseTransition sidebarSave = new PauseTransition(Duration.millis(180));
  private double filesWidth = SIDEBAR_WIDTH, connectionsWidth = CONNECTIONS_WIDTH;
  private boolean sidebarLayoutReady, sidebarLayoutChanging, sidebarLayoutQueued;
  private FxUpdates updates;
  private FxPomodoro pomodoro;
  private FxNoteGraph noteGraph;
  private ToggleButton connectionsControl;

  public FxDiyalamp() { this(Preferences.userRoot().node("com/diyalamp/desktop")); }
  FxDiyalamp(Preferences preferences) {
    this.preferences = preferences;
    zenMode.addListener((observable, previous, enabled) -> animateZen(enabled));
  }

  ReadOnlyDoubleProperty zenProgressProperty() { return zenProgress; }
  ReadOnlyBooleanProperty zenModeProperty() { return zenMode; }

  private static final class Session {
    Path path;
    String text, fingerprint, error;
    boolean dirty;
    String metricsSource;
    NoteMetrics metrics;
    View blockedView, pendingView;
    final List<View> views = new ArrayList<>();
    Session(Path path, NoteStore.Loaded loaded) {
      this.path = path; text = loaded.text(); fingerprint = loaded.fingerprint();
    }
  }

  private final class View {
    final Session session;
    final FxWritingPane editor;
    Tab tab;
    final BorderPane content = new BorderPane();
    PaneHost host;
    boolean disposed;
    View(Session session, PaneHost host) {
      this.session = session; this.host = host;
      editor = new FxWritingPane(session.text);
      editor.setFocusLineProgress(zenProgressProperty());
      tab = newTab(UUID.randomUUID().toString());
      content.getStyleClass().add("note-page");
      Node writing = editor.node();
      StackPane page = new StackPane(writing);
      StackPane.setAlignment(writing, Pos.TOP_CENTER);
      content.setCenter(page);
      content.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> activate(this));
      editor.setOnChange(() -> changed(this));
      editor.setOnNoteLink(target -> { if (noteGraph != null) noteGraph.openWiki(session.path, target); });
      session.views.add(this);
    }
    Tab newTab(String id) {
      Tab result = new Tab(id, session.path.getFileName().toString(), FxTheme.icon("file-text", 14));
      result.setUserData(this);
      result.setTooltip(new Tooltip(session.path.getFileName().toString()));
      result.setOnCloseRequest(event -> { if (!save(session, true)) event.consume(); });
      result.setOnClosed(event -> removeView(this));
      return result;
    }
  }

  private final class PaneHost {
    final FxDocumentTabs tabs = new FxDocumentTabs();
    final BorderPane container = new BorderPane();
    final Label breadcrumb = new Label();
    final VBox chrome = new VBox();
    final StackPane chromeClip = clipped(chrome);
    PaneHost() {
      tabs.getStyleClass().addAll(Styles.DENSE, Styles.TABS_FLOATING, "document-tabs");
      tabs.setTabClosingPolicy(Tab.ClosingPolicy.ALL_TABS);
      tabs.setTabResizePolicy(Tab.ResizePolicy.COMPUTED_WIDTH);
      tabs.setTabMinWidth(110);
      tabs.setAnimated(true);
      HBox location = new HBox(breadcrumb);
      location.setAlignment(Pos.CENTER_LEFT);
      location.getStyleClass().add("document-location");
      breadcrumb.getStyleClass().add("document-breadcrumb");
      chrome.getChildren().setAll(tabs, location);
      container.setTop(chromeClip);
      container.getStyleClass().add("document-pane");
      tabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
        View view = selected == null ? null : (View) selected.getUserData();
        if (view != null && !view.disposed) {
          container.setCenter(view.content); activate(view);
        } else {
          container.setCenter(emptyPage()); breadcrumb.setText("");
          if (active != null && active.host == this) active = null;
          updateStatus();
        }
      });
      container.setCenter(emptyPage());
      installDrop(this);
      installTabDrag(this);
    }
  }

  @Override public void start(Stage stage) {
    this.stage = stage;
    updates = new FxUpdates(stage, () -> saveAll(true), Platform::exit, status::setText);
    FxTheme.setup();
    noteGraph = new FxNoteGraph(path -> open(path, false), status::setText);
    filesWidth = sidebarWidth("filesWidth", SIDEBAR_WIDTH, 160);
    connectionsWidth = sidebarWidth("connectionsWidth", CONNECTIONS_WIDTH, 200);
    sidebarOpen = preferences.getBoolean("filesVisible", true);
    noteGraph.showingProperty().addListener((observable, previous, showing) -> {
      preferences.putBoolean("connectionsVisible", showing);
      if (sidebarLayoutReady) layoutSidebars(true);
    });
    noteGraph.showingProperty().set(preferences.getBoolean("connectionsVisible", false));
    stage.setTitle("diyalamp");
    stage.setMinWidth(820); stage.setMinHeight(560);
    boolean packagedMac = System.getProperty("os.name", "").startsWith("Mac")
        && !System.getProperty("jpackage.app-path", "").isBlank();
    if (!packagedMac) {
      var icon = FxTheme.image("app-icon.png");
      if (icon != null) stage.getIcons().add(icon);
    }
    primary = new PaneHost(); secondary = new PaneHost();
    split.getItems().add(primary.container);
    split.getStyleClass().add("writing-split");
    buildExplorer();
    configureSidebars();
    root.getStyleClass().add("workspace-root");
    root.setCenter(workspaceLayer);
    pomodoro = new FxPomodoro(stage, status::setText);
    buildToolbar();
    root.setTop(new VBox(buildMenu(), FxWindowHeader.create(stage, toolbarClip, toolbarDragHandle)));
    metrics.getStyleClass().add("note-metrics"); metrics.setAlignment(Pos.CENTER_RIGHT);
    footer.getChildren().setAll(status, spacer(), metrics);
    footer.getStyleClass().add("workspace-status");
    footer.setAlignment(Pos.CENTER_LEFT); root.setBottom(footerClip);
    Scene scene = new Scene(root, 1180, 800);
    scene.getPreferences().setColorScheme(ColorScheme.DARK);
    scene.getStylesheets().add(FxDiyalamp.class.getResource("/fx-workspace.css").toExternalForm());
    initializeAccent();
    scene.focusOwnerProperty().addListener((observable, previous, focused) -> {
      for (Session session : sessions.values()) for (View view : session.views)
        if (within(focused, view.content)) { activate(view); return; }
    });
    stage.setScene(scene);
    stage.setOnCloseRequest(event -> { if (!saveAll(true)) event.consume(); else disposeAll(); });
    autosave.setOnFinished(event -> saveAll(false));
    serialization.setOnFinished(event -> {
      for (Session session : new ArrayList<>(sessions.values())) flush(session);
    });
    MacDockIcon.restoreBundledIcon();
    stage.show();
    queueSidebarLayout();
    String requested = null;
    List<String> arguments = getParameters() == null ? List.of() : getParameters().getRaw();
    if (arguments.size() == 2 && arguments.getFirst().equals("--workspace")) {
      requested = arguments.get(1); persistentFolder = false;
    } else requested = preferences.get("folder", "");
    if (!requested.isBlank()) {
      try {
        Path folder = Path.of(requested);
        if (Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) setFolder(folder);
      } catch (Exception e) { status.setText("Choose a folder to start writing"); }
    }
    stage.toFront();
    if (preferences.getBoolean("recoveryAvailable", false)) {
      preferences.putBoolean("recoveryAvailable", false);
      Platform.runLater(() -> information("Recovery copies are available",
          "Some edits from the previous session could not be saved to their original files. "
              + "Use File → Open recovery folder to inspect the saved copies before editing those notes."));
    }
  }

  private ToolBar buildToolbar() {
    toolbar.getStyleClass().add("workspace-toolbar");
    Label name = new Label("diyalamp"); name.getStyleClass().add("workspace-brand-name");
    var lamp = FxTheme.lamp(24);
    lamp.setTranslateY(2);
    HBox brand = new HBox(8, lamp, name);
    brand.setAlignment(Pos.CENTER_LEFT); brand.getStyleClass().add("workspace-brand");
    Button files = FxTheme.iconButton("panel-left", "Toggle files sidebar", this::toggleSidebar);
    Button folder = FxTheme.iconButton("folder-open", "Open folder…", this::chooseFolder);
    folderLabel.getStyleClass().add("workspace-folder");
    folderLabel.setAlignment(Pos.CENTER_LEFT);
    folderLabel.setMinWidth(0);
    folderLabel.setMaxWidth(Double.MAX_VALUE);
    folderLabel.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);
    Tooltip folderPath = new Tooltip();
    folderPath.textProperty().bind(folderLabel.textProperty());
    folderLabel.setTooltip(folderPath);
    ToggleButton find = new ToggleButton(null, FxTheme.icon("search", 16));
    find.getStyleClass().addAll("flat", "button-icon", "workspace-tool");
    find.setAccessibleText("Search notes");
    find.setTooltip(new Tooltip("Search notes · ⌘P"));
    find.selectedProperty().bindBidirectional(searching);
    var noWorkspace = Bindings.createBooleanBinding(() -> store == null, tree.rootProperty());
    find.disableProperty().bind(noWorkspace);
    search.setPromptText("Find a note…");
    search.setAccessibleText("Find a note by name");
    search.getStyleClass().addAll(Styles.ROUNDED, "note-search-field");
    search.setMinWidth(0);
    search.visibleProperty().bind(searching);
    search.managedProperty().bind(searching);
    folderLabel.visibleProperty().bind(searching.not());
    folderLabel.managedProperty().bind(searching.not());
    StackPane location = new StackPane(folderLabel, search);
    location.setMinWidth(32);
    location.setPrefWidth(32);
    location.maxWidthProperty().bind(Bindings.createDoubleBinding(
        () -> searching.get() ? 320 : Math.max(32, folderLabel.prefWidth(-1)),
        searching, folderLabel.textProperty(), folderLabel.fontProperty()));
    HBox.setHgrow(location, Priority.ALWAYS);
    toolbarDragHandle.setMinWidth(24);
    HBox.setHgrow(toolbarDragHandle, Priority.SOMETIMES);
    search.textProperty().addListener((observable, previous, query) -> refreshTree());
    searching.addListener((observable, previous, enabled) -> {
      if (enabled) {
        if (!sidebarOpen) toggleSidebar();
        Platform.runLater(search::requestFocus);
      } else search.clear();
    });
    search.setOnKeyPressed(event -> {
      if (event.getCode() == KeyCode.ESCAPE) {
        searching.set(false);
        find.requestFocus();
        event.consume();
      }
    });
    search.setOnAction(event -> openFirstSearchResult());
    ToggleButton connections = new ToggleButton(null, FxTheme.icon("panel-right", 16));
    connectionsControl = connections;
    connections.getStyleClass().addAll("flat", "button-icon", "workspace-tool");
    connections.setAccessibleText("Toggle right sidebar");
    connections.setAccessibleHelp("Show or hide incoming and outgoing note connections. The state is remembered.");
    Tooltip connectionHelp = new Tooltip();
    connectionHelp.textProperty().bind(Bindings.when(connections.selectedProperty())
        .then("Hide right sidebar").otherwise("Show right sidebar"));
    connections.setTooltip(connectionHelp);
    if (noteGraph != null) connections.selectedProperty().bindBidirectional(noteGraph.showingProperty());
    ToggleButton zen = new ToggleButton(null, FxTheme.icon("focus", 18));
    zen.getStyleClass().addAll("flat", "button-icon", "workspace-tool", "zen-toggle");
    Tooltip zenHelp = new Tooltip();
    zenHelp.textProperty().bind(Bindings.when(zenMode).then("Exit line focus · ⇧⌘F").otherwise("Enter line focus · ⇧⌘F"));
    zen.setTooltip(zenHelp); zen.setAccessibleText("Zen mode");
    zen.setAccessibleHelp("Keep the current line clear and gradually fade surrounding lines. Workspace controls stay visible.");
    zen.selectedProperty().bindBidirectional(zenMode);
    Button create = FxTheme.iconButton("plus", "New note", () -> newNote(null));
    Button createFolder = FxTheme.iconButton("folder-plus", "New folder", () -> newFolder(null));
    createFolder.disableProperty().bind(noWorkspace);
    Button beside = FxTheme.iconButton("columns-2", "Open note side by side", this::splitActive);
    toolbar.getItems().setAll(brand, files, folder, location, find, create, createFolder, beside, toolbarDragHandle);
    if (pomodoro != null) toolbar.getItems().add(pomodoro.button());
    Button reset = FxTheme.iconButton("refresh-cw", "Reset sidebar widths", this::resetSidebarWidths);
    reset.setAccessibleHelp("Restore default left and right sidebar widths without changing visibility or notes.");
    toolbar.getItems().addAll(accentButton(), zen, reset, connections);
    return toolbar;
  }

  private void buildExplorer() {
    explorer.getStyleClass().add("file-explorer");
    explorer.setMinWidth(0); explorer.setPrefWidth(filesWidth); explorer.setMaxWidth(Double.MAX_VALUE);
    explorerClip.setMinWidth(0); explorerClip.setPrefWidth(filesWidth); explorerClip.setMaxWidth(Double.MAX_VALUE);
    Rectangle clipping = new Rectangle();
    clipping.widthProperty().bind(explorerClip.widthProperty());
    clipping.heightProperty().bind(explorerClip.heightProperty()); explorerClip.setClip(clipping);
    tree.setShowRoot(false); tree.setAccessibleText("Markdown files and folders");
    tree.setCellFactory(view -> new FileCell());
    tree.getStyleClass().add("file-tree");
    tree.setOnKeyPressed(event -> {
      Path selected = selectedPath();
      if (event.getCode() == KeyCode.ENTER && selected != null && store != null && !store.isDirectory(selected)) {
        open(selected, false); event.consume();
      } else if (event.getCode() == KeyCode.F2 && selected != null && !store.isDirectory(selected)) {
        rename(selected); event.consume();
      }
    });
    tree.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
      if (!refreshing && selected != null && selected.getValue() != null && store != null
          && !store.isDirectory(selected.getValue())) open(selected.getValue(), false);
    });
    VBox.setVgrow(tree, Priority.ALWAYS);
    explorer.getChildren().setAll(tree);
  }

  private final class FileCell extends TreeCell<Path> {
    @Override protected void updateItem(Path path, boolean empty) {
      super.updateItem(path, empty);
      setText(null); setGraphic(null); setContextMenu(null);
      setOnDragDetected(null); setOnMouseClicked(null);
      setOnDragOver(null); setOnDragExited(null); setOnDragDropped(null);
      pseudoClassStateChanged(DROP_TARGET, false);
      if (empty || path == null || store == null) return;
      boolean directory = store.isDirectory(path);
      Label title = new Label(path.getFileName().toString());
      title.setMinWidth(0); title.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(title, Priority.ALWAYS);
      HBox row = new HBox(7, FxTheme.icon(directory ? "folder-open" : "file-text", 15), title);
      row.setAlignment(Pos.CENTER_LEFT); row.setMaxWidth(Double.MAX_VALUE);
      setGraphic(row); setAccessibleText(path.getFileName() + (directory ? ", folder" : ", Markdown note"));
      ContextMenu menu = new ContextMenu();
      if (directory) menu.getItems().addAll(item("New note", () -> newNote(path)), item("New folder…", () -> newFolder(path)));
      else menu.getItems().addAll(item("Open", () -> open(path, false)),
          item("Open side by side", () -> open(path, true)), item("Rename…", () -> rename(path)),
          new SeparatorMenuItem(), item("Move to deleted notes", () -> delete(path)));
      setContextMenu(menu);
      if (directory) {
        setOnDragOver(event -> {
          if (event.getDragboard().hasContent(NOTE_DRAG)) {
            event.acceptTransferModes(TransferMode.MOVE); pseudoClassStateChanged(DROP_TARGET, true); event.consume();
          }
        });
        setOnDragExited(event -> pseudoClassStateChanged(DROP_TARGET, false));
        setOnDragDropped(event -> {
          pseudoClassStateChanged(DROP_TARGET, false);
          boolean done = false;
          if (event.getDragboard().hasContent(NOTE_DRAG)) {
            try { done = moveNote(Path.of((String) event.getDragboard().getContent(NOTE_DRAG)), path); }
            catch (RuntimeException error) { FxDiyalamp.this.error("Note could not be moved", error); }
          }
          event.setDropCompleted(done); event.consume();
        });
      }
      if (!directory) setOnDragDetected(event -> {
        Dragboard board = startDragAndDrop(NOTE_DRAG_MODES.toArray(TransferMode[]::new));
        ClipboardContent content = new ClipboardContent();
        content.put(NOTE_DRAG, path.toString()); content.putFiles(List.of(path.toFile()));
        board.setContent(content); event.consume();
      });
      setOnMouseClicked(event -> {
        if (!directory && event.getClickCount() == 2) {
          open(path, false); if (active != null) active.editor.focus();
        }
      });
    }
  }

  private final class DirectoryItem extends TreeItem<Path> {
    boolean loaded;
    DirectoryItem(Path path) {
      super(path);
      if (store.isDirectory(path)) {
        getChildren().add(new TreeItem<>());
        expandedProperty().addListener((observable, previous, expanded) -> { if (expanded) populate(); });
      }
    }
    void populate() {
      if (loaded) return;
      loaded = true;
      try { getChildren().setAll(store.children(getValue()).stream().map(DirectoryItem::new).toList()); }
      catch (IOException e) { getChildren().clear(); error("Folder could not be read", e); }
    }
  }

  private void refreshTree() {
    if (store == null) return;
    if (noteGraph != null) noteGraph.filesChanged();
    Path selected = selectedPath();
    refreshing = true;
    try {
      String query = search.getText().strip().toLowerCase(java.util.Locale.ROOT);
      if (query.isEmpty()) {
        DirectoryItem rootItem = new DirectoryItem(store.root());
        rootItem.populate(); rootItem.setExpanded(true); tree.setRoot(rootItem);
      } else {
        TreeItem<Path> rootItem = new TreeItem<>(store.root()); rootItem.setExpanded(true);
        Map<Path, TreeItem<Path>> folders = new LinkedHashMap<>(); folders.put(store.root(), rootItem);
        for (Path path : store.notes()) {
          if (!store.root().relativize(path).toString().toLowerCase(java.util.Locale.ROOT).contains(query)) continue;
          TreeItem<Path> parent = ensureSearchFolder(path.getParent(), folders);
          parent.getChildren().add(new TreeItem<>(path));
        }
        tree.setRoot(rootItem);
      }
      if (selected != null) selectTree(tree.getRoot(), selected);
    } catch (IOException e) { error("Notes could not be listed", e); }
    finally { refreshing = false; }
  }

  private TreeItem<Path> ensureSearchFolder(Path path, Map<Path, TreeItem<Path>> folders) {
    if (folders.containsKey(path)) return folders.get(path);
    TreeItem<Path> parent = ensureSearchFolder(path.getParent(), folders);
    TreeItem<Path> item = new TreeItem<>(path); item.setExpanded(true);
    parent.getChildren().add(item); folders.put(path, item); return item;
  }

  private void openFirstSearchResult() {
    if (store == null || search.getText().isBlank()) return;
    for (int row = 0; row < tree.getExpandedItemCount(); row++) {
      Path path = tree.getTreeItem(row).getValue();
      if (path == null || store.isDirectory(path)) continue;
      searching.set(false);
      open(path, false);
      if (active != null) active.editor.focus();
      return;
    }
  }

  private Node emptyPage() {
    Label title = new Label("diyalamp"), detail = new Label("Open a Markdown note, or create one.");
    title.getStyleClass().add("workspace-empty-title"); detail.getStyleClass().add("empty-detail");
    Button choose = new Button("Open folder…"); choose.setOnAction(event -> chooseFolder());
    choose.getStyleClass().add("accent");
    Button create = new Button("New note"); create.setOnAction(event -> newNote(null));
    create.setDisable(store == null);
    HBox actions = new HBox(10, choose, create); actions.setAlignment(Pos.CENTER);
    HBox brand = new HBox(10, FxTheme.lamp(30), title);
    brand.setAlignment(Pos.CENTER);
    VBox empty = new VBox(14, brand, detail, actions);
    empty.setAlignment(Pos.CENTER); empty.getStyleClass().add("workspace-empty");
    return empty;
  }

  private static Region spacer() { Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); return spacer; }

  private static boolean within(Node node, Parent ancestor) {
    for (Node current = node; current != null; current = current.getParent()) if (current == ancestor) return true;
    return false;
  }

  private Path selectedPath() {
    TreeItem<Path> selected = tree.getSelectionModel().getSelectedItem();
    return selected == null ? null : selected.getValue();
  }

  private boolean selectTree(TreeItem<Path> item, Path path) {
    if (item == null) return false;
    if (path.equals(item.getValue())) { tree.getSelectionModel().select(item); return true; }
    if (item instanceof DirectoryItem directory && path.startsWith(directory.getValue())) {
      directory.populate(); directory.setExpanded(true);
    }
    for (TreeItem<Path> child : item.getChildren()) if (selectTree(child, path)) return true;
    return false;
  }

  private void activate(View view) {
    if (view.disposed) return;
    if (active != null && active != view && active.session.pendingView != null) flush(active.session);
    active = view;
    view.host.breadcrumb.setText(store.root().getFileName() + "  /  " + store.root().relativize(view.session.path));
    stage.setTitle(view.session.path.getFileName() + " · diyalamp");
    updateStatus();
  }

  private void chooseFolder() {
    DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Open Markdown folder");
    if (store != null) chooser.setInitialDirectory(store.root().toFile());
    File chosen = chooser.showDialog(stage);
    if (chosen != null) setFolder(chosen.toPath());
  }

  private void setFolder(Path folder) {
    if (!saveAll(true)) return;
    try {
      NoteStore next = new NoteStore(folder);
      disposeAll(); store = next; lastDeleted = null;
      if (noteGraph != null) noteGraph.workspace(store);
      primary.tabs.getTabs().clear(); secondary.tabs.getTabs().clear();
      split.getItems().setAll(primary.container);
      primary.container.setCenter(emptyPage()); secondary.container.setCenter(emptyPage());
      folderLabel.setText(store.root().toString());
      searching.set(false); refreshTree(); updateStatus();
      if (persistentFolder) preferences.put("folder", store.root().toString());
    } catch (IOException e) { error("Folder could not be opened", e); }
  }

  private void newNote(Path parent) {
    if (store == null) { chooseFolder(); if (store == null) return; }
    Path directory = parent;
    if (directory == null) {
      Path selected = selectedPath();
      directory = selected == null ? store.root() : store.isDirectory(selected) ? selected : selected.getParent();
    }
    TextInputDialog dialog = input("New note", "Note name", "Untitled");
    Optional<String> name = dialog.showAndWait();
    if (name.isEmpty()) return;
    try {
      Path created = store.create(directory, name.get(), "");
      searching.set(false); refreshTree(); open(created, false);
      if (active != null) active.editor.focus();
    } catch (IOException e) { error("Note could not be created", e); }
  }

  private void newFolder(Path parent) {
    if (store == null) return;
    Path selected = selectedPath();
    Path directory = parent != null ? parent : selected == null ? store.root()
        : store.isDirectory(selected) ? selected : selected.getParent();
    input("New folder", "Folder name", "New folder").showAndWait().ifPresent(name -> {
      try {
        Path folder = store.createFolder(directory, name);
        searching.set(false); refreshTree(); selectTree(tree.getRoot(), folder);
        tree.requestFocus(); status.setText("Folder created");
      } catch (IOException error) { error("Folder could not be created", error); }
    });
  }

  private boolean moveNote(Path path, Path directory) {
    if (store == null) return false;
    path = path.toAbsolutePath().normalize();
    Session session = sessions.get(path);
    if (session != null && !save(session, true)) return false;
    try {
      Path moved = store.move(path, directory);
      if (noteGraph != null) { noteGraph.saved(path); noteGraph.saved(moved); }
      if (session != null) {
        sessions.remove(path); session.path = moved; sessions.put(moved, session);
        session.views.forEach(this::updateView);
      }
      refreshTree();
      refreshing = true;
      try { selectTree(tree.getRoot(), moved); } finally { refreshing = false; }
      if (active != null) activate(active);
      status.setText("Note moved to " + directory.getFileName()); return true;
    } catch (IOException error) { error("Note could not be moved", error); return false; }
  }

  private void open(Path path, boolean beside) {
    if (store == null) return;
    try {
      path = path.toAbsolutePath().normalize();
      Session session = sessions.get(path);
      if (session == null) { session = new Session(path, store.load(path)); sessions.put(path, session); }
      else flush(session);
      PaneHost host = beside ? secondary : primary;
      if (beside) showSecondary();
      for (View view : session.views) if (view.host == host) {
        host.tabs.getSelectionModel().select(view.tab); activate(view); return;
      }
      View view = new View(session, host);
      host.tabs.getTabs().add(view.tab); host.tabs.getSelectionModel().select(view.tab);
      refreshing = true; selectTree(tree.getRoot(), path); refreshing = false;
      activate(view); updateView(view);
    } catch (IOException e) { error("Note could not be opened", e); }
  }

  private void changed(View source) {
    if (source.disposed) return;
    Session session = source.session;
    boolean wasDirty = session.dirty;
    session.dirty = true; session.pendingView = source; session.metrics = null; active = source;
    if (!wasDirty) for (View view : session.views) updateView(view);
    status.setText("Saving…");
    serialization.playFromStart(); autosave.playFromStart();
  }

  /** Expensive Markdown conversion runs once per short typing burst, or before a file action. */
  private boolean flush(Session session) {
    View source = session.pendingView;
    if (source == null) return session.blockedView == null;
    session.pendingView = null;
    String next;
    try { next = source.editor.markdown(); }
    catch (IllegalStateException e) {
      session.dirty = true; session.blockedView = source;
      session.error = "This formatting cannot be saved safely. Undo or simplify it, or export the visible text.";
      for (View view : session.views) {
        view.editor.node().setDisable(view != source); updateView(view);
      }
      autosave.stop(); updateStatus(); return false;
    }
    boolean recoveredFormatting = session.blockedView != null;
    if (recoveredFormatting) {
      session.blockedView = null; session.error = null;
      session.views.forEach(view -> view.editor.node().setDisable(false));
    }
    if (next.equals(session.text)) {
      if (recoveredFormatting) {
        session.views.forEach(this::updateView); autosave.playFromStart(); updateStatus();
      }
      return true;
    }
    session.text = next; session.dirty = true;
    for (View view : session.views) {
      if (view != source) view.editor.load(next);
      updateView(view);
    }
    updateStatus(); return true;
  }

  private boolean save(Session session, boolean explicit) {
    flush(session);
    if (!session.dirty) return true;
    if (session.blockedView != null) {
      if (explicit) error("Note has not been saved", new IOException(session.error));
      return false;
    }
    try {
      session.fingerprint = store.save(session.path, session.text, session.fingerprint);
      session.dirty = false; session.error = null;
      if (noteGraph != null) noteGraph.saved(session.path);
      session.views.forEach(this::updateView); updateStatus(); return true;
    } catch (IOException e) {
      session.error = e.getMessage(); session.views.forEach(this::updateView); updateStatus();
      if (explicit) error("Note has not been saved", e);
      return false;
    }
  }

  private boolean saveAll(boolean explicit) {
    autosave.stop(); serialization.stop();
    boolean saved = true;
    for (Session session : new ArrayList<>(sessions.values()))
      if (!save(session, explicit && saved)) saved = false;
    return saved;
  }

  private void updateView(View view) {
    Session session = view.session;
    view.editor.node().setDisable(session.blockedView != null && session.blockedView != view);
    view.tab.setText(session.path.getFileName() + (session.dirty ? " •" : ""));
    if (session.error == null) { view.content.setTop(null); return; }
    Label message = new Label(session.blockedView == null ? "Not saved. Your edits are still open."
        : "Formatting needs attention. Undo, simplify, or export visible text.");
    message.setTooltip(new Tooltip(session.error)); message.setWrapText(true);
    Button export = new Button("Export copy"); export.getStyleClass().add("flat");
    export.setOnAction(event -> export(session));
    Button reload = new Button("Reload"); reload.getStyleClass().add("flat");
    reload.setOnAction(event -> reload(session));
    HBox banner = new HBox(10, message, spacer(), export, reload);
    banner.setAlignment(Pos.CENTER_LEFT); banner.getStyleClass().add("save-warning");
    view.content.setTop(banner);
  }

  private void updateStatus() {
    if (noteGraph != null) noteGraph.select(active == null ? null : active.session.path);
    if (active == null) {
      metrics.setVisible(false); metrics.setManaged(false); status.setText(store == null ? "Choose a folder to start writing" : "Local Markdown files");
      if (stage != null) stage.setTitle("diyalamp"); return;
    }
    Session session = active.session;
    metrics.setVisible(true); metrics.setManaged(true);
    if (session.metrics == null || session.metricsSource != session.text) {
      session.metrics = NoteMetrics.count(active.editor.recoveryText(), session.text);
      session.metricsSource = session.text;
    }
    NoteMetrics count = session.metrics;
    characters.setText(count.characters() + " chars");
    words.setText(count.words() + " words");
    lines.setText(count.lines() + " lines");
    fileSize.setText(count.fileSize());
    fileSize.getTooltip().setText(count.utf8Bytes() + " UTF-8 bytes of "
        + (session.blockedView == null ? "current Markdown, including unsaved edits" : "last safely serialized Markdown"));
    status.setText(session.blockedView != null ? "Not saved · undo, simplify formatting, or export visible text"
        : session.error != null ? "Not saved · export or reload to continue"
        : session.dirty ? "Saving…" : "All changes saved");
  }

  private static Label metricLabel(String icon, String description) {
    Label label = new Label("", FxTheme.icon(icon, 16));
    label.getStyleClass().add("note-metric");
    label.setTooltip(new Tooltip(description)); label.setAccessibleHelp(description);
    label.setMinWidth(Region.USE_PREF_SIZE);
    return label;
  }

  private void export(Session session) {
    if (session == null) return;
    flush(session);
    boolean visibleRecovery = session.blockedView != null;
    FileChooser chooser = visibleRecovery ? new FileChooser() : markdownChooser("Export Markdown copy");
    if (visibleRecovery) {
      chooser.setTitle("Export visible text recovery");
      chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Plain text recovery", "*.txt"));
    }
    chooser.setInitialFileName(session.path.getFileName() + (visibleRecovery ? ".recovery.txt" : ""));
    File destination = chooser.showSaveDialog(stage);
    if (destination == null) return;
    try {
      Path path = destination.toPath().toAbsolutePath().normalize();
      for (Session open : sessions.values())
        if (path.equals(open.path) || (Files.exists(path) && Files.isSameFile(path, open.path))) {
          error("Choose a different export name", new IOException(
              "This file is open in diyalamp. Save uses conflict checks; export your copy to a different file."));
          return;
        }
      Files.writeString(path, visibleRecovery ? session.blockedView.editor.recoveryText() : session.text,
          StandardCharsets.UTF_8);
      status.setText(visibleRecovery ? "Visible text exported · formatting is not included" : "Markdown copy exported");
    } catch (IOException e) { error("Copy could not be exported", e); }
  }

  private void importNote() {
    if (store == null) { chooseFolder(); if (store == null) return; }
    File chosen = markdownChooser("Import Markdown").showOpenDialog(stage);
    if (chosen != null) importNote(chosen.toPath());
  }

  private void importNote(Path source) {
    if (store == null) return;
    try {
      if (!NoteStore.isMarkdown(source)) throw new IOException("Choose a .md or .markdown file.");
      if (source.toAbsolutePath().normalize().startsWith(store.root())) { open(source, false); return; }
      if (Files.size(source) > NoteStore.MAX_BYTES) throw new IOException("This file is larger than the 5 MB editing limit.");
      String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
          .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
          .decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(source))).toString();
      Path imported = store.create(source.getFileName().toString(), text);
      refreshTree(); open(imported, false);
    } catch (IOException e) { error("Markdown could not be imported", e); }
  }

  private void reload(Session session) {
    if (session == null) return;
    flush(session);
    if (session.dirty && !confirm("Reload note from disk?",
        "This replaces the unsaved text in this note. Export a copy first if you want to keep it.")) return;
    try {
      NoteStore.Loaded loaded = store.load(session.path);
      session.text = loaded.text(); session.fingerprint = loaded.fingerprint();
      session.dirty = false; session.error = null; session.blockedView = null;
      for (View view : session.views) { view.editor.load(session.text); updateView(view); }
      updateStatus();
    } catch (IOException e) { error("Note could not be reloaded", e); }
  }

  private void rename(Path path) {
    Session session = sessions.get(path);
    if (session != null && !save(session, true)) return;
    Optional<String> name = input("Rename note", "Note name", path.getFileName().toString()).showAndWait();
    if (name.isEmpty()) return;
    try {
      Path renamed = store.rename(path, name.get());
      if (session != null) {
        sessions.remove(path); session.path = renamed; sessions.put(renamed, session);
        session.views.forEach(this::updateView);
      }
      refreshTree();
      if (active != null) activate(active);
    } catch (IOException e) { error("Note could not be renamed", e); }
  }

  private void delete(Path path) {
    if (store == null || path == null || store.isDirectory(path)) return;
    Session session = sessions.get(path);
    if (session != null && !save(session, true)) return;
    try {
      lastDeleted = store.trash(path);
      if (session != null) for (View view : new ArrayList<>(session.views)) {
        view.host.tabs.getTabs().remove(view.tab); removeView(view);
      }
      refreshTree(); status.setText("Note moved to deleted notes · File → Restore last deleted note");
    } catch (IOException e) { error("Note could not be deleted", e); }
  }

  private void restoreLast() {
    if (store == null || lastDeleted == null) { deletedNotes(); return; }
    restore(lastDeleted);
  }

  private void restore(NoteStore.Deleted deleted) {
    try {
      Path restored = store.restore(deleted); if (deleted.equals(lastDeleted)) lastDeleted = null;
      refreshTree(); open(restored, false); status.setText("Note restored");
    } catch (IOException e) { error("Note could not be restored", e); }
  }

  private void deletedNotes() {
    if (store == null) return;
    try {
      List<NoteStore.Deleted> deleted = store.deleted();
      if (deleted.isEmpty()) { information("Deleted notes", "There are no deleted notes in this folder."); return; }
      ChoiceDialog<NoteStore.Deleted> dialog = new ChoiceDialog<>(deleted.getFirst(), deleted);
      dialog.setTitle("Restore a deleted note"); dialog.setHeaderText(null); dialog.setContentText("Note");
      FxDialogs.style(dialog, stage);
      @SuppressWarnings("unchecked") ComboBox<NoteStore.Deleted> choices =
          (ComboBox<NoteStore.Deleted>) dialog.getDialogPane().lookup(".combo-box");
      if (choices != null) {
        choices.setConverter(new javafx.util.StringConverter<>() {
          @Override public String toString(NoteStore.Deleted note) {
            return note == null ? "" : store.root().relativize(note.original()).toString();
          }
          @Override public NoteStore.Deleted fromString(String text) { return null; }
        });
      }
      dialog.showAndWait().ifPresent(this::restore);
    } catch (IOException e) { error("Deleted notes could not be read", e); }
  }

  private void closeActive() {
    if (active == null || !save(active.session, true)) return;
    View view = active; view.host.tabs.getTabs().remove(view.tab); removeView(view);
  }

  private void removeView(View view) {
    if (view.disposed) return;
    view.disposed = true; view.editor.dispose(); view.session.views.remove(view);
    if (view.session.views.isEmpty()) sessions.remove(view.session.path);
    if (active == view) active = null;
    if (view.host.tabs.getTabs().isEmpty()) {
      view.host.container.setCenter(emptyPage()); view.host.breadcrumb.setText("");
      if (view.host == secondary) split.getItems().remove(secondary.container);
    }
    Tab selected = view.host.tabs.getSelectionModel().getSelectedItem();
    if (selected != null && selected.getUserData() instanceof View next) activate(next);
    else if (!primary.tabs.getTabs().isEmpty()) {
      Tab tab = primary.tabs.getSelectionModel().getSelectedItem();
      if (tab != null) activate((View) tab.getUserData());
    }
    updateStatus();
  }

  private void disposeAll() {
    autosave.stop(); serialization.stop();
    for (Session session : sessions.values()) for (View view : session.views) {
      view.disposed = true; view.editor.dispose();
    }
    sessions.clear(); active = null;
  }

  private void showSecondary() {
    if (!split.getItems().contains(secondary.container)) {
      split.getItems().add(secondary.container); split.setDividerPositions(.5);
    }
  }

  private void splitActive() { if (active != null) open(active.session.path, true); }

  private void singlePane() {
    if (!saveAll(true)) return;
    for (Tab tab : new ArrayList<>(secondary.tabs.getTabs())) {
      View view = (View) tab.getUserData();
      View existing = view.session.views.stream().filter(other -> other.host == primary).findFirst().orElse(null);
      secondary.tabs.getTabs().remove(tab);
      if (existing != null) removeView(view);
      else {
        secondary.container.setCenter(null); view.host = primary;
        view.tab = view.newTab(tab.getId()); updateView(view);
        primary.tabs.getTabs().add(view.tab);
      }
    }
    split.getItems().setAll(primary.container);
    if (!primary.tabs.getTabs().isEmpty()) primary.tabs.getSelectionModel().selectLast();
  }

  private void installDrop(PaneHost host) {
    host.container.setOnDragOver(event -> {
      if (event.getDragboard().hasContent(NOTE_DRAG) || event.getDragboard().hasFiles()) {
        event.acceptTransferModes(paneDropMode(event.getDragboard().hasContent(TAB_DRAG)));
        host.container.pseudoClassStateChanged(DROP_TARGET, true); event.consume();
      }
    });
    host.container.setOnDragExited(event -> host.container.pseudoClassStateChanged(DROP_TARGET, false));
    host.container.setOnDragDropped(event -> {
      host.container.pseudoClassStateChanged(DROP_TARGET, false);
      boolean done = false;
      if (store != null) {
        Dragboard board = event.getDragboard();
        boolean beside = host == secondary || event.getX() > host.container.getWidth() * .70;
        try {
          if (board.hasContent(TAB_DRAG)) {
            View dragged = draggedView((String) board.getContent(TAB_DRAG));
            if (dragged != null) done = moveView(dragged, beside ? secondary : primary);
          } else if (board.hasContent(NOTE_DRAG)) { open(Path.of((String) board.getContent(NOTE_DRAG)), beside); done = true; }
          else if (board.hasFiles()) for (File file : board.getFiles()) {
            if (!NoteStore.isMarkdown(file.toPath())) continue;
            if (file.toPath().toAbsolutePath().normalize().startsWith(store.root())) open(file.toPath(), beside);
            else importNote(file.toPath());
            done = true;
          }
        } catch (RuntimeException e) { error("Note could not be dropped", e); }
      }
      event.setDropCompleted(done); event.consume();
    });
  }

  /** Start the one native gesture immediately, so it can reach headers, panes, and folders. */
  private void installTabDrag(PaneHost host) {
    TabSkin[] candidate = {null};
    host.tabs.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
      candidate[0] = event.getButton() == MouseButton.PRIMARY ? tabSkin(event.getTarget()) : null;
    });
    host.tabs.addEventFilter(MouseEvent.DRAG_DETECTED, event -> {
      TabSkin skin = candidate[0];
      if (skin == null || !(skin.getTab().getUserData() instanceof View view)) return;
      Dragboard board = skin.startDragAndDrop(TAB_DRAG_MODES.toArray(TransferMode[]::new));
      ClipboardContent content = new ClipboardContent();
      content.put(NOTE_DRAG, view.session.path.toString()); content.put(TAB_DRAG, view.tab.getId());
      board.setContent(content);
      board.setDragView(skin.snapshot(null, null));
      candidate[0] = null; event.consume();
    });
    host.tabs.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> candidate[0] = null);
    host.tabs.setOnDragOver(event -> {
      if (event.getDragboard().hasContent(TAB_DRAG)
          && draggedView((String) event.getDragboard().getContent(TAB_DRAG)) != null) {
        event.acceptTransferModes(TransferMode.MOVE); event.consume();
      }
    });
    host.tabs.setOnDragDropped(event -> {
      if (!event.getDragboard().hasContent(TAB_DRAG)) return;
      View view = draggedView((String) event.getDragboard().getContent(TAB_DRAG));
      int insertion = tabInsertionIndex(host, event.getSceneX());
      boolean moved = view != null && moveView(view, host);
      if (moved) host.tabs.moveToInsertion(view.tab, insertion);
      event.setDropCompleted(moved); event.consume();
    });
  }

  private int tabInsertionIndex(PaneHost host, double sceneX) {
    for (int index = 0; index < host.tabs.getTabs().size(); index++) {
      Tab tab = host.tabs.getTabs().get(index);
      for (Node node : host.tabs.lookupAll(".tab")) {
        if (node instanceof TabSkin skin && skin.getTab() == tab
            && sceneX < skin.localToScene(skin.getBoundsInLocal()).getCenterX()) return index;
      }
    }
    return host.tabs.getTabs().size();
  }

  private TabSkin tabSkin(Object target) {
    if (!(target instanceof Node node)) return null;
    for (Node current = node; current != null; current = current.getParent()) {
      if (current instanceof ButtonBase || current.getStyleClass().contains("close-button")) return null;
      if (current instanceof TabSkin skin) return skin;
    }
    return null;
  }

  private View draggedView(String id) {
    for (Session session : sessions.values()) for (View view : session.views)
      if (view.tab.getId().equals(id)) return view;
    return null;
  }

  private boolean moveView(View view, PaneHost target) {
    if (view.disposed || !flush(view.session)) return false;
    if (view.host == target) { target.tabs.getSelectionModel().select(view.tab); return true; }
    View existing = view.session.views.stream().filter(other -> other != view && other.host == target)
        .findFirst().orElse(null);
    if (existing != null) {
      target.tabs.getTabs().remove(existing.tab); removeView(existing);
    }
    PaneHost previous = view.host;
    previous.tabs.getTabs().remove(view.tab);
    if (previous.container.getCenter() == view.content) previous.container.setCenter(emptyPage());
    view.host = target;
    // TabLine skins retain selection listeners while their removal animation finishes.
    // A fresh tab model avoids selecting a detached skin; the actual editor and history stay intact.
    view.tab = view.newTab(view.tab.getId()); updateView(view);
    if (target == secondary) showSecondary();
    target.tabs.getTabs().add(view.tab); target.tabs.getSelectionModel().select(view.tab);
    if (previous == secondary && previous.tabs.getTabs().isEmpty()) split.getItems().remove(secondary.container);
    activate(view); view.editor.focus(); return true;
  }

  private void toggleSidebar() {
    captureSidebarWidths();
    sidebarOpen = !sidebarOpen;
    if (!sidebarOpen) searching.set(false);
    preferences.putBoolean("filesVisible", sidebarOpen);
    layoutSidebars(true);
  }

  private double sidebarWidth(String key, double fallback, double minimum) {
    double value = preferences.getDouble(key, fallback);
    return !Double.isFinite(value) || value <= 0 ? fallback : Math.max(minimum, Math.min(600, value));
  }

  private void configureSidebars() {
    sidebarLayoutChanging = true;
    body.getStyleClass().addAll("writing-split", "workspace-sidebars"); body.setMinWidth(0);
    explorerClip.setMinWidth(160); explorerClip.setMaxWidth(600);
    ((Region) noteGraph.node()).setMinWidth(200); ((Region) noteGraph.node()).setMaxWidth(600);
    split.setMinWidth(MIN_WRITING_WIDTH);
    SplitPane.setResizableWithParent(explorerClip, false);
    SplitPane.setResizableWithParent(noteGraph.node(), false);
    body.getItems().add(split);
    if (sidebarOpen) body.getItems().addFirst(explorerClip);
    if (noteGraph.showingProperty().get()) body.getItems().add(noteGraph.node());
    explorerClip.setVisible(sidebarOpen); explorerClip.setManaged(sidebarOpen);
    explorer.setOpacity(sidebarOpen ? 1 : 0);
    sidebarSave.setOnFinished(event -> persistSidebarWidths());
    explorerClip.widthProperty().addListener((observable, previous, width) -> captureSidebarWidths());
    noteGraph.node().layoutBoundsProperty().addListener((observable, previous, bounds) -> captureSidebarWidths());
    body.widthProperty().addListener((observable, previous, width) -> queueSidebarLayout());
    split.getItems().addListener((javafx.collections.ListChangeListener<Node>) change -> queueSidebarLayout());
    sidebarLayoutReady = true;
  }

  private void captureSidebarWidths() {
    if (!sidebarLayoutReady || sidebarLayoutChanging || body.getWidth() <= 0) return;
    if (sidebarOpen && body.getItems().contains(explorerClip) && explorerClip.getWidth() >= 160)
      filesWidth = Math.min(600, explorerClip.getWidth());
    if (noteGraph.showingProperty().get() && body.getItems().contains(noteGraph.node())
        && noteGraph.node().getLayoutBounds().getWidth() >= 200)
      connectionsWidth = Math.min(600, noteGraph.node().getLayoutBounds().getWidth());
    sidebarSave.playFromStart();
  }

  private void persistSidebarWidths() {
    preferences.putDouble("filesWidth", filesWidth);
    preferences.putDouble("connectionsWidth", connectionsWidth);
  }

  private void queueSidebarLayout() {
    if (!sidebarLayoutReady || sidebarLayoutQueued) return;
    sidebarLayoutQueued = true; sidebarLayoutChanging = true;
    Platform.runLater(() -> { sidebarLayoutQueued = false; if (sidebarLayoutReady) layoutSidebars(false); });
  }

  private void resetSidebarWidths() {
    filesWidth = SIDEBAR_WIDTH; connectionsWidth = CONNECTIONS_WIDTH;
    persistSidebarWidths(); layoutSidebars(true);
  }

  /** Native SplitPane owns divider dragging; only visibility toggles animate its existing dividers. */
  private void layoutSidebars(boolean animate) {
    if (!sidebarLayoutReady || body.getWidth() <= 0) return;
    if (sidebarAnimation != null) sidebarAnimation.stop();
    sidebarLayoutChanging = true;
    double oldLeft = body.getItems().contains(explorerClip) ? explorerClip.getWidth() : 0;
    double oldRight = body.getItems().contains(noteGraph.node()) ? noteGraph.node().getLayoutBounds().getWidth() : 0;
    if (sidebarOpen && !body.getItems().contains(explorerClip)) {
      explorerClip.setVisible(true); explorerClip.setManaged(true); body.getItems().addFirst(explorerClip);
    }
    if (noteGraph.showingProperty().get() && !body.getItems().contains(noteGraph.node())) body.getItems().add(noteGraph.node());
    explorerClip.setMinWidth(0); ((Region) noteGraph.node()).setMinWidth(0);
    double width = body.getWidth();
    double writingMinimum = Math.max(MIN_WRITING_WIDTH, split.getItems().size() * 200.0
        + Math.max(0, split.getItems().size() - 1));
    split.setMinWidth(writingMinimum);
    double left = sidebarOpen ? filesWidth : 0, right = noteGraph.showingProperty().get() ? connectionsWidth : 0;
    double available = Math.max(0, width - writingMinimum - body.getDividers().size() * SIDEBAR_DIVIDER_WIDTH);
    if (left + right > available) {
      double factor = available / (left + right); left *= factor; right *= factor;
    }
    double[] start = sidebarPositions(oldLeft, oldRight, width), target = sidebarPositions(left, right, width);
    if (animate && !Boolean.getBoolean("diyalamp.reduceMotion") && target.length > 0) {
      body.setDividerPositions(start);
      ArrayList<KeyValue> values = new ArrayList<>();
      for (int i = 0; i < target.length; i++)
        values.add(new KeyValue(body.getDividers().get(i).positionProperty(), target[i], Interpolator.EASE_BOTH));
      values.add(new KeyValue(explorer.opacityProperty(), sidebarOpen ? 1 : 0, Interpolator.EASE_BOTH));
      sidebarAnimation = new Timeline(new KeyFrame(Duration.millis(180), values.toArray(KeyValue[]::new)));
      sidebarAnimation.setOnFinished(event -> finishSidebarLayout()); sidebarAnimation.play();
    } else {
      body.setDividerPositions(target); explorer.setOpacity(sidebarOpen ? 1 : 0); finishSidebarLayout();
    }
  }

  private double[] sidebarPositions(double left, double right, double width) {
    boolean hasLeft = body.getItems().contains(explorerClip), hasRight = body.getItems().contains(noteGraph.node());
    double half = SIDEBAR_DIVIDER_WIDTH / 2;
    if (hasLeft && hasRight) return new double[] {(left + half) / width, 1 - (right + half) / width};
    if (hasLeft) return new double[] {(left + half) / width};
    if (hasRight) return new double[] {1 - (right + half) / width};
    return new double[0];
  }

  private void finishSidebarLayout() {
    if (!sidebarOpen) body.getItems().remove(explorerClip);
    if (!noteGraph.showingProperty().get()) body.getItems().remove(noteGraph.node());
    explorerClip.setVisible(sidebarOpen); explorerClip.setManaged(sidebarOpen);
    explorerClip.setMinWidth(160); ((Region) noteGraph.node()).setMinWidth(200);
    sidebarAnimation = null;
    body.applyCss(); body.layout();
    body.setDividerPositions(sidebarPositions(sidebarOpen ? Math.min(filesWidth, explorerClip.getWidth()) : 0,
        noteGraph.showingProperty().get() ? Math.min(connectionsWidth, noteGraph.node().getLayoutBounds().getWidth()) : 0,
        body.getWidth()));
    body.layout(); sidebarLayoutChanging = false; persistSidebarWidths();
  }

  private static StackPane clipped(Region child) {
    StackPane pane = new StackPane(child);
    pane.setAlignment(Pos.TOP_LEFT); pane.setMinSize(0, 0);
    pane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
    Rectangle clip = new Rectangle();
    clip.widthProperty().bind(pane.widthProperty()); clip.heightProperty().bind(pane.heightProperty());
    pane.setClip(clip); return pane;
  }

  private void toggleZen() { zenMode.set(!zenMode.get()); }

  /** Line focus shares one reversible clock; workspace chrome never moves or disappears. */
  private void animateZen(boolean enabled) {
    if (zenAnimation != null) zenAnimation.stop();
    double current = zenProgress.get(), target = enabled ? 1 : 0;
    if (Boolean.getBoolean("diyalamp.reduceMotion") || current == target) {
      zenProgress.set(target); zenAnimation = null; return;
    }
    Timeline transition = new Timeline(
        new KeyFrame(Duration.ZERO, new KeyValue(zenProgress, current)),
        new KeyFrame(Duration.millis(280 * Math.max(.2, Math.abs(target - current))),
            new KeyValue(zenProgress, target, Interpolator.EASE_BOTH)));
    zenAnimation = transition;
    transition.setOnFinished(event -> { zenProgress.set(target); zenAnimation = null; });
    transition.play();
  }

  private MenuBar buildMenu() {
    MenuBar menu = new MenuBar(); menu.setUseSystemMenuBar(true);
    Menu file = new Menu("File"), edit = new Menu("Edit"), format = new Menu("Format"),
        view = new Menu("View"), help = new Menu("Help");
    file.getItems().addAll(shortcut("New note", KeyCode.N, false, () -> newNote(null)),
        shortcut("Open folder…", KeyCode.O, false, this::chooseFolder), item("Import Markdown…", this::importNote),
        new SeparatorMenuItem(), shortcut("Save all", KeyCode.S, false, () -> saveAll(true)),
        item("Export active note…", () -> { if (active != null) export(active.session); }),
        item("Reload note from disk…", () -> { if (active != null) reload(active.session); }),
        new SeparatorMenuItem(), shortcut("Rename note…", KeyCode.R, true,
            () -> { if (active != null) rename(active.session.path); }),
        item("Move note to deleted notes", () -> { if (active != null) delete(active.session.path); }),
        item("Restore last deleted note", this::restoreLast), item("Deleted notes…", this::deletedNotes),
        item("Open recovery folder…", this::openRecoveryFolder),
        new SeparatorMenuItem(), shortcut("Close note", KeyCode.W, false, this::closeActive));
    edit.getItems().addAll(shortcut("Undo", KeyCode.Z, false, () -> edit("undo")),
        shortcut("Redo", KeyCode.Z, true, () -> edit("redo")), new SeparatorMenuItem(),
        shortcut("Cut", KeyCode.X, false, () -> edit("cut")),
        shortcut("Copy", KeyCode.C, false, () -> edit("copy")),
        shortcut("Paste", KeyCode.V, false, () -> edit("paste")),
        shortcut("Select all", KeyCode.A, false, () -> edit("select")),
        new SeparatorMenuItem(), shortcut("Find in note…", KeyCode.F, false, this::findInNote),
        shortcut("Search notes…", KeyCode.P, false, () -> { if (store != null) searching.set(!searching.get()); }));
    format.getItems().addAll(shortcut("Bold", KeyCode.B, false, () -> format("bold")),
        shortcut("Italic", KeyCode.I, false, () -> format("italic")),
        shortcut("Inline code", KeyCode.BACK_QUOTE, false, () -> format("code")),
        shortcut("Insert or edit link…", KeyCode.K, false, () -> { if (active != null) active.editor.insertLink(); }),
        new SeparatorMenuItem(), item("Paragraph", () -> block("paragraph", 1)),
        item("Heading 1", () -> block("heading", 1)), item("Heading 2", () -> block("heading", 2)),
        item("Heading 3", () -> block("heading", 3)), item("Bullet list", () -> block("bullet", 1)),
        item("Numbered list", () -> block("ordered", 1)), item("Checklist", () -> block("task", 1)),
        shortcut("Toggle checklist item", KeyCode.ENTER, true, () -> { if (active != null) active.editor.toggleChecklist(); }),
        item("Quote", () -> block("quote", 1)), item("Code block", () -> block("code", 1)),
        item("Divider", () -> block("divider", 1)));
    view.getItems().addAll(shortcut("Toggle files sidebar", KeyCode.B, true, this::toggleSidebar),
        shortcut("Side by side", KeyCode.BACK_SLASH, false, this::splitActive),
        item("Single pane", this::singlePane), new SeparatorMenuItem(), item("Accent color…", this::chooseAccent));
    MenuItem zen = shortcut("Enter Zen mode", KeyCode.F, true, this::toggleZen);
    zen.textProperty().bind(Bindings.when(zenMode).then("Exit Zen mode").otherwise("Enter Zen mode"));
    view.getItems().addAll(new SeparatorMenuItem(), zen);
    CheckMenuItem connections = new CheckMenuItem("Note connections");
    if (noteGraph != null) connections.selectedProperty().bindBidirectional(noteGraph.showingProperty());
    view.getItems().add(connections);
    view.getItems().addAll(new SeparatorMenuItem(), item("Pomodoro…", () -> { if (pomodoro != null) pomodoro.show(); }));
    help.getItems().addAll(item("Check for updates…", () -> updates.check()), new SeparatorMenuItem(),
        item("Keyboard shortcuts", () -> information("Keyboard shortcuts",
        "⌘N  New note\n⌘O  Open folder\n⌘P  Search notes\n⌘S  Save\n⌘W  Close note\n⌘K  Insert or edit link\n"
        + "⇧⌘Enter  Toggle checklist\n⇧⌘B  Toggle sidebar\n⌘\\  Side by side\n\n"
        + "Type / on a new line for blocks. Drag a note to the right edge to open it beside your writing.\n"
        + "Deleted notes can be restored from the File menu.")),
        item("About diyalamp", () -> information("diyalamp", "Version " + DesktopLauncher.VERSION
            + "\nOpen-source Markdown editor for macOS.\nLocal files. No account required.\n\nApache License 2.0")));
    menu.getMenus().setAll(file, edit, format, view, help); return menu;
  }

  private void format(String kind) {
    if (editableActive() && !(stage.getScene().getFocusOwner() instanceof TextInputControl)) active.editor.format(kind);
  }
  private void block(String kind, int level) { if (editableActive()) active.editor.block(kind, level); }
  private boolean editableActive() { return active != null && !active.editor.node().isDisabled(); }

  private void edit(String action) {
    if (stage.getScene().getFocusOwner() instanceof TextInputControl field) {
      switch (action) {
        case "undo" -> field.undo(); case "redo" -> field.redo(); case "cut" -> field.cut();
        case "copy" -> field.copy(); case "paste" -> field.paste(); case "select" -> field.selectAll();
        default -> { }
      }
    } else if (editableActive()) {
      switch (action) {
        case "undo" -> active.editor.undo(); case "redo" -> active.editor.redo();
        case "cut" -> active.editor.cut(); case "copy" -> active.editor.copy();
        case "paste" -> active.editor.paste(); case "select" -> active.editor.selectAll();
        default -> { }
      }
    }
  }

  private void findInNote() {
    if (active == null) return;
    input("Find in note", "Text", "").showAndWait().ifPresent(query -> {
      if (!query.isBlank()) active.editor.find(query);
    });
  }

  private void chooseAccent() {
    Button anchor = accentButton();
    if (anchor.getScene() == null) return;
    if (accentMenu == null) accentMenu = accentPopover();
    if (accentMenu.isShowing()) accentMenu.hide(); else accentMenu.show(anchor);
  }

  private Button accentButton() {
    if (accentControl != null) return accentControl;
    accentControl = new Button(null, accentSwatch(displayedAccent));
    accentControl.setContentDisplay(ContentDisplay.RIGHT);
    accentControl.setTooltip(new Tooltip("Workspace accent color"));
    accentControl.setAccessibleText("Workspace accent color");
    accentControl.setAccessibleHelp("Choose from six muted accent colors. Changes apply immediately.");
    accentControl.getStyleClass().addAll("workspace-tool", "workspace-accent-button");
    accentControl.setOnAction(event -> chooseAccent());
    return accentControl;
  }

  private void initializeAccent() {
    try {
      String chosen = preferences.get("accent", FxTheme.hex(FxTheme.DEFAULT_ACCENT));
      if (chosen.equalsIgnoreCase("#7B8FB7") || chosen.equalsIgnoreCase("#0078D4"))
        chosen = FxTheme.hex(FxTheme.DEFAULT_ACCENT);
      accent.set(Color.web(chosen));
    }
    catch (IllegalArgumentException e) { accent.set(FxTheme.DEFAULT_ACCENT); }
    displayedAccent.set(accent.get());
    FxTheme.accent(root, displayedAccent.get());
    displayedAccent.addListener((observable, previous, color) -> FxTheme.accent(root, color));
    accent.addListener((observable, previous, color) -> {
      // Only the chosen target is persisted; animation frames never write preferences.
      preferences.put("accent", FxTheme.hex(color));
      animateAccent(color);
    });
  }

  private void animateAccent(Color target) {
    Color current = displayedAccent.get();
    if (accentAnimation != null) accentAnimation.stop();
    displayedAccent.set(current);
    if (current.equals(target)) { accentAnimation = null; return; }
    Timeline transition = new Timeline(
        new KeyFrame(Duration.ZERO, new KeyValue(displayedAccent, current)),
        new KeyFrame(Duration.millis(280), new KeyValue(displayedAccent, target, Interpolator.EASE_BOTH)));
    accentAnimation = transition;
    transition.setOnFinished(event -> { displayedAccent.set(target); accentAnimation = null; });
    transition.play();
  }

  private Popover accentPopover() {
    Label title = new Label("Accent color"); title.getStyleClass().add("popover-title");
    GridPane choices = new GridPane(); choices.setHgap(8); choices.setVgap(8);
    choices.getStyleClass().add("accent-choices");
    for (int i = 0; i < ACCENT_CHOICES.length; i++) {
      String name = ACCENT_CHOICES[i][0];
      Color value = Color.web(ACCENT_CHOICES[i][1]);
      Button choice = new Button(name, accentSwatch(new SimpleObjectProperty<>(value)));
      choice.setContentDisplay(ContentDisplay.LEFT);
      choice.getStyleClass().add("accent-choice");
      choice.setMaxWidth(Double.MAX_VALUE);
      GridPane.setHgrow(choice, Priority.ALWAYS);
      Runnable state = () -> {
        boolean current = value.equals(accent.get());
        choice.setText(name + (current ? " ✓" : ""));
        choice.setAccessibleText(name + " accent color" + (current ? ", selected" : ""));
        choice.pseudoClassStateChanged(ACCENT_SELECTED, current);
      };
      accent.addListener((observable, previous, next) -> state.run()); state.run();
      choice.setOnAction(event -> accent.set(value));
      choices.add(choice, i % 2, i / 2);
    }
    VBox content = new VBox(16, title, choices); content.getStyleClass().add("accent-bubble"); content.setPrefWidth(320);
    Popover popup = new Popover(content); popup.setTitle("Accent color");
    FxPopovers.style(popup, stage);
    return popup;
  }

  private static Region accentSwatch(ObjectProperty<Color> color) {
    Region swatch = new Region(); swatch.getStyleClass().add("accent-swatch");
    swatch.setMouseTransparent(true);
    swatch.styleProperty().bind(Bindings.createStringBinding(
        () -> "-fx-background-color: " + FxTheme.hex(color.get()) + ";", color));
    return swatch;
  }

  private MenuItem shortcut(String title, KeyCode code, boolean shift, Runnable action) {
    MenuItem item = item(title, action);
    item.setAccelerator(shift ? new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
        : new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN));
    return item;
  }

  private MenuItem item(String title, Runnable action) {
    MenuItem item = new MenuItem(title); item.setOnAction(event -> action.run()); return item;
  }

  private TextInputDialog input(String title, String label, String initial) {
    TextInputDialog dialog = new TextInputDialog(initial);
    dialog.setTitle(title); dialog.setHeaderText(null); dialog.setContentText(label);
    FxDialogs.style(dialog, stage);
    dialog.getEditor().setPrefWidth(250); return dialog;
  }

  private FileChooser markdownChooser(String title) {
    FileChooser chooser = new FileChooser(); chooser.setTitle(title);
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Markdown files", "*.md", "*.markdown"));
    return chooser;
  }

  private boolean confirm(String title, String message) {
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, ButtonType.OK);
    alert.setTitle(title); alert.setHeaderText(null); FxDialogs.style(alert, stage);
    return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
  }

  private void information(String title, String message) {
    Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
    alert.setTitle(title); alert.setHeaderText(null); FxDialogs.style(alert, stage); alert.showAndWait();
  }

  private void error(String title, Exception exception) {
    Alert alert = new Alert(Alert.AlertType.ERROR,
        exception.getMessage() == null ? title : exception.getMessage(), ButtonType.OK);
    alert.setTitle(title); alert.setHeaderText(null); FxDialogs.style(alert, stage); alert.showAndWait();
  }

  /** Native application quit can bypass a window close request. Preserve any blocked edits. */
  @Override public void stop() {
    sidebarLayoutReady = false;
    if (sidebarAnimation != null) sidebarAnimation.stop();
    sidebarSave.stop(); persistSidebarWidths();
    if (accentAnimation != null) accentAnimation.stop();
    if (pomodoro != null) pomodoro.close();
    if (zenAnimation != null) zenAnimation.stop();
    if (updates != null) updates.close();
    if (!saveAll(false)) {
      for (Session session : sessions.values()) if (session.dirty) {
        try { recoveryCopy(session); }
        catch (IOException e) { System.err.println("diyalamp could not write an emergency recovery copy: " + e.getMessage()); }
      }
    }
    if (noteGraph != null) noteGraph.close();
    disposeAll();
  }

  private Path recoveryDirectory() throws IOException {
    Path home = Path.of(System.getProperty("user.home"));
    Path support = home.resolve("Library/Application Support/diyalamp/recovery");
    Path current = home;
    for (Path segment : home.relativize(support)) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) throw new IOException("The recovery folder cannot be a symbolic link.");
    }
    Files.createDirectories(support);
    Files.setPosixFilePermissions(support, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    return support;
  }

  private void recoveryCopy(Session session) throws IOException {
    flush(session);
    Path directory = recoveryDirectory();
    String id = System.currentTimeMillis() + "-" + UUID.randomUUID();
    privateRecoveryFile(directory.resolve(id + ".md"), session.text);
    if (session.blockedView != null)
      privateRecoveryFile(directory.resolve(id + ".visible-text.txt"), session.blockedView.editor.recoveryText());
    privateRecoveryFile(directory.resolve(id + ".info.txt"),
        "diyalamp recovery copy\nOriginal file: " + session.path
            + "\nThe original file was not replaced.\nReason: " + session.error
            + "\nThe .md copy contains the last safely serialized Markdown.\n"
            + (session.blockedView == null ? "" : "The visible-text copy includes the current text without formatting.\n"));
    preferences.putBoolean("recoveryAvailable", true);
  }

  private void privateRecoveryFile(Path path, String text) throws IOException {
    Files.createFile(path, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
    Files.writeString(path, text, StandardCharsets.UTF_8);
  }

  private void openRecoveryFolder() {
    try { getHostServices().showDocument(recoveryDirectory().toUri().toString()); }
    catch (IOException e) { error("Recovery folder could not be opened", e); }
  }
}
