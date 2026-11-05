package com.diyalamp.desktop.fx;

import com.brunomnsilva.smartgraph.containers.ContentZoomScrollPane;
import com.brunomnsilva.smartgraph.graph.DigraphEdgeList;
import com.brunomnsilva.smartgraph.graph.Vertex;
import com.brunomnsilva.smartgraph.graphview.*;
import com.diyalamp.desktop.NoteStore;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.property.*;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** SmartGraph visualization and a background, read-only index of saved local notes. */
final class FxNoteGraph implements AutoCloseable {
  private static final int MAX_VISIBLE_NODES = 60;
  private static final double WIDTH = 304;
  private final Consumer<Path> open;
  private final Consumer<String> message;
  private final NoteLinkIndex index = new NoteLinkIndex();
  private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "diyalamp-note-links"); thread.setDaemon(true); return thread;
  });
  private final Set<Path> invalidated = ConcurrentHashMap.newKeySet();
  private final AtomicBoolean clearCache = new AtomicBoolean(true);
  private final BooleanProperty showing = new SimpleBooleanProperty(false);
  private final DoubleProperty reveal = new SimpleDoubleProperty(0);
  private final StackPane wrapper = new StackPane();
  private final VBox content = new VBox();
  private final Label selected = new Label("Open a note"), detail = new Label("Local note connections");
  private final Label incomingTitle = new Label("Incoming"), outgoingTitle = new Label("Outgoing");
  private final ListView<Path> incoming = new ListView<>(), outgoing = new ListView<>();
  private final StackPane canvas = new StackPane();
  private final PauseTransition debounce = new PauseTransition(Duration.millis(400));
  private final PauseTransition settle = new PauseTransition(Duration.seconds(5));
  private final SplitPane sections = new SplitPane();
  private Timeline animation;
  private SmartGraphPanel<Path, NoteLinkIndex.Edge> graph;
  private DigraphEdgeList<Path, NoteLinkIndex.Edge> model;
  private ContentZoomScrollPane zoom;
  private final Map<Path, Vertex<Path>> vertices = new LinkedHashMap<>();
  private final Map<NoteLinkIndex.Edge, com.brunomnsilva.smartgraph.graph.Edge<NoteLinkIndex.Edge, Path>> edges = new LinkedHashMap<>();
  private final Map<Path, Point2D> positions = new LinkedHashMap<>(32, .75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<Path, Point2D> entry) { return size() > 240; }
  };
  private final Set<Path> restorePositions = new HashSet<>();
  private final Set<Node> watchedVertices = Collections.newSetFromMap(new WeakHashMap<>());
  private final Label placeholder = new Label();
  private boolean viewDirty, needsReheat, graphInitialized, updatingGraph;
  private Path hovered;
  private Scene watchedScene;
  private Window watchedWindow;
  private final ChangeListener<Window> windowListener = (observable, previous, current) -> watchWindow(current);
  private final ChangeListener<Boolean> windowVisibility = (observable, previous, current) -> {
    if (canAnimate()) reheat(); else stopLayout();
  };
  private NoteLinkIndex.Snapshot snapshot;
  private Future<?> pending;
  private NoteStore store;
  private Path active;
  private long generation;
  private boolean stale = true, closed;

  FxNoteGraph(Consumer<Path> open, Consumer<String> message) {
    this.open = open; this.message = message;
    wrapper.getStyleClass().add("note-graph-clip");
    wrapper.setMinWidth(0); wrapper.setPrefWidth(WIDTH); wrapper.setMaxWidth(Double.MAX_VALUE);
    wrapper.opacityProperty().bind(reveal);
    content.getStyleClass().add("note-graph-panel");
    content.getStylesheets().add(FxNoteGraph.class.getResource("/fx-note-graph-panel.css").toExternalForm());
    content.setMinWidth(0); content.setPrefWidth(WIDTH); content.setMaxWidth(Double.MAX_VALUE);
    StackPane.setAlignment(content, Pos.TOP_RIGHT);
    Rectangle clip = new Rectangle(); clip.widthProperty().bind(wrapper.widthProperty());
    clip.heightProperty().bind(wrapper.heightProperty()); wrapper.setClip(clip);
    wrapper.getChildren().add(content);
    Label title = new Label("Connections"); title.getStyleClass().add("graph-heading");
    Region spring = new Region(); HBox.setHgrow(spring, Priority.ALWAYS);
    Button refresh = FxTheme.iconButton("refresh-cw", "Refresh note connections", this::refresh);
    Button close = FxTheme.iconButton("panel-right", "Hide connections", () -> showing.set(false));
    HBox heading = new HBox(8, title, spring, refresh, close); heading.setAlignment(Pos.CENTER_LEFT);
    heading.getStyleClass().add("graph-heading-row");
    selected.getStyleClass().add("graph-current-note"); selected.setWrapText(true);
    detail.getStyleClass().add("graph-detail"); detail.setWrapText(true);
    VBox summary = new VBox(8, selected, detail); summary.getStyleClass().add("graph-summary");
    VBox lists = new VBox(listSection(incomingTitle, incoming, "No incoming links"),
        listSection(outgoingTitle, outgoing, "No outgoing links"));
    lists.getStyleClass().add("graph-link-sections");
    for (Node node : lists.getChildren()) VBox.setVgrow(node, Priority.ALWAYS);
    canvas.getStyleClass().add("graph-canvas"); canvas.setMinHeight(110);
    sections.setOrientation(Orientation.VERTICAL); sections.getStyleClass().add("graph-sections");
    sections.getItems().addAll(canvas, lists); sections.setDividerPositions(.34);
    VBox.setVgrow(sections, Priority.ALWAYS);
    content.getChildren().addAll(heading, summary, sections);
    debounce.setOnFinished(event -> requestScan(null));
    settle.setOnFinished(event -> { if (graph != null) graph.setAutomaticLayout(false); });
    showing.addListener((observable, previous, visible) -> {
      if (animation != null) animation.stop();
      if (!visible) stopLayout();
      if (visible) { wrapper.setVisible(true); wrapper.setManaged(true); }
      animation = new Timeline(new KeyFrame(Duration.millis(160),
          new KeyValue(reveal, visible ? 1 : 0, Interpolator.EASE_BOTH)));
      animation.setOnFinished(event -> { if (!visible) stopLayout(); }); animation.play();
      if (visible) {
        refreshGraph(); reheat();
        if (stale) requestScan(null);
      }
    });
    reveal.addListener((observable, previous, value) -> updateHidden());
    wrapper.sceneProperty().addListener((observable, previous, current) -> {
      if (watchedScene != null) watchedScene.windowProperty().removeListener(windowListener);
      watchedScene = current;
      if (current != null) current.windowProperty().addListener(windowListener);
      watchWindow(current == null ? null : current.getWindow());
    });
    placeholder.getStyleClass().add("graph-detail"); placeholder.setWrapText(true);
    emptyCanvas();
    updateHidden();
  }

  Node node() { return wrapper; }
  BooleanProperty showingProperty() { return showing; }

  private void updateHidden() {
    boolean visible = reveal.get() > .001 && wrapper.getOpacity() > .001;
    wrapper.setVisible(visible); wrapper.setManaged(visible);
    if (!visible) stopLayout();
  }

  void workspace(NoteStore next) {
    stopLayout(); graph = null; model = null; zoom = null;
    vertices.clear(); edges.clear(); positions.clear(); restorePositions.clear(); watchedVertices.clear();
    hovered = null; viewDirty = false; needsReheat = false; graphInitialized = false;
    store = next; active = null; snapshot = null; stale = true; generation++;
    clearCache.set(true); invalidated.clear();
    if (pending != null) pending.cancel(true);
    render(); if (showing.get()) requestScan(null);
  }
  void select(Path path) {
    if (Objects.equals(path, active)) return;
    active = path; render(); reheat();
  }
  void saved(Path path) {
    invalidated.add(path); stale = true;
    if (showing.get()) debounce.playFromStart();
  }
  void filesChanged() {
    stale = true;
    if (showing.get()) debounce.playFromStart();
  }
  void refresh() {
    clearCache.set(true); stale = true; requestScan(null);
  }

  /** A wiki click may need an index even while the graph panel is hidden. */
  void openWiki(Path source, String target) {
    if (snapshot != null && !stale) openResolved(snapshot, source, target);
    else requestScan(result -> openResolved(result, source, target));
  }
  private void openResolved(NoteLinkIndex.Snapshot result, Path source, String target) {
    Optional<Path> resolved = NoteLinkIndex.resolve(result.root(), source, target, true, result.notes());
    if (resolved.isPresent()) open.accept(resolved.get());
    else message.accept("Note link not found or ambiguous: " + target);
  }

  private void requestScan(Consumer<NoteLinkIndex.Snapshot> after) {
    debounce.stop();
    if (closed || store == null) return;
    if (pending != null) pending.cancel(true);
    NoteStore chosen = store; long request = ++generation;
    detail("Updating connections…");
    pending = worker.submit(() -> {
      try {
        if (clearCache.getAndSet(false)) index.clear();
        for (Path path : new ArrayList<>(invalidated)) { index.invalidate(path); invalidated.remove(path); }
        NoteLinkIndex.Snapshot result = index.scan(chosen);
        Platform.runLater(() -> {
          if (closed || generation != request || store != chosen) return;
          snapshot = result; stale = false; render(); if (after != null) after.accept(result);
        });
      } catch (CancellationException error) { /* A newer workspace or save superseded this scan. */ }
      catch (IOException | RuntimeException error) {
        Platform.runLater(() -> {
          if (!closed && generation == request) detail("Could not update connections. Try refreshing.");
        });
      }
    });
  }

  private VBox listSection(Label title, ListView<Path> list, String empty) {
    title.getStyleClass().add("graph-list-heading");
    boolean inbound = list == incoming;
    list.setAccessibleText(inbound ? "Incoming links to the current note" : "Outgoing links from the current note");
    list.setAccessibleHelp((inbound ? "Notes that link to the current note." : "Notes linked from the current note.")
        + " Use Up and Down to select a note, then Enter to open it.");
    title.setLabelFor(list);
    list.getStyleClass().add("graph-note-list"); list.setFixedCellSize(40); list.setMinHeight(48);
    Label placeholder = new Label(empty); placeholder.setWrapText(true); placeholder.getStyleClass().add("graph-detail");
    list.setPlaceholder(placeholder);
    list.setCellFactory(unused -> new ListCell<>() {
      @Override protected void updateItem(Path path, boolean emptyCell) {
        super.updateItem(path, emptyCell);
        setText(emptyCell || path == null ? null : title(path));
        setGraphic(emptyCell || path == null ? null : FxTheme.icon("file-text", 16));
        setTooltip(emptyCell || path == null || store == null ? null : new Tooltip(store.root().relativize(path).toString()));
      }
    });
    list.setOnMouseClicked(event -> {
      if (event.getClickCount() == 1 && event.getButton() == javafx.scene.input.MouseButton.PRIMARY) openSelected(list);
    });
    list.setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER) { openSelected(list); event.consume(); } });
    VBox section = new VBox(8, title, list); section.getStyleClass().add("graph-link-section");
    VBox.setVgrow(list, Priority.ALWAYS); return section;
  }
  private void openSelected(ListView<Path> list) {
    Path path = list.getSelectionModel().getSelectedItem(); if (path != null) open.accept(path);
  }

  private void render() {
    selected.setText(active == null ? "" : title(active));
    selected.setVisible(active != null); selected.setManaged(active != null);
    selected.setTooltip(active == null || store == null ? null : new Tooltip(store.root().relativize(active).toString()));
    List<Path> in = snapshot == null || active == null ? List.of() : snapshot.incoming(active);
    List<Path> out = snapshot == null || active == null ? List.of() : snapshot.outgoing(active);
    incoming.getItems().setAll(in); outgoing.getItems().setAll(out);
    incomingTitle.setText("Incoming · " + in.size()); outgoingTitle.setText("Outgoing · " + out.size());
    detail(active != null && snapshot == null ? "Updating connections…"
        : snapshot != null && snapshot.partial() ? "Some notes were skipped while indexing." : "");
    if (active == null || snapshot == null) { emptyCanvas(); return; }
    LinkedHashSet<Path> visible = new LinkedHashSet<>(); visible.add(active);
    java.util.stream.Stream.concat(in.stream(), out.stream()).distinct().limit(MAX_VISIBLE_NODES - 1).forEach(visible::add);
    Set<NoteLinkIndex.Edge> wantedEdges = new LinkedHashSet<>();
    snapshot.edges().stream().filter(edge -> visible.contains(edge.from()) && visible.contains(edge.to()))
        .filter(edge -> edge.from().equals(active) || edge.to().equals(active))
        .forEach(wantedEdges::add);
    rememberPositions();
    if (model == null) model = new DigraphEdgeList<>();
    boolean changed = false;
    for (var edge : new ArrayList<>(edges.keySet())) if (!wantedEdges.contains(edge)) {
      model.removeEdge(edges.remove(edge)); changed = true;
    }
    for (Path path : new ArrayList<>(vertices.keySet())) if (!visible.contains(path)) {
      model.removeVertex(vertices.remove(path)); changed = true;
    }
    for (Path path : visible) if (!vertices.containsKey(path)) {
      vertices.put(path, model.insertVertex(path)); restorePositions.add(path); changed = true;
    }
    for (var edge : wantedEdges) if (!edges.containsKey(edge)) {
      edges.put(edge, model.insertEdge(vertices.get(edge.from()), vertices.get(edge.to()), edge)); changed = true;
    }
    if (graph == null) createGraph();
    viewDirty = true; needsReheat |= changed;
    zoom.setVisible(true); zoom.setManaged(true); placeholder.setVisible(false); placeholder.setManaged(false);
    refreshGraph();
    if (in.size() + out.size() >= MAX_VISIBLE_NODES)
      detail("Showing 60 notes. Lists include all indexed links.");
  }

  private void createGraph() {
    try {
      graph = new SmartGraphPanel<>(model, new SmartGraphProperties(
          "vertex.allow-user-move=true\nvertex.radius=6\nvertex.label=true\nvertex.tooltip=true\nedge.label=false\nedge.tooltip=false\nedge.arrow=true\nedge.arrowsize=5\n"),
          new SmartCircularSortedPlacementStrategy(), FxNoteGraph.class.getResource("/fx-note-graph.css").toURI(),
          new ForceDirectedSpringGravityLayoutStrategy<>(6000, 2, 15, .4, .15));
    } catch (URISyntaxException error) { throw new IllegalStateException(error); }
    graph.setVertexLabelProvider(path -> {
      String name = title(path); return name.length() > 22 ? name.substring(0, 20) + "…" : name;
    });
    graph.setVertexRadiusProvider(path -> path.equals(active) ? 9 : 5);
    graph.setVertexClickHandler(event -> {
      if (event.isPrimaryButton() && event.isDoubleClick()) open.accept(event.getVertex().getUnderlyingVertex().element());
    });
    zoom = new ContentZoomScrollPane(graph, 4, .15);
    zoom.getStyleClass().add("note-graph-zoom"); zoom.setMinSize(0, 0);
    Tooltip.install(zoom, new Tooltip("Scroll to zoom · drag to pan · double-click a note to open"));
    zoom.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> reheat());
    zoom.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> reheat());
    zoom.addEventFilter(ScrollEvent.SCROLL, event -> reheat());
    graph.widthProperty().addListener((observable, previous, value) -> { refreshGraph(); reheat(); });
    graph.heightProperty().addListener((observable, previous, value) -> { refreshGraph(); reheat(); });
    graph.sceneProperty().addListener((observable, previous, value) -> refreshGraph());
    canvas.getChildren().setAll(zoom, placeholder);
  }

  private void refreshGraph() {
    if (graph == null || updatingGraph || graph.getScene() == null || graph.getWidth() <= 0 || graph.getHeight() <= 0 || !viewDirty) return;
    updatingGraph = true;
    try {
      graph.init(); graphInitialized = true;
      // SmartGraph 2.6.1 performs updateAndWait directly when already on the FX thread.
      // Existing vertex nodes and the upstream zoom container are retained.
      graph.updateAndWait(); viewDirty = false;
      for (Path path : restorePositions) {
        Point2D point = positions.get(path); Vertex<Path> vertex = vertices.get(path);
        if (point != null && vertex != null) graph.setVertexPosition(vertex, point.getX(), point.getY());
      }
      restorePositions.clear(); updateHighlights();
      for (SmartGraphVertex<Path> vertex : graph.getSmartVertices()) {
        if (vertex instanceof Node node && watchedVertices.add(node)) {
          node.addEventFilter(MouseEvent.MOUSE_ENTERED, event -> {
            hovered = vertex.getUnderlyingVertex().element(); updateHighlights(); reheat();
          });
          node.addEventFilter(MouseEvent.MOUSE_EXITED, event -> { hovered = null; updateHighlights(); });
        }
      }
      if (needsReheat) { needsReheat = false; reheat(); }
    } finally { updatingGraph = false; }
  }

  private void rememberPositions() {
    if (graph == null || !graphInitialized) return;
    for (SmartGraphVertex<Path> vertex : graph.getSmartVertices())
      positions.put(vertex.getUnderlyingVertex().element(), new Point2D(vertex.getPositionCenterX(), vertex.getPositionCenterY()));
  }

  private void updateHighlights() {
    if (graph == null) return;
    Set<Path> neighbors = new HashSet<>();
    if (hovered != null) for (var edge : edges.keySet()) {
      if (edge.from().equals(hovered)) neighbors.add(edge.to());
      if (edge.to().equals(hovered)) neighbors.add(edge.from());
    }
    for (SmartGraphVertex<Path> vertex : graph.getSmartVertices()) {
      Path path = vertex.getUnderlyingVertex().element();
      clearStyle(vertex, "current-note"); clearStyle(vertex, "related-note");
      if (path.equals(active)) vertex.addStyleClass("current-note");
      if (neighbors.contains(path)) vertex.addStyleClass("related-note");
      SmartStylableNode label = vertex.getStylableLabel();
      if (label != null) {
        clearStyle(label, "current-note"); clearStyle(label, "related-note");
        if (path.equals(active)) label.addStyleClass("current-note");
        if (neighbors.contains(path)) label.addStyleClass("related-note");
      }
    }
    for (var entry : edges.entrySet()) {
      SmartStylableNode edge = graph.getStylableEdge(entry.getValue());
      if (edge == null) continue;
      clearStyle(edge, "highlighted-link");
      if (hovered != null && (entry.getKey().from().equals(hovered) || entry.getKey().to().equals(hovered)))
        edge.addStyleClass("highlighted-link");
    }
  }

  private static void clearStyle(SmartStylableNode node, String style) {
    while (node.removeStyleClass(style)) { }
    // SmartGraph 2.6.1 SmartLabel.removeStyleClass clears its pane twice, leaving
    // the child Text unchanged. Remove only our state class from its public children.
    if (node instanceof SmartLabel label)
      for (Node child : label.getChildrenUnmodifiable()) child.getStyleClass().removeIf(style::equals);
  }

  private boolean canAnimate() {
    return !closed && graph != null && graphInitialized && active != null && snapshot != null && showing.get() && wrapper.isVisible()
        && watchedWindow != null && watchedWindow.isShowing()
        && (!(watchedWindow instanceof Stage stage) || !stage.isIconified());
  }

  private void reheat() {
    if (!canAnimate() || Boolean.getBoolean("diyalamp.reduceMotion")) return;
    graph.setAutomaticLayout(true); settle.playFromStart();
  }

  private void watchWindow(Window next) {
    if (watchedWindow != null) {
      watchedWindow.showingProperty().removeListener(windowVisibility);
      if (watchedWindow instanceof Stage stage) stage.iconifiedProperty().removeListener(windowVisibility);
    }
    watchedWindow = next;
    if (next != null) {
      next.showingProperty().addListener(windowVisibility);
      if (next instanceof Stage stage) stage.iconifiedProperty().addListener(windowVisibility);
    }
    if (canAnimate()) reheat(); else stopLayout();
  }
  private void emptyCanvas() {
    stopLayout();
    placeholder.setText(store == null ? "Open a folder to see connections" : "Open a note to see connections");
    placeholder.setVisible(true); placeholder.setManaged(true);
    if (zoom == null) canvas.getChildren().setAll(placeholder);
    else { zoom.setVisible(false); zoom.setManaged(false); }
  }
  private static String title(Path path) {
    String name = path.getFileName().toString();
    return name.replaceFirst("(?i)\\.(md|markdown)$", "");
  }
  private void detail(String text) {
    detail.setText(text); detail.setVisible(!text.isBlank()); detail.setManaged(!text.isBlank());
  }
  private void stopLayout() { settle.stop(); if (graph != null) graph.setAutomaticLayout(false); }
  @Override public void close() {
    closed = true; generation++; debounce.stop(); stopLayout();
    if (animation != null) animation.stop(); if (pending != null) pending.cancel(true); worker.shutdownNow();
    if (watchedScene != null) watchedScene.windowProperty().removeListener(windowListener);
    watchWindow(null);
  }
}
