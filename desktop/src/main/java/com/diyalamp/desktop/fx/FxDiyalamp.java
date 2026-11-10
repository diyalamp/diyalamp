package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Tab;
import atlantafx.base.controls.Popover;
import atlantafx.base.controls.TabSkin;
import atlantafx.base.controls.Message;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.css.PseudoClass;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
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
  private static final PseudoClass TAB_DRAG_SOURCE = PseudoClass.getPseudoClass("drag-source");
  private static final PseudoClass INVALID = PseudoClass.getPseudoClass("invalid");
  private static final double SIDEBAR_WIDTH = 272;
  private static final double MIN_FILES_WIDTH = 200;
  private static final double SIDEBAR_RATIO = .20;
  private static final double MIN_WRITING_WIDTH = 240;
  private static final double SIDEBAR_DIVIDER_WIDTH = 6;
  private static final double TOOLBAR_GAP = 4;
  private static final double FOLDER_CONTROL_CHROME = 40;
  private final Preferences preferences;
  private final Map<Path, Session> sessions = new LinkedHashMap<>();
  private final TreeView<Path> tree = new TreeView<>();
  private HiddenFileSelection hiddenFileSelection;
  private final Set<Path> expandedFolders = new HashSet<>();
  private Path renameRequested;
  private final FxSidebarSearch sidebarSearch = new FxSidebarSearch();
  private final PauseTransition searchDelay = new PauseTransition(Duration.millis(160));
  private final ExecutorService searchWorker = Executors.newSingleThreadExecutor(
      Thread.ofVirtual().name("diyalamp-search").factory());
  private FxWorkspaceSearch.Query searchQuery;
  private Future<?> searchTask;
  private long searchGeneration;
  private final ObjectProperty<Path> workspacePath = new SimpleObjectProperty<>();
  private final Button folderControl = new Button("Open folder…", FxTheme.icon("folder-open", 16));
  private final Label status = new Label("Local workspace");
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
  private final BooleanProperty privacyMode = new SimpleBooleanProperty(false);
  private final BooleanProperty historyBack = new SimpleBooleanProperty(false);
  private final BooleanProperty historyForward = new SimpleBooleanProperty(false);
  private final List<HistoryEntry> history = new ArrayList<>();
  private int historyIndex = -1;
  private boolean navigatingHistory;
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
  private final ObjectProperty<View> focusView = new SimpleObjectProperty<>();
  private NoteStore.Deleted lastDeleted;
  private boolean persistentFolder = true, sidebarOpen = true;
  private boolean workspaceRemembered, restoringWorkspace;
  private Timeline sidebarAnimation;
  private final ChangeListener<Boolean> motionPreference = (observable, before, after) -> applyMotionPreference();
  private final PauseTransition sidebarSave = new PauseTransition(Duration.millis(180));
  private double filesRatio = SIDEBAR_RATIO, connectionsRatio = SIDEBAR_RATIO;
  private boolean sidebarLayoutReady, sidebarLayoutChanging, sidebarLayoutQueued;
  private FxUpdates updates;
  private FxPomodoro pomodoro;
  private FxQuickTasks quickTasks;
  private FxNoteGraph noteGraph;
  private ToggleButton filesControl, connectionsControl;

  private record HistoryEntry(Path path, boolean beside) {}

  public FxDiyalamp() { this(Preferences.userRoot().node("com/diyalamp/desktop")); }
  FxDiyalamp(Preferences preferences) {
    this.preferences = preferences;
    zenMode.addListener((observable, previous, enabled) -> animateZen(enabled));
    zenProgress.addListener((observable, previous, progress) -> FxTheme.focusSurface(root, progress.doubleValue()));
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
    final FxFilePreview preview;
    Tab tab;
    final BorderPane content = new BorderPane();
    final ReadOnlyDoubleWrapper inactiveFocus = new ReadOnlyDoubleWrapper();
    PaneHost host;
    boolean disposed;
    View(Session session, PaneHost host) {
      this.session = session; this.host = host;
      boolean markdown = NoteStore.isMarkdown(session.path);
      editor = markdown ? new FxWritingPane(session.text) : null;
      preview = markdown ? null : new FxFilePreview(session.path);
      var isCurrent = focusView.isEqualTo(this);
      ReadOnlyDoubleWrapper lineFocus = new ReadOnlyDoubleWrapper();
      lineFocus.bind(Bindings.when(isCurrent).then(zenProgress).otherwise(0.0));
      if (editor != null) editor.setFocusLineProgress(lineFocus.getReadOnlyProperty());
      inactiveFocus.bind(Bindings.when(isCurrent).then(0.0).otherwise(zenProgress));
      FxZenChrome.apply(inactiveFocus.getReadOnlyProperty(), content);
      tab = newTab(UUID.randomUUID().toString());
      content.getStyleClass().add("note-page");
      Node writing = editor == null ? preview : editor.node();
      StackPane page = new StackPane(writing);
      StackPane.setAlignment(writing, Pos.TOP_CENTER);
      content.setCenter(page);
      content.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> activate(this));
      if (editor != null) {
        editor.setOnChange(() -> changed(this));
        editor.setOnNoteLink(target -> { if (noteGraph != null) noteGraph.openWiki(session.path, target); });
      }
      session.views.add(this);
    }
    void focus() { if (editor != null) editor.focus(); else preview.focus(); }
    void dispose() { if (editor != null) editor.dispose(); else preview.dispose(); }
    Tab newTab(String id) {
      Tab result = new Tab(id, session.path.getFileName().toString(), FxTheme.icon(documentIcon(session.path), 14));
      result.setUserData(this);
      result.setOnCloseRequest(event -> { if (!save(session, true)) event.consume(); });
      result.setOnClosed(event -> removeView(this));
      return result;
    }
  }

  private final class PaneHost {
    final FxDocumentTabs tabs = new FxDocumentTabs();
    final BorderPane container = new BorderPane();
    final FxDocumentLocation breadcrumb = new FxDocumentLocation(this::navigateBreadcrumb);
    final VBox chrome = new VBox();
    final StackPane chromeClip = clipped(chrome);
    PaneHost() {
      tabs.getStyleClass().addAll(Styles.DENSE, Styles.TABS_FLOATING, "document-tabs", "workspace-band");
      tabs.setTabClosingPolicy(Tab.ClosingPolicy.ALL_TABS);
      tabs.setTabResizePolicy(Tab.ResizePolicy.COMPUTED_WIDTH);
      tabs.setTabMinWidth(110);
      HBox location = new HBox(breadcrumb);
      HBox.setHgrow(breadcrumb, Priority.ALWAYS);
      location.setAlignment(Pos.CENTER_LEFT);
      location.getStyleClass().addAll("workspace-band", "document-location");
      chrome.getChildren().setAll(tabs, location);
      container.setTop(chromeClip);
      container.getStyleClass().add("document-pane");
      tabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
        View view = selected == null ? null : (View) selected.getUserData();
        if (view != null && !view.disposed) {
          container.setCenter(view.content);
          // Restoring or closing a background tab can update this pane without activating it.
          if (restoringWorkspace || active != null && active.host != this && previous != null && !tabs.getTabs().contains(previous)) {
            breadcrumb.show(store.root(), view.session.path);
            return;
          }
          syncFileSelection(view.session.path); activate(view);
        } else {
          container.setCenter(emptyPage()); breadcrumb.show(null, null);
          if (active != null && active.host == this) setActive(null);
          updateStatus();
        }
      });
      container.setCenter(emptyPage());
      installDrop(this);
      installTabDrag(this);
    }

    private void navigateBreadcrumb(Path path) {
      if (store == null) return;
      if (store.isDirectory(path)) {
        revealFolder(path);
      } else {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab != null && tab.getUserData() instanceof View view) {
          activate(view);
          view.focus();
        }
      }
    }
  }

  @Override public void start(Stage stage) {
    this.stage = stage;
    updates = new FxUpdates(stage, () -> saveAll(true), Platform::exit, status::setText);
    FxTheme.setup();
    noteGraph = new FxNoteGraph(path -> open(path, false), status::setText);
    filesRatio = sidebarRatio("filesRatio");
    connectionsRatio = sidebarRatio("connectionsRatio");
    sidebarOpen = preferences.getBoolean("filesVisible", true);
    noteGraph.showingProperty().addListener((observable, previous, showing) -> {
      preferences.putBoolean("connectionsVisible", showing);
      if (sidebarLayoutReady) layoutSidebars(true);
    });
    noteGraph.showingProperty().set(preferences.getBoolean("connectionsVisible", true));
    stage.setTitle("diyalamp");
    stage.setMinWidth(820); stage.setMinHeight(560);
    boolean packagedMac = System.getProperty("os.name", "").startsWith("Mac")
        && !System.getProperty("jpackage.app-path", "").isBlank();
    if (!packagedMac) {
      var icon = FxTheme.appIcon();
      if (icon != null) stage.getIcons().add(icon);
    }
    primary = new PaneHost(); secondary = new PaneHost();
    split.getItems().add(primary.container);
    split.getStyleClass().add("writing-split");
    Line bandDivider = workspaceDivider("workspace-top-divider", primary.tabs.heightProperty());
    Line contextDivider = workspaceDivider("workspace-context-divider", primary.chrome.heightProperty());
    workspaceLayer.getChildren().addAll(bandDivider, contextDivider);
    buildExplorer();
    configureSidebars();
    root.getStyleClass().add("workspace-root");
    root.setCenter(workspaceLayer);
    pomodoro = new FxPomodoro(stage, status::setText, preferences);
    quickTasks = new FxQuickTasks(stage, preferences);
    buildToolbar();
    MenuBar menu = buildMenu();
    root.setTop(new VBox(menu, FxWindowHeader.create(stage, menu, toolbarClip, toolbarDragHandle, zenProgressProperty())));
    metrics.getStyleClass().add("note-metrics"); metrics.setAlignment(Pos.CENTER_RIGHT);
    Separator metricsDivider = new Separator(javafx.geometry.Orientation.VERTICAL);
    metricsDivider.getStyleClass().add("status-divider");
    metricsDivider.visibleProperty().bind(metrics.visibleProperty());
    metricsDivider.managedProperty().bind(metrics.managedProperty());
    status.setMinWidth(0);
    footer.getChildren().setAll(status, spacer(), metricsDivider, metrics);
    footer.getStyleClass().add("workspace-status");
    footer.setAlignment(Pos.CENTER_LEFT); root.setBottom(footerClip);
    privacyMode.addListener((observable, previous, enabled) -> {
      if (enabled) {
        quickTasks.hide();
        if (accentMenu != null) accentMenu.hide();
        pomodoro.hide();
        for (javafx.stage.Window window : List.copyOf(javafx.stage.Window.getWindows()))
          if (window instanceof javafx.stage.PopupWindow popup && popup.getOwnerWindow() == stage) popup.hide();
      }
      updateWindowTitle();
    });
    FxPrivacyShield.install(root, workspaceLayer, footerClip, privacyMode);
    FxZenChrome.apply(zenProgressProperty(), toolbarClip, footerClip, bandDivider, contextDivider, explorer,
        noteGraph.node(), primary.chromeClip, secondary.chromeClip);
    Scene scene = new Scene(root, 1180, 800);
    scene.getPreferences().setColorScheme(ColorScheme.DARK);
    scene.getStylesheets().add(FxDiyalamp.class.getResource("/fx-workspace.css").toExternalForm());
    initializeAccent();
    Platform.getPreferences().reducedMotionProperty().addListener(motionPreference);
    scene.focusOwnerProperty().addListener((observable, previous, focused) -> {
      for (Session session : sessions.values()) for (View view : session.views)
        if (within(focused, view.content)) { activate(view); return; }
    });
    stage.setScene(scene);
    stage.setOnCloseRequest(event -> {
      if (!saveAll(true)) { event.consume(); return; }
      rememberWorkspace();
      disposeAll();
    });
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
        if (Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
          setFolder(folder);
          if (persistentFolder && store != null) restoreWorkspace();
        }
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
    Label brand = new Label("diyalamp", FxTheme.toolbarBrandIcon(36));
    brand.setMinWidth(Region.USE_PREF_SIZE);
    brand.setAlignment(Pos.CENTER_LEFT); brand.getStyleClass().add("workspace-brand");
    Button back = FxTheme.iconButton("arrow-left", "Previous document", () -> navigateHistory(-1));
    Button forward = FxTheme.iconButton("arrow-right", "Next document", () -> navigateHistory(1));
    back.disableProperty().bind(historyBack.not().or(privacyMode));
    forward.disableProperty().bind(historyForward.not().or(privacyMode));
    ToggleButton files = new ToggleButton(null, FxTheme.icon("panel-left", 16));
    filesControl = files;
    files.getStyleClass().addAll("flat", "button-icon", "workspace-tool", "sidebar-toggle");
    files.setAccessibleText("Toggle files sidebar");
    Tooltip filesHelp = new Tooltip();
    filesHelp.textProperty().bind(Bindings.when(files.selectedProperty())
        .then("Hide files sidebar").otherwise("Show files sidebar"));
    files.setTooltip(filesHelp);
    files.setSelected(sidebarOpen);
    files.setOnAction(event -> toggleSidebar());
    folderControl.getStyleClass().addAll("flat", "workspace-tool", "workspace-folder");
    folderControl.setAlignment(Pos.CENTER_LEFT);
    folderControl.setMaxWidth(Region.USE_PREF_SIZE);
    folderControl.setTextOverrun(OverrunStyle.ELLIPSIS);
    folderControl.setAccessibleText("Open folder");
    folderControl.setOnAction(event -> chooseFolder());
    folderControl.visibleProperty().bind(privacyMode.not());
    folderControl.managedProperty().bind(folderControl.visibleProperty());
    HBox.setHgrow(folderControl, Priority.ALWAYS);
    toolbarDragHandle.setMinWidth(12);
    HBox.setHgrow(toolbarDragHandle, Priority.SOMETIMES);
    ToggleButton connections = new ToggleButton(null, FxTheme.icon("panel-right", 16));
    connectionsControl = connections;
    connections.getStyleClass().addAll("flat", "button-icon", "workspace-tool", "sidebar-toggle");
    connections.setAccessibleText("Toggle right sidebar");
    connections.setAccessibleHelp("Show or hide incoming and outgoing note connections. The state is remembered.");
    Tooltip connectionHelp = new Tooltip();
    connectionHelp.textProperty().bind(Bindings.when(connections.selectedProperty())
        .then("Hide right sidebar").otherwise("Show right sidebar"));
    connections.setTooltip(connectionHelp);
    if (noteGraph != null) connections.selectedProperty().bindBidirectional(noteGraph.showingProperty());
    ToggleButton zen = new ToggleButton(null, FxTheme.icon("moon", 16));
    zen.getStyleClass().addAll("flat", "button-icon", "workspace-tool");
    Tooltip zenHelp = new Tooltip();
    zenHelp.textProperty().bind(Bindings.when(zenMode).then("Exit line focus · ⇧⌘F").otherwise("Enter line focus · ⇧⌘F"));
    zen.setTooltip(zenHelp); zen.setAccessibleText("Zen mode");
    zen.setAccessibleHelp("Keep the current line clear while dimming surrounding text and workspace controls.");
    zen.selectedProperty().bindBidirectional(zenMode);
    Region folderDivider = toolbarDivider();
    folderDivider.visibleProperty().bind(folderControl.visibleProperty());
    folderDivider.managedProperty().bind(folderDivider.visibleProperty());
    toolbar.getItems().setAll(back, forward, files, brand, folderDivider, folderControl, toolbarDragHandle);
    if (pomodoro != null) {
      toolbar.getItems().add(pomodoro.button());
      toolbar.widthProperty().addListener((observable, previous, width) -> pomodoro.setCompact(width.doubleValue() < 820));
    }
    ToggleButton privacy = new ToggleButton(null, FxTheme.icon("shield"));
    privacy.getStyleClass().addAll("flat", "button-icon", "workspace-tool");
    privacy.setAccessibleText("Privacy Mode");
    Tooltip privacyHelp = new Tooltip();
    privacyHelp.textProperty().bind(Bindings.when(privacyMode)
        .then("Turn off Privacy Mode").otherwise("Hide workspace for presentation"));
    privacy.setTooltip(privacyHelp);
    privacy.selectedProperty().bindBidirectional(privacyMode);
    if (quickTasks != null) {
      quickTasks.button().disableProperty().bind(privacyMode);
      toolbar.getItems().add(quickTasks.button());
    }
    Button reset = FxTheme.iconButton("layout-template", "Reset sidebar widths", this::resetSidebarWidths);
    reset.setAccessibleHelp("Restore both sidebars to 20 percent of the workspace when space allows.");
    toolbar.getItems().addAll(privacy, accentButton(), zen, reset, toolbarDivider(), connections);
    for (ButtonBase control : List.of(files, accentButton(), zen, reset, connections))
      control.disableProperty().bind(privacyMode);
    if (pomodoro != null) pomodoro.button().disableProperty().bind(privacyMode);
    fitFolderControl();
    return toolbar;
  }

  private void fitFolderControl() {
    var fullPath = Bindings.createStringBinding(() -> workspacePath.get() == null
        ? "Open folder…" : workspacePath.get().toString(), workspacePath);
    folderControl.accessibleHelpProperty().bind(fullPath);
    Tooltip folderPath = new Tooltip();
    folderPath.textProperty().bind(fullPath);
    folderControl.setTooltip(folderPath);

    Text fullPathText = new Text();
    fullPathText.textProperty().bind(fullPath);
    fullPathText.fontProperty().bind(folderControl.fontProperty());
    var timerWidth = pomodoro == null ? toolbar.widthProperty() : pomodoro.button().prefWidthProperty();
    var available = Bindings.createDoubleBinding(() -> {
      Insets insets = toolbar.getInsets();
      // Leave one extra gap for pixel rounding in the toolbar skin.
      double reserved = insets.getLeft() + insets.getRight() + TOOLBAR_GAP * toolbar.getItems().size();
      for (Node item : toolbar.getItems()) if (item != folderControl && item.isManaged()) reserved += item.prefWidth(-1);
      return Math.max(36, toolbar.getWidth() - reserved);
    }, toolbar.widthProperty(), toolbar.insetsProperty(), folderControl.visibleProperty(), timerWidth);
    folderControl.textProperty().bind(Bindings.createStringBinding(() -> {
      Path path = workspacePath.get();
      return path != null && path.getFileName() != null
          && fullPathText.getLayoutBounds().getWidth() + FOLDER_CONTROL_CHROME > available.get()
          ? path.getFileName().toString() : fullPath.get();
    }, workspacePath, fullPathText.layoutBoundsProperty(), available));
    Text displayedPath = new Text();
    displayedPath.textProperty().bind(folderControl.textProperty());
    displayedPath.fontProperty().bind(folderControl.fontProperty());
    folderControl.prefWidthProperty().bind(Bindings.createDoubleBinding(() ->
        Math.min(available.get(), Math.ceil(displayedPath.getLayoutBounds().getWidth()) + FOLDER_CONTROL_CHROME),
        available, displayedPath.layoutBoundsProperty()));
  }

  private static Region toolbarDivider() {
    Region divider = new Region();
    divider.getStyleClass().add("workspace-toolbar-divider");
    divider.setMouseTransparent(true);
    return divider;
  }

  private void buildExplorer() {
    explorer.getStyleClass().add("file-explorer");
    explorer.setMinWidth(0); explorer.setPrefWidth(SIDEBAR_WIDTH); explorer.setMaxWidth(Double.MAX_VALUE);
    explorerClip.setMinWidth(0); explorerClip.setPrefWidth(SIDEBAR_WIDTH); explorerClip.setMaxWidth(Double.MAX_VALUE);
    Rectangle clipping = new Rectangle();
    clipping.widthProperty().bind(explorerClip.widthProperty());
    clipping.heightProperty().bind(explorerClip.heightProperty()); explorerClip.setClip(clipping);
    tree.setShowRoot(false); tree.setAccessibleText("Files and folders");
    tree.setAccessibleHelp("Use Right and Left to expand or collapse folders. Press Enter to open a note or F2 to rename a note or folder.");
    tree.setEditable(true);
    tree.setFixedCellSize(36);
    tree.setCellFactory(view -> new FileCell());
    tree.getStyleClass().addAll("file-tree", "edge-to-edge");
    FxTreeMotion.install(tree);
    tree.getSelectionModel().selectedItemProperty().addListener((observable, previous, selected) -> {
      if (hiddenFileSelection != null && selected != hiddenFileSelection.folder()) hiddenFileSelection = null;
    });
    tree.focusedProperty().addListener((observable, previous, focused) -> {
      if (!focused) hiddenFileSelection = null;
    });
    focusView.addListener((observable, previous, focused) -> hiddenFileSelection = null);
    tree.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
      TreeItem<Path> selected = tree.getSelectionModel().getSelectedItem();
      if (event.getTarget() != tree || tree.getEditingItem() != null || selected == null
          || selected.getValue() == null || store == null) return;
      Path path = selected.getValue();
      if (event.getCode() == KeyCode.ENTER) {
        if (store.isDirectory(path)) selected.setExpanded(!selected.isExpanded());
        else open(path, false);
        event.consume();
      } else if (event.getCode() == KeyCode.F2) {
        beginRename(path); event.consume();
      }
    });
    Button createFolder = fileAction("folder-plus", "New folder", () -> newFolder(null));
    Button create = fileAction("plus", "New note", () -> newNote(null));
    Region query = sidebarSearch.queryControl();
    var noFolder = tree.rootProperty().isNull();
    createFolder.disableProperty().bind(noFolder);
    query.disableProperty().bind(noFolder);
    HBox.setHgrow(query, Priority.ALWAYS);
    HBox searchRow = new HBox(query);
    searchRow.setAlignment(Pos.CENTER_LEFT);
    searchRow.getStyleClass().addAll("workspace-band", "files-search-row");
    sidebarSearch.setWorkspaceRoot(null);
    sidebarSearch.setOnSearch(this::scheduleSearch);
    sidebarSearch.setOnOpen(this::openSearchResult);
    sidebarSearch.setOnDismiss(() -> { scheduleSearch(null); tree.requestFocus(); });
    sidebarSearch.setRowActions(this::fileActions);
    searchDelay.setOnFinished(event -> runSearch());
    tree.visibleProperty().bind(sidebarSearch.hasQueryProperty().not());
    tree.managedProperty().bind(tree.visibleProperty());
    VBox.setVgrow(tree, Priority.ALWAYS);
    VBox.setVgrow(sidebarSearch, Priority.ALWAYS);
    Label filesTitle = new Label("Files");
    filesTitle.getStyleClass().add("workspace-section-title");
    HBox files = new HBox(4, filesTitle, spacer(), createFolder, create);
    files.setAlignment(Pos.CENTER_LEFT);
    files.getStyleClass().addAll("workspace-band", "files-heading-row");
    explorer.getChildren().setAll(files, searchRow, sidebarSearch, tree);
  }

  private static String documentIcon(Path path) {
    return NoteStore.documentKind(path) == NoteStore.DocumentKind.IMAGE ? "file-image" : "file-text";
  }

  private Line workspaceDivider(String id, ReadOnlyDoubleProperty boundary) {
    Line divider = new Line();
    divider.setId(id);
    divider.getStyleClass().add("workspace-band-divider");
    divider.endXProperty().bind(workspaceLayer.widthProperty());
    divider.translateYProperty().bind(boundary.subtract(.5));
    divider.setManaged(false);
    divider.setMouseTransparent(true);
    return divider;
  }

  private HBox fileActions(Path path) {
    String name = path.getFileName().toString();
    Button splitNote = fileAction("columns-2", "Open " + name + " side by side", () -> open(path, true));
    if (!NoteStore.isMarkdown(path)) return new HBox(splitNote);
    Button renameNote = fileAction("square-pen", "Rename " + name, () -> beginRename(path));
    Button trash = fileAction("trash-2", "Move " + name + " to deleted notes", () -> delete(path));
    HBox actions = new HBox(splitNote, renameNote, trash);
    actions.setAlignment(Pos.CENTER_RIGHT);
    return actions;
  }

  private HBox folderActions(Path path) {
    String name = path.getFileName().toString();
    Button create = fileAction("plus", "New note in " + name, () -> newNote(path));
    Button rename = fileAction("square-pen", "Rename folder " + name, () -> beginRename(path));
    Button trash = fileAction("trash-2", "Move folder " + name + " to deleted items", () -> deleteFolder(path));
    HBox actions = new HBox(create, rename, trash);
    actions.setAlignment(Pos.CENTER_RIGHT);
    return actions;
  }

  private static Button fileAction(String icon, String description, Runnable run) {
    Button button = FxTheme.iconButton(icon, description, run);
    button.getStyleClass().add("file-row-action");
    button.setOnMouseClicked(MouseEvent::consume);
    button.setOnDragDetected(MouseEvent::consume);
    return button;
  }

  private final class FileCell extends TreeCell<Path> {
    private HBox row;
    private Label title;
    private TextField renameInput;
    private Scene renameScene;
    private final EventHandler<MouseEvent> cancelOnOutsideClick = event -> {
      if (renameInput != null && event.getTarget() instanceof Node target && !within(target, renameInput)) cancelEdit();
    };

    FileCell() {
      setPrefWidth(0);
      Node chevron = FxTheme.icon("chevron-right", 12);
      chevron.getStyleClass().add("file-disclosure-chevron");
      StackPane disclosure = new StackPane(chevron);
      disclosure.getStyleClass().add("file-disclosure");
      // The skin top-aligns this slot. Fill the row to center the chevron.
      double rowHeight = tree.getFixedCellSize();
      disclosure.setMinSize(18, rowHeight);
      disclosure.setPrefSize(18, rowHeight);
      disclosure.setMaxSize(18, rowHeight);
      setDisclosureNode(disclosure);
    }

    @Override protected void updateItem(Path path, boolean empty) {
      if (renameInput != null && (empty || !java.util.Objects.equals(getItem(), path))) cancelEdit();
      super.updateItem(path, empty);
      if (renameInput != null) return;
      row = null; title = null;
      setText(null); setGraphic(null); setContextMenu(null); setTooltip(null);
      setOnDragDetected(null); setOnMouseClicked(null);
      setOnDragOver(null); setOnDragExited(null); setOnDragDropped(null);
      pseudoClassStateChanged(DROP_TARGET, false);
      if (empty || path == null || store == null) { setAccessibleText(null); return; }
      boolean directory = store.isDirectory(path);
      setEditable(true);
      title = new Label(path.getFileName().toString());
      title.setMinWidth(0); title.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(title, Priority.ALWAYS);
      Node kind = directory ? folderIcon() : FxTheme.icon(documentIcon(path));
      kind.getStyleClass().add("file-kind");
      row = new HBox(8, kind, title);
      // Let the tree skin account for disclosure, nesting, and the scroll viewport.
      row.setMinWidth(0);
      row.setAlignment(Pos.CENTER_LEFT); row.setMaxWidth(Double.MAX_VALUE);
      HBox actions = directory ? folderActions(path) : fileActions(path);
      if (directory) {
        actions.visibleProperty().bind(hoverProperty().or(selectedProperty()).or(focusedProperty()));
        actions.managedProperty().bind(actions.visibleProperty());
      }
      row.getChildren().add(actions);
      setTooltip(new Tooltip(path.getFileName().toString()));
      setGraphic(row);
      boolean markdown = NoteStore.isMarkdown(path);
      setAccessibleText(path.getFileName() + (directory ? ", folder" : markdown ? ", Markdown note" : ", preview file"));
      ContextMenu menu = FxTheme.contextMenu();
      if (directory) menu.getItems().addAll(item("New note", () -> newNote(path)), item("New folder…", () -> newFolder(path)),
          item("Rename folder…", () -> Platform.runLater(() -> beginRename(path))),
          new SeparatorMenuItem(), item("Move folder to deleted items", () -> deleteFolder(path)));
      else if (markdown) menu.getItems().addAll(item("Open", () -> open(path, false)),
          item("Open side by side", () -> open(path, true)), item("Rename…", () -> Platform.runLater(() -> beginRename(path))),
          new SeparatorMenuItem(), item("Move to deleted notes", () -> delete(path)));
      else menu.getItems().addAll(item("Open preview", () -> open(path, false)),
          item("Open side by side", () -> open(path, true)));
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
      if (markdown) setOnDragDetected(event -> {
        Dragboard board = startDragAndDrop(NOTE_DRAG_MODES.toArray(TransferMode[]::new));
        ClipboardContent content = new ClipboardContent();
        content.put(NOTE_DRAG, path.toString()); content.putFiles(List.of(path.toFile()));
        board.setContent(content); event.consume();
      });
      setOnMouseClicked(event -> {
        if (!directory && !isEditing() && event.getButton() == MouseButton.PRIMARY
            && event.isStillSincePress() && !event.isPopupTrigger()) {
          open(path, false);
          if (event.getClickCount() == 2 && active != null && path.equals(active.session.path)) active.focus();
        }
      });
    }

    private Node folderIcon() {
      Node closed = FxTheme.icon("folder"), open = FxTheme.icon("folder-open");
      closed.getStyleClass().add("folder-closed");
      open.getStyleClass().add("folder-open");
      return new StackPane(closed, open);
    }

    @Override public void startEdit() {
      if (row == null || renameInput != null) return;
      Path path = getItem();
      boolean directory = store.isDirectory(path);
      if (!path.equals(renameRequested) || (!directory && !NoteStore.isMarkdown(path))) {
        if (tree.getEditingItem() == getTreeItem()) tree.edit(null);
        return;
      }
      super.startEdit();
      if (!isEditing()) return;
      String filename = path.getFileName().toString();
      TextField input = new TextField(directory ? filename : filename.substring(0, filename.lastIndexOf('.')));
      renameInput = input;
      input.setAccessibleText((directory ? "Rename folder " : "Rename ") + filename);
      input.getStyleClass().add("file-rename-field");
      input.setMinWidth(0);
      HBox.setHgrow(input, Priority.ALWAYS);
      row.getChildren().set(1, input);
      renameScene = getScene();
      if (renameScene != null) renameScene.addEventFilter(MouseEvent.MOUSE_PRESSED, cancelOnOutsideClick);
      input.focusedProperty().addListener((observable, previous, focused) -> {
        if (!focused && renameInput == input) cancelEdit();
      });
      input.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
        if (event.getCode() == KeyCode.ESCAPE) { cancelEdit(); tree.requestFocus(); event.consume(); }
      });
      input.setOnAction(event -> {
        event.consume();
        try {
          Path renamed = directory ? renameFolder(path, input.getText()) : renameFile(path, input.getText());
          cancelEdit();
          if (!path.equals(renamed)) refreshAfterRename(renamed, directory);
          tree.requestFocus();
        } catch (IOException failure) {
          input.pseudoClassStateChanged(INVALID, true);
          String message = failure instanceof java.nio.file.FileAlreadyExistsException
              ? "An item with that name already exists." : failure.getMessage();
          input.setAccessibleHelp(message);
          status.setText(message);
        }
      });
      input.textProperty().addListener((observable, previous, text) -> {
        input.pseudoClassStateChanged(INVALID, false);
        input.setAccessibleHelp(null);
      });
      input.requestFocus();
      input.selectAll();
    }

    @Override public void cancelEdit() {
      TextField previous = renameInput;
      renameInput = null;
      if (renameScene != null) renameScene.removeEventFilter(MouseEvent.MOUSE_PRESSED, cancelOnOutsideClick);
      renameScene = null;
      if (previous != null && row != null && row.getChildren().contains(previous))
        row.getChildren().set(1, title);
      super.cancelEdit();
    }
  }

  private record HiddenFileSelection(TreeItem<Path> folder, TreeItem<Path> file) {}
  private record FileTreePosition(TreeItem<Path> selected, TreeItem<Path> focused) {}

  private void rememberHiddenFileSelection(TreeItem<Path> folder) {
    TreeItem<Path> selected = tree.getSelectionModel().getSelectedItem();
    if (active != null && selected != null && selected != folder && active.session.path.equals(selected.getValue())
        && active.session.path.startsWith(folder.getValue())) hiddenFileSelection = new HiddenFileSelection(folder, selected);
  }

  private void restoreHiddenFileSelection(TreeItem<Path> folder) {
    HiddenFileSelection saved = hiddenFileSelection;
    if (saved == null || saved.folder() != folder) return;
    hiddenFileSelection = null;
    if (tree.getSelectionModel().getSelectedItem() == folder && tree.getRow(saved.file()) >= 0)
      tree.getSelectionModel().select(saved.file());
  }

  private final class DirectoryItem extends TreeItem<Path> {
    boolean loaded;
    private FileTreePosition positionBeforeLoad;
    DirectoryItem(Path path) {
      super(path);
      if (store.isDirectory(path)) {
        getChildren().add(new TreeItem<>());
        expandedProperty().addListener((observable, previous, expanded) -> {
          if (expanded) {
            expandedFolders.add(path);
            populate();
            restorePositionAfterLoad();
            restoreHiddenFileSelection(this);
          } else {
            expandedFolders.remove(path);
          }
        });
      }
    }

    private void restorePositionAfterLoad() {
      FileTreePosition saved = positionBeforeLoad;
      positionBeforeLoad = null;
      if (saved == null) return;
      int selected = tree.getRow(saved.selected());
      if (selected >= 0 && (selected != tree.getSelectionModel().getSelectedIndex()
          || saved.selected() != tree.getSelectionModel().getSelectedItem()))
        tree.getSelectionModel().clearAndSelect(selected);
      int focused = tree.getRow(saved.focused());
      if (focused >= 0 && (focused != tree.getFocusModel().getFocusedIndex()
          || saved.focused() != tree.getFocusModel().getFocusedItem())) tree.getFocusModel().focus(focused);
    }

    void populate() {
      if (loaded) return;
      loaded = true;
      try {
        List<DirectoryItem> children = store.documents(getValue()).stream().map(DirectoryItem::new).toList();
        getChildren().setAll(children);
        for (DirectoryItem child : children)
          if (!child.isLeaf() && expandedFolders.contains(child.getValue())) child.setExpanded(true);
      }
      catch (IOException e) { getChildren().clear(); error("Folder could not be read", e); }
    }
  }

  private void refreshTree() {
    if (store == null) return;
    if (noteGraph != null) noteGraph.filesChanged();
    Path selected = selectedPath();
    DirectoryItem rootItem = new DirectoryItem(store.root());
    hiddenFileSelection = null;
    // Capture the active row before the native selection falls back to its collapsing ancestor.
    rootItem.addEventFilter(TreeItem.<Path>branchCollapsedEvent(), event -> rememberHiddenFileSelection(event.getTreeItem()));
    // The native model first counts the placeholder as a row. Replacing it with real files
    // can shift the selected index twice, so preserve the selected item through the first load.
    rootItem.addEventFilter(TreeItem.<Path>branchExpandedEvent(), event -> {
      if (event.getTreeItem() instanceof DirectoryItem folder && !folder.loaded)
        folder.positionBeforeLoad = new FileTreePosition(tree.getSelectionModel().getSelectedItem(),
            tree.getFocusModel().getFocusedItem());
    });
    rootItem.populate(); rootItem.setExpanded(true); tree.setRoot(rootItem);
    if (selected != null) selectTree(tree.getRoot(), selected);
    if (searchQuery != null) scheduleSearch(searchQuery);
  }

  private void scheduleSearch(FxWorkspaceSearch.Query query) {
    searchGeneration++;
    searchDelay.stop();
    if (searchTask != null) { searchTask.cancel(true); searchTask = null; }
    searchQuery = query == null || query.text().isEmpty() ? null : query;
    if (searchQuery == null || store == null) return;
    sidebarSearch.showSearching();
    searchDelay.playFromStart();
  }

  private void runSearch() {
    FxWorkspaceSearch.Query query = searchQuery;
    NoteStore workspace = store;
    if (query == null || workspace == null) return;
    long generation = searchGeneration;
    Map<Path, String> snapshots = new LinkedHashMap<>();
    if (query.scope() != FxWorkspaceSearch.Scope.FILENAMES) {
      for (Session session : sessions.values()) {
        if (!session.dirty) continue;
        flush(session);
        snapshots.put(session.path, session.blockedView == null ? session.text : session.blockedView.editor.recoveryText());
      }
    }
    searchTask = searchWorker.submit(() -> {
      try {
        FxWorkspaceSearch.Result result = FxWorkspaceSearch.search(workspace, query, snapshots);
        Platform.runLater(() -> { if (generation == searchGeneration) sidebarSearch.showResults(result); });
      } catch (CancellationException superseded) {
        // A newer query or workspace replaced this one.
      } catch (IOException | RuntimeException error) {
        Platform.runLater(() -> {
          if (generation == searchGeneration) sidebarSearch.showError("The workspace could not be searched.");
        });
      }
    });
  }

  private void focusSearch(FxWorkspaceSearch.Scope scope) {
    if (store == null) return;
    if (!sidebarOpen) toggleSidebar();
    Platform.runLater(() -> sidebarSearch.focusQuery(scope));
  }

  private void openSearchResult(FxSidebarSearch.OpenResult result) {
    open(result.path(), false);
    if (active == null || !active.session.path.equals(result.path())) return;
    FxWorkspaceSearch.Match match = result.match();
    if (match == null || active.editor == null) { active.focus(); return; }
    String markdown = active.session.blockedView == null ? active.session.text : active.session.blockedView.editor.recoveryText();
    active.editor.revealSearchMatch(markdown, match.line(), match.start(), match.end());
  }

  private Node emptyPage() {
    Label title = new Label("diyalamp"), detail = new Label("Open a file, or create a Markdown note.");
    title.getStyleClass().add("workspace-empty-title"); detail.getStyleClass().add("empty-detail");
    Button choose = new Button("Open folder…"); choose.setOnAction(event -> chooseFolder());
    choose.getStyleClass().add("accent");
    Button create = new Button("New note"); create.setOnAction(event -> newNote(null));
    create.setDisable(store == null);
    HBox actions = new HBox(10, choose, create); actions.setAlignment(Pos.CENTER);
    HBox brand = new HBox(10, FxTheme.brandIcon(30), title);
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

  private void syncFileSelection(Path path) {
    if (!path.equals(selectedPath())) selectTree(tree.getRoot(), path);
  }

  private void revealFolder(Path folder) {
    if (!sidebarOpen) toggleSidebar();
    if (sidebarSearch.hasQuery()) sidebarSearch.reset();
    if (folder.equals(store.root())) {
      tree.getSelectionModel().clearSelection();
      tree.scrollTo(0);
    } else if (selectTree(tree.getRoot(), folder)) {
      TreeItem<Path> row = tree.getSelectionModel().getSelectedItem();
      row.setExpanded(true);
      tree.scrollTo(tree.getRow(row));
    }
    tree.requestFocus();
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
    setActive(view);
    view.host.breadcrumb.show(store.root(), view.session.path);
    if (!privacyMode.get()) rememberLocation(view);
    updateStatus();
  }

  private void updateWindowTitle() {
    if (stage == null) return;
    stage.setTitle(privacyMode.get() ? "Privacy Mode · diyalamp" : active == null
        ? "diyalamp" : active.session.path.getFileName() + " · diyalamp");
  }

  private void rememberLocation(View view) {
    if (navigatingHistory) return;
    HistoryEntry entry = new HistoryEntry(view.session.path, view.host == secondary);
    if (historyIndex >= 0 && entry.equals(history.get(historyIndex))) return;
    history.subList(historyIndex + 1, history.size()).clear();
    history.add(entry);
    if (history.size() > 100) history.removeFirst();
    historyIndex = history.size() - 1;
    updateHistoryButtons();
  }

  private void updateHistoryButtons() {
    historyBack.set(historyIndex > 0);
    historyForward.set(historyIndex + 1 < history.size());
  }

  private void navigateHistory(int direction) {
    if (privacyMode.get() || store == null) return;
    int target = historyIndex + direction;
    while (target >= 0 && target < history.size()) {
      HistoryEntry entry = history.get(target);
      try {
        store.checkedDocument(entry.path());
      } catch (IOException unavailable) {
        history.remove(target);
        if (direction < 0) { historyIndex--; target--; }
        continue;
      }
      navigatingHistory = true;
      try {
        open(entry.path(), entry.beside());
        if (active != null && active.session.path.equals(entry.path())) {
          historyIndex = target;
          active.focus();
        }
      } finally {
        navigatingHistory = false;
      }
      break;
    }
    updateHistoryButtons();
  }

  private void relocateHistory(Path from, Path to) {
    history.replaceAll(entry -> entry.path().startsWith(from)
        ? new HistoryEntry(to.resolve(from.relativize(entry.path())), entry.beside()) : entry);
  }

  private void setActive(View view) {
    active = view;
    focusView.set(view);
    updateWindowTitle();
  }

  private void chooseFolder() {
    if (privacyMode.get()) return;
    DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("Open Markdown folder");
    if (store != null) chooser.setInitialDirectory(store.root().toFile());
    File chosen = chooser.showDialog(stage);
    if (chosen != null) setFolder(chosen.toPath());
  }

  private void chooseDocument() {
    if (privacyMode.get()) return;
    FileChooser chooser = new FileChooser(); chooser.setTitle("Open file");
    chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Supported files",
        "*.md", "*.markdown", "*.txt", "*.pdf", "*.jpeg", "*.jpg", "*.png", "*.gif", "*.bmp"));
    if (store != null) chooser.setInitialDirectory(store.root().toFile());
    File chosen = chooser.showOpenDialog(stage);
    if (chosen == null) return;
    try {
      Path path = chosen.toPath();
      Path parent = path.getParent().toRealPath();
      if (store == null || !parent.startsWith(store.root())) setFolder(parent);
      if (store != null && parent.startsWith(store.root())) open(parent.resolve(path.getFileName()), false);
    } catch (IOException error) { error("File could not be opened", error); }
  }

  private void setFolder(Path folder) {
    if (!saveAll(true)) return;
    try {
      NoteStore next = new NoteStore(folder);
      if (store != null && store.root().equals(next.root())) {
        refreshTree();
        return;
      }
      expandedFolders.clear();
      disposeAll(); store = next; lastDeleted = null;
      history.clear(); historyIndex = -1; updateHistoryButtons();
      if (noteGraph != null) noteGraph.workspace(store);
      primary.tabs.getTabs().clear(); secondary.tabs.getTabs().clear();
      split.getItems().setAll(primary.container);
      primary.container.setCenter(emptyPage()); secondary.container.setCenter(emptyPage());
      workspacePath.set(store.root());
      scheduleSearch(null);
      sidebarSearch.setWorkspaceRoot(store.root());
      sidebarSearch.reset();
      refreshTree(); updateStatus();
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
      sidebarSearch.reset(); scheduleSearch(null); refreshTree(); open(created, false);
      if (active != null) active.focus();
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
        sidebarSearch.reset(); scheduleSearch(null); refreshTree(); selectTree(tree.getRoot(), folder);
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
      relocateHistory(path, moved);
      if (noteGraph != null) { noteGraph.saved(path); noteGraph.saved(moved); }
      if (session != null) {
        sessions.remove(path); session.path = moved; sessions.put(moved, session);
        session.views.forEach(this::updateView);
      }
      refreshTree();
      selectTree(tree.getRoot(), moved);
      if (active != null) activate(active);
      status.setText("Note moved to " + directory.getFileName()); return true;
    } catch (IOException error) { error("Note could not be moved", error); return false; }
  }

  private void open(Path path, boolean beside) {
    if (store == null || privacyMode.get()) return;
    try {
      PaneHost host = beside ? secondary : primary;
      View view = documentView(path, host);
      host.tabs.getSelectionModel().select(view.tab);
      syncFileSelection(view.session.path);
      activate(view);
    } catch (IOException e) { error("File could not be opened", e); }
  }

  private View documentView(Path path, PaneHost host) throws IOException {
    path = path.toAbsolutePath().normalize();
    Session session = sessions.get(path);
    if (session == null) {
      path = store.checkedDocument(path);
      NoteStore.Loaded loaded = NoteStore.isMarkdown(path) ? store.load(path) : new NoteStore.Loaded("", "");
      session = new Session(path, loaded);
      sessions.put(path, session);
    } else flush(session);
    if (host == secondary) showSecondary();
    for (View view : session.views) if (view.host == host) return view;
    View view = new View(session, host);
    host.tabs.getTabs().add(view.tab);
    updateView(view);
    return view;
  }

  private void changed(View source) {
    if (source.disposed) return;
    Session session = source.session;
    boolean wasDirty = session.dirty;
    session.dirty = true; session.pendingView = source; session.metrics = null; setActive(source);
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
    if (view.editor == null) return;
    view.editor.node().setDisable(session.blockedView != null && session.blockedView != view);
    view.tab.setText(session.path.getFileName() + (session.dirty ? " •" : ""));
    if (session.error == null) { view.content.setTop(null); return; }
    Message message = new Message("Changes need attention", session.blockedView == null
        ? "Your edits are still open." : "Undo, simplify, or export visible text.");
    message.getStyleClass().addAll(Styles.WARNING, "save-warning-message");
    message.setTooltip(new Tooltip(session.error));
    message.setMaxWidth(Double.MAX_VALUE);
    Button export = new Button("Export copy"); export.getStyleClass().add("dialog-action");
    export.setOnAction(event -> export(session));
    Button reload = new Button("Reload"); reload.getStyleClass().add("dialog-action");
    reload.setOnAction(event -> reload(session));
    FlowPane actions = new FlowPane(8, 8, export, reload);
    actions.setAlignment(Pos.CENTER_RIGHT);
    VBox banner = new VBox(8, message, actions);
    banner.getStyleClass().add("save-warning");
    view.content.setTop(banner);
  }

  private void updateStatus() {
    if (noteGraph != null) noteGraph.select(active == null || active.editor == null ? null : active.session.path);
    if (active == null) {
      metrics.setVisible(false); metrics.setManaged(false); status.setText(store == null ? "Choose a folder to start writing" : "Local workspace");
      updateWindowTitle(); return;
    }
    if (active.editor == null) {
      metrics.setVisible(false); metrics.setManaged(false);
      status.setText("Read-only preview");
      return;
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

  private void beginRename(Path path) {
    if (store == null || path == null || path.equals(store.root())) return;
    if (!NoteStore.isMarkdown(path) && !store.isDirectory(path)) return;
    if (!sidebarOpen) toggleSidebar();
    if (sidebarSearch.hasQuery()) { sidebarSearch.reset(); scheduleSearch(null); }
    if (!selectTree(tree.getRoot(), path)) return;
    TreeItem<Path> selected = tree.getSelectionModel().getSelectedItem();
    boolean visible = tree.lookupAll(".tree-cell").stream()
        .anyMatch(node -> node instanceof TreeCell<?> cell && cell.getTreeItem() == selected && cell.isVisible());
    if (!visible) { tree.scrollTo(tree.getRow(selected)); tree.layout(); }
    renameRequested = path;
    try { tree.edit(selected); } finally { renameRequested = null; }
  }

  private Path renameFile(Path path, String name) throws IOException {
    Session session = sessions.get(path);
    if (session != null && !save(session, false))
      throw new IOException("Save or resolve the note’s changes before renaming it.");
    Path renamed = store.rename(path, name);
    relocateHistory(path, renamed);
    if (session != null && !path.equals(renamed)) {
      sessions.remove(path); session.path = renamed; sessions.put(renamed, session);
      session.views.forEach(this::updateView);
    }
    return renamed;
  }

  private Path renameFolder(Path path, String name) throws IOException {
    List<Session> affected = sessionsIn(path);
    for (Session session : affected)
      if (!save(session, false)) throw new IOException("Save or resolve changes in open notes before renaming this folder.");
    Path renamed = store.renameFolder(path, name);
    if (renamed.equals(path)) return path;
    relocateHistory(path, renamed);
    for (Session session : affected) {
      sessions.remove(session.path);
      session.path = renamed.resolve(path.relativize(session.path));
      sessions.put(session.path, session);
      for (View view : session.views) {
        if (view.preview != null) view.preview.relocate(session.path);
        updateView(view);
      }
    }
    List<Path> expanded = expandedFolders.stream().filter(folder -> folder.startsWith(path)).toList();
    expandedFolders.removeAll(expanded);
    for (Path folder : expanded) expandedFolders.add(renamed.resolve(path.relativize(folder)));
    return renamed;
  }

  private List<Session> sessionsIn(Path folder) {
    return sessions.values().stream().filter(session -> session.path.startsWith(folder)).toList();
  }

  private void refreshAfterRename(Path renamed, boolean directory) {
    refreshTree();
    selectTree(tree.getRoot(), renamed);
    if (active != null) activate(active);
    status.setText((directory ? "Folder" : "Note") + " renamed to " + renamed.getFileName());
  }

  private void delete(Path path) {
    if (store == null || path == null || !NoteStore.isMarkdown(path) || store.isDirectory(path)) return;
    Session session = sessions.get(path);
    if (session != null && !save(session, true)) return;
    try {
      lastDeleted = store.trash(path);
      if (session != null) closeDeletedSession(session);
      refreshTree(); status.setText("Note moved to deleted items · File → Restore last deleted item");
    } catch (IOException e) { error("Note could not be deleted", e); }
  }

  private void deleteFolder(Path path) {
    if (!confirm("Move folder to deleted items?",
        "“" + path.getFileName() + "” and everything inside it can be restored from the File menu.")) return;
    List<Session> affected = sessionsIn(path);
    for (Session session : affected) if (!save(session, true)) return;
    try {
      lastDeleted = store.deleteFolder(path);
      for (Session session : affected) closeDeletedSession(session);
      expandedFolders.removeIf(folder -> folder.startsWith(path));
      refreshTree();
      status.setText("Folder moved to deleted items · File → Restore last deleted item");
    } catch (IOException e) { error("Folder could not be deleted", e); }
  }

  private void closeDeletedSession(Session session) {
    for (View view : new ArrayList<>(session.views)) {
      view.host.tabs.getTabs().remove(view.tab); removeView(view);
    }
  }

  private void restoreLast() {
    if (store == null || lastDeleted == null) { deletedItems(); return; }
    restore(lastDeleted);
  }

  private void restore(NoteStore.Deleted deleted) {
    try {
      Path restored = store.restore(deleted); if (deleted.equals(lastDeleted)) lastDeleted = null;
      if (deleted.folder()) expandedFolders.add(restored);
      refreshTree();
      if (deleted.folder()) selectTree(tree.getRoot(), restored);
      else open(restored, false);
      status.setText(deleted.folder() ? "Folder restored" : "Note restored");
    } catch (IOException e) { error("Item could not be restored", e); }
  }

  private void deletedItems() {
    if (store == null) return;
    try {
      List<NoteStore.Deleted> deleted = store.deleted();
      if (deleted.isEmpty()) { information("Deleted items", "There are no deleted notes or folders in this workspace."); return; }
      ChoiceDialog<NoteStore.Deleted> dialog = new ChoiceDialog<>(deleted.getFirst(), deleted);
      dialog.setTitle("Restore a deleted item"); dialog.setHeaderText(null); dialog.setContentText("Item");
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
    } catch (IOException e) { error("Deleted items could not be read", e); }
  }

  private void closeActive() {
    if (active == null || !save(active.session, true)) return;
    View view = active; view.host.tabs.getTabs().remove(view.tab); removeView(view);
  }

  private void removeView(View view) {
    if (view.disposed) return;
    view.disposed = true; view.dispose(); view.session.views.remove(view);
    if (view.session.views.isEmpty()) sessions.remove(view.session.path);
    if (active == view) setActive(null);
    if (view.host.tabs.getTabs().isEmpty()) {
      view.host.container.setCenter(emptyPage()); view.host.breadcrumb.show(null, null);
      if (view.host == secondary) split.getItems().remove(secondary.container);
    }
    if (active == null) {
      Tab selected = view.host.tabs.getSelectionModel().getSelectedItem();
      if (selected == null) {
        PaneHost other = view.host == primary ? secondary : primary;
        selected = other.tabs.getSelectionModel().getSelectedItem();
      }
      if (selected != null && selected.getUserData() instanceof View next) {
        syncFileSelection(next.session.path); activate(next);
      }
    }
    updateStatus();
  }

  private void disposeAll() {
    autosave.stop(); serialization.stop();
    for (Session session : sessions.values()) for (View view : session.views) {
      view.disposed = true; view.dispose();
    }
    sessions.clear(); setActive(null);
  }

  private void rememberWorkspace() {
    if (workspaceRemembered || !persistentFolder || store == null || primary == null || secondary == null) return;
    workspaceRemembered = true;
    if (privacyMode.get()) {
      FxWorkspaceState.clear(preferences);
      return;
    }
    var dividers = split.getDividers();
    double divider = dividers.isEmpty() ? .5 : dividers.getFirst().getPosition();
    new FxWorkspaceState(store.root(), rememberPane(primary), rememberPane(secondary),
        active != null && active.host == secondary, divider).write(preferences);
  }

  private FxWorkspaceState.Pane rememberPane(PaneHost host) {
    List<Path> tabs = host.tabs.getTabs().stream().map(tab -> (View) tab.getUserData())
        .filter(view -> !view.disposed).map(view -> store.root().relativize(view.session.path)).toList();
    Tab selected = host.tabs.getSelectionModel().getSelectedItem();
    Path selectedPath = selected == null ? null : store.root().relativize(((View) selected.getUserData()).session.path);
    return new FxWorkspaceState.Pane(tabs, selectedPath);
  }

  private void restoreWorkspace() {
    FxWorkspaceState workspace = FxWorkspaceState.read(preferences, store.root());
    if (workspace == null) return;
    restoringWorkspace = true;
    primary.tabs.setAnimated(false);
    secondary.tabs.setAnimated(false);
    View left, right;
    try {
      left = restorePane(primary, workspace.primary());
      right = restorePane(secondary, workspace.secondary());
      if (right != null) split.setDividerPositions(workspace.divider());
    } finally {
      primary.tabs.applyMotionPreference();
      secondary.tabs.applyMotionPreference();
      restoringWorkspace = false;
    }
    View preferred = workspace.activeBeside() ? right : left;
    View restored = preferred != null ? preferred : left != null ? left : right;
    if (restored != null) {
      syncFileSelection(restored.session.path);
      activate(restored);
      restored.focus();
    }
  }

  private View restorePane(PaneHost host, FxWorkspaceState.Pane pane) {
    Tab selected = null;
    for (Path path : pane.tabs()) {
      try {
        Tab tab = documentView(store.root().resolve(path), host).tab;
        if (selected == null || path.equals(pane.selected())) selected = tab;
      } catch (IOException unavailable) {
        // A file can be moved, removed or edited outside DiyaLamp between runs.
      }
    }
    if (selected == null) return null;
    host.tabs.getSelectionModel().select(selected);
    return (View) selected.getUserData();
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
      SnapshotParameters snapshot = new SnapshotParameters(); snapshot.setFill(Color.TRANSPARENT);
      var hotspot = skin.sceneToLocal(event.getSceneX(), event.getSceneY());
      board.setDragView(skin.snapshot(snapshot, null), hotspot.getX(), hotspot.getY());
      skin.pseudoClassStateChanged(TAB_DRAG_SOURCE, true);
      skin.setOnDragDone(done -> {
        skin.pseudoClassStateChanged(TAB_DRAG_SOURCE, false);
        primary.tabs.clearInsertion(); secondary.tabs.clearInsertion();
        skin.setOnDragDone(null);
      });
      candidate[0] = null; event.consume();
    });
    host.tabs.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> candidate[0] = null);
    host.tabs.setOnDragOver(event -> {
      if (!event.getDragboard().hasContent(TAB_DRAG)) return;
      View view = draggedView((String) event.getDragboard().getContent(TAB_DRAG));
      if (view == null) return;
      int insertion = host.tabs.insertionAt(event.getSceneX());
      int current = host.tabs.getTabs().indexOf(view.tab);
      if (current >= 0 && (insertion == current || insertion == current + 1)) host.tabs.clearInsertion();
      else host.tabs.showInsertion(insertion);
      event.acceptTransferModes(TransferMode.MOVE); event.consume();
    });
    host.tabs.setOnDragExited(event -> host.tabs.clearInsertion());
    host.tabs.setOnDragDropped(event -> {
      if (!event.getDragboard().hasContent(TAB_DRAG)) return;
      host.tabs.clearInsertion();
      View view = draggedView((String) event.getDragboard().getContent(TAB_DRAG));
      int insertion = host.tabs.insertionAt(event.getSceneX());
      boolean moved = view != null && moveView(view, host);
      if (moved) host.tabs.moveToInsertion(view.tab, insertion);
      event.setDropCompleted(moved); event.consume();
    });
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
    activate(view); view.focus(); return true;
  }

  private void toggleSidebar() {
    sidebarOpen = !sidebarOpen;
    if (filesControl != null) filesControl.setSelected(sidebarOpen);
    preferences.putBoolean("filesVisible", sidebarOpen);
    layoutSidebars(true);
  }

  private double sidebarRatio(String key) {
    double value = preferences.getDouble(key, SIDEBAR_RATIO);
    // Older builds saved the default ratio even when the user never dragged a divider.
    if (!preferences.getBoolean("sidebarDefaults20", false) && value == .30) return SIDEBAR_RATIO;
    return value > 0 && value < 1 ? value : SIDEBAR_RATIO;
  }

  private void configureSidebars() {
    sidebarLayoutChanging = true;
    body.getStyleClass().addAll("writing-split", "workspace-sidebars"); body.setMinWidth(0);
    explorerClip.setMinWidth(MIN_FILES_WIDTH);
    ((Region) noteGraph.node()).setMinWidth(MIN_FILES_WIDTH);
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
    if (sidebarOpen && body.getItems().contains(explorerClip) && explorerClip.getWidth() >= MIN_FILES_WIDTH)
      filesRatio = explorerClip.getWidth() / body.getWidth();
    if (noteGraph.showingProperty().get() && body.getItems().contains(noteGraph.node())
        && noteGraph.node().getLayoutBounds().getWidth() >= MIN_FILES_WIDTH)
      connectionsRatio = noteGraph.node().getLayoutBounds().getWidth() / body.getWidth();
    sidebarSave.playFromStart();
  }

  private void persistSidebarWidths() {
    preferences.putDouble("filesRatio", filesRatio);
    preferences.putDouble("connectionsRatio", connectionsRatio);
    preferences.putBoolean("sidebarDefaults20", true);
  }

  private void queueSidebarLayout() {
    if (!sidebarLayoutReady || sidebarLayoutQueued) return;
    sidebarLayoutQueued = true; sidebarLayoutChanging = true;
    Platform.runLater(() -> { sidebarLayoutQueued = false; if (sidebarLayoutReady) layoutSidebars(false); });
  }

  private void resetSidebarWidths() {
    filesRatio = SIDEBAR_RATIO; connectionsRatio = SIDEBAR_RATIO;
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
    double[] start = sidebarPositions(oldLeft, oldRight, width), target = defaultSidebarPositions(width);
    if (animate && !FxMotion.reduced() && target.length > 0) {
      body.setDividerPositions(start);
      ArrayList<KeyValue> values = new ArrayList<>();
      for (int i = 0; i < target.length; i++)
        values.add(new KeyValue(body.getDividers().get(i).positionProperty(), target[i], FxMotion.SIDEBAR_EASE));
      values.add(new KeyValue(explorer.opacityProperty(), sidebarOpen ? 1 : 0, FxMotion.SIDEBAR_EASE));
      sidebarAnimation = new Timeline(new KeyFrame(FxMotion.SIDEBAR_TIME, values.toArray(KeyValue[]::new)));
      sidebarAnimation.setOnFinished(event -> finishSidebarLayout()); sidebarAnimation.play();
    } else {
      body.setDividerPositions(target); explorer.setOpacity(sidebarOpen ? 1 : 0); finishSidebarLayout();
    }
  }

  private double[] defaultSidebarPositions(double width) {
    double left = sidebarOpen ? Math.max(MIN_FILES_WIDTH, filesRatio * width) : 0;
    double right = noteGraph.showingProperty().get() ? Math.max(MIN_FILES_WIDTH, connectionsRatio * width) : 0;
    int visible = (sidebarOpen ? 1 : 0) + (noteGraph.showingProperty().get() ? 1 : 0);
    double extra = Math.max(0, left + right - visible * MIN_FILES_WIDTH);
    double available = Math.max(0, width - split.getMinWidth()
        - visible * (SIDEBAR_DIVIDER_WIDTH + MIN_FILES_WIDTH));
    if (extra > available) {
      double factor = available / extra;
      if (sidebarOpen) left = MIN_FILES_WIDTH + (left - MIN_FILES_WIDTH) * factor;
      if (noteGraph.showingProperty().get()) right = MIN_FILES_WIDTH + (right - MIN_FILES_WIDTH) * factor;
    }
    return sidebarPositions(left, right, width);
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
    explorerClip.setMinWidth(MIN_FILES_WIDTH); ((Region) noteGraph.node()).setMinWidth(MIN_FILES_WIDTH);
    sidebarAnimation = null;
    body.applyCss(); body.layout();
    body.setDividerPositions(defaultSidebarPositions(body.getWidth()));
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

  private void applyMotionPreference() {
    FxTheme.applyMotionPreference(root);
    primary.tabs.applyMotionPreference();
    secondary.tabs.applyMotionPreference();
    if (!FxMotion.reduced()) return;
    if (sidebarAnimation != null) layoutSidebars(false);
    if (zenAnimation != null) animateZen(zenMode.get());
    if (accentAnimation != null) animateAccent(accent.get());
    noteGraph.finishMotion();
  }

  /** Line focus shares one reversible clock; workspace chrome never moves or disappears. */
  private void animateZen(boolean enabled) {
    if (zenAnimation != null) zenAnimation.stop();
    double current = zenProgress.get(), target = enabled ? 1 : 0;
    if (FxMotion.reduced() || current == target) {
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
        shortcut("Open folder…", KeyCode.O, false, this::chooseFolder), item("Open file…", this::chooseDocument),
        item("Import Markdown…", this::importNote),
        new SeparatorMenuItem(), shortcut("Save all", KeyCode.S, false, () -> saveAll(true)),
        item("Export active note…", () -> { if (active != null && active.editor != null) export(active.session); }),
        item("Reload note from disk…", () -> { if (active != null && active.editor != null) reload(active.session); }),
        new SeparatorMenuItem(), shortcut("Rename note", KeyCode.R, true,
            () -> { if (active != null) beginRename(active.session.path); }),
        item("Move note to deleted notes", () -> { if (active != null) delete(active.session.path); }),
        item("Restore last deleted item", this::restoreLast), item("Deleted items…", this::deletedItems),
        item("Open recovery folder…", this::openRecoveryFolder),
        new SeparatorMenuItem(), shortcut("Close note", KeyCode.W, false, this::closeActive));
    edit.getItems().addAll(shortcut("Undo", KeyCode.Z, false, () -> edit("undo")),
        shortcut("Redo", KeyCode.Z, true, () -> edit("redo")), new SeparatorMenuItem(),
        shortcut("Cut", KeyCode.X, false, () -> edit("cut")),
        shortcut("Copy", KeyCode.C, false, () -> edit("copy")),
        shortcut("Paste", KeyCode.V, false, () -> edit("paste")),
        shortcut("Select all", KeyCode.A, false, () -> edit("select")),
        new SeparatorMenuItem(), shortcut("Find in note…", KeyCode.F, false, this::findInNote),
        shortcut("Search filenames", KeyCode.P, false, () -> focusSearch(FxWorkspaceSearch.Scope.FILENAMES)));
    MenuItem searchContents = item("Search note text", () -> focusSearch(FxWorkspaceSearch.Scope.CONTENTS));
    searchContents.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN));
    edit.getItems().add(searchContents);
    format.getItems().addAll(shortcut("Bold", KeyCode.B, false, () -> format("bold")),
        shortcut("Italic", KeyCode.I, false, () -> format("italic")),
        shortcut("Inline code", KeyCode.BACK_QUOTE, false, () -> format("code")),
        shortcut("Insert or edit link…", KeyCode.K, false, () -> { if (editableActive()) active.editor.insertLink(); }),
        new SeparatorMenuItem(), item("Paragraph", () -> block("paragraph", 1)),
        item("Heading 1", () -> block("heading", 1)), item("Heading 2", () -> block("heading", 2)),
        item("Heading 3", () -> block("heading", 3)), item("Bullet list", () -> block("bullet", 1)),
        item("Numbered list", () -> block("ordered", 1)), item("Checklist", () -> block("task", 1)),
        shortcut("Toggle checklist item", KeyCode.ENTER, true, () -> { if (editableActive()) active.editor.toggleChecklist(); }),
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
    if (quickTasks != null) view.getItems().add(item("Quick tasks…", quickTasks::show));
    view.getItems().add(item("Reset sidebar widths", this::resetSidebarWidths));
    for (Menu sensitive : List.of(file, edit, format)) {
      sensitive.disableProperty().bind(privacyMode);
      for (MenuItem action : sensitive.getItems()) action.disableProperty().bind(privacyMode);
    }
    for (MenuItem control : view.getItems()) control.disableProperty().bind(privacyMode);
    CheckMenuItem privacy = new CheckMenuItem("Privacy Mode");
    privacy.selectedProperty().bindBidirectional(privacyMode);
    view.getItems().addAll(new SeparatorMenuItem(), privacy);
    MenuItem checkUpdates = item("Check for updates…", () -> updates.check());
    checkUpdates.disableProperty().bind(privacyMode);
    help.getItems().addAll(checkUpdates, new SeparatorMenuItem(),
        item("Keyboard shortcuts", () -> information("Keyboard shortcuts",
        "⌘N  New note\n⌘O  Open folder\n⌘P  Search notes\n⌘S  Save\n⌘W  Close note\n⌘K  Insert or edit link\n"
        + "⇧⌘Enter  Toggle checklist\n⇧⌘B  Toggle sidebar\n⌘\\  Side by side\n\n"
        + "Type / on a new line for blocks. Drag a note to the right edge to open it beside your writing.\n"
        + "Deleted notes and folders can be restored from the File menu.")),
        item("About diyalamp", this::about));
    menu.getMenus().setAll(file, edit, format, view, help); return menu;
  }

  private void format(String kind) {
    if (editableActive() && !(stage.getScene().getFocusOwner() instanceof TextInputControl)) active.editor.format(kind);
  }
  private void block(String kind, int level) { if (editableActive()) active.editor.block(kind, level); }
  private boolean editableActive() { return active != null && active.editor != null && !active.editor.node().isDisabled(); }

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
    if (active != null && active.editor != null) active.editor.showFind();
  }

  private void chooseAccent() {
    Button anchor = accentButton();
    if (anchor.getScene() == null) return;
    if (accentMenu == null) accentMenu = accentPopover();
    FxPopovers.toggle(accentMenu, anchor);
  }

  private Button accentButton() {
    if (accentControl != null) return accentControl;
    accentControl = new Button(null, accentSwatch(displayedAccent));
    accentControl.setContentDisplay(ContentDisplay.RIGHT);
    accentControl.setTooltip(new Tooltip("Workspace accent color"));
    accentControl.setAccessibleText("Workspace accent color");
    accentControl.setAccessibleHelp("Choose a preset accent or enter a custom six-digit hex color.");
    accentControl.getStyleClass().addAll("workspace-tool", "workspace-accent-button");
    accentControl.setOnAction(event -> chooseAccent());
    return accentControl;
  }

  private void initializeAccent() {
    try {
      String chosen = preferences.get("accent", FxTheme.hex(FxTheme.DEFAULT_ACCENT));
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
    if (FxMotion.reduced() || current.equals(target)) {
      displayedAccent.set(target);
      accentAnimation = null;
      return;
    }
    Timeline transition = new Timeline(
        new KeyFrame(Duration.ZERO, new KeyValue(displayedAccent, current)),
        new KeyFrame(Duration.millis(280), new KeyValue(displayedAccent, target, Interpolator.EASE_BOTH)));
    accentAnimation = transition;
    transition.setOnFinished(event -> { displayedAccent.set(target); accentAnimation = null; });
    transition.play();
  }

  private Popover accentPopover() {
    return FxAccentPopover.create(stage, accent);
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

  private void about() {
    Alert alert = new Alert(Alert.AlertType.INFORMATION, "Version " + DesktopLauncher.VERSION
        + "\nOpen-source Markdown editor for macOS.\nLocal files. No account required.\n\nApache License 2.0", ButtonType.OK);
    Label title = new Label("diyalamp", FxTheme.brandIcon(30));
    title.getStyleClass().add("workspace-empty-title");
    title.setGraphicTextGap(8);
    title.setPadding(new Insets(24, 24, 0, 24));
    alert.setTitle("diyalamp");
    alert.getDialogPane().setHeader(title);
    FxDialogs.style(alert, stage);
    alert.showAndWait();
  }

  private void error(String title, Exception exception) {
    Alert alert = new Alert(Alert.AlertType.ERROR,
        privacyMode.get() ? "Turn off Privacy Mode to view the details." :
            exception.getMessage() == null ? title : exception.getMessage(), ButtonType.OK);
    alert.setTitle(title); alert.setHeaderText(null); FxDialogs.style(alert, stage); alert.showAndWait();
  }

  /** Native application quit can bypass a window close request. Preserve any blocked edits. */
  @Override public void stop() {
    Platform.getPreferences().reducedMotionProperty().removeListener(motionPreference);
    sidebarLayoutReady = false;
    if (sidebarAnimation != null) sidebarAnimation.stop();
    scheduleSearch(null); searchWorker.shutdownNow();
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
    rememberWorkspace();
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
