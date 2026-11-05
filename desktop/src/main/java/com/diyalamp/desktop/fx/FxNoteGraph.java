package com.diyalamp.desktop.fx;

import com.brunomnsilva.smartgraph.containers.ContentZoomScrollPane;
import com.brunomnsilva.smartgraph.graph.DigraphEdgeList;
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
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
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
  private final PauseTransition settle = new PauseTransition(Duration.seconds(2));
  private final SplitPane sections = new SplitPane();
  private Timeline animation;
  private SmartGraphPanel<Path, NoteLinkIndex.Edge> graph;
  private NoteLinkIndex.Snapshot snapshot;
  private Future<?> pending;
  private NoteStore store;
  private Path active;
  private long generation;
  private boolean stale = true, closed;

  FxNoteGraph(Consumer<Path> open, Consumer<String> message) {
    this.open = open; this.message = message;
    wrapper.getStyleClass().add("note-graph-clip");
    content.getStyleClass().add("note-graph-panel");
    content.getStylesheets().add(FxNoteGraph.class.getResource("/fx-note-graph-panel.css").toExternalForm());
    content.setMinWidth(WIDTH); content.setPrefWidth(WIDTH); content.setMaxWidth(WIDTH);
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
      if (visible) { wrapper.setVisible(true); wrapper.setManaged(true); }
      animation = new Timeline(new KeyFrame(Duration.millis(160),
          new KeyValue(reveal, visible ? 1 : 0, Interpolator.EASE_BOTH)));
      animation.setOnFinished(event -> { if (!visible) stopLayout(); }); animation.play();
      if (visible && stale) requestScan(null);
    });
    reveal.addListener((observable, previous, value) -> updateHidden());
    emptyCanvas();
  }

  Node node() { return wrapper; }
  BooleanProperty showingProperty() { return showing; }

  /** Zen uses the same reveal clock as the files/sidebar transition. */
  void bindZen(ReadOnlyDoubleProperty zenProgress) {
    var fraction = reveal.multiply(Bindings.subtract(1, zenProgress));
    wrapper.minWidthProperty().bind(fraction.multiply(WIDTH));
    wrapper.prefWidthProperty().bind(fraction.multiply(WIDTH));
    wrapper.maxWidthProperty().bind(fraction.multiply(WIDTH));
    wrapper.opacityProperty().bind(fraction);
    fraction.addListener((observable, previous, value) -> updateHidden());
    updateHidden();
  }
  private void updateHidden() {
    boolean visible = reveal.get() > .001 && wrapper.getOpacity() > .001;
    wrapper.setVisible(visible); wrapper.setManaged(visible);
    if (!visible) stopLayout();
  }

  void workspace(NoteStore next) {
    store = next; active = null; snapshot = null; stale = true; generation++;
    clearCache.set(true); invalidated.clear();
    if (pending != null) pending.cancel(true);
    render(); if (showing.get()) requestScan(null);
  }
  void select(Path path) {
    if (Objects.equals(path, active)) return;
    active = path; render();
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
    stopLayout();
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
    DigraphEdgeList<Path, NoteLinkIndex.Edge> model = new DigraphEdgeList<>();
    visible.forEach(model::insertVertex);
    snapshot.edges().stream().filter(edge -> visible.contains(edge.from()) && visible.contains(edge.to()))
        .filter(edge -> edge.from().equals(active) || edge.to().equals(active))
        .forEach(edge -> model.insertEdge(edge.from(), edge.to(), edge));
    try {
      graph = new SmartGraphPanel<>(model, new SmartGraphProperties(
          "vertex.radius=6\nvertex.label=true\nvertex.tooltip=true\nedge.label=false\nedge.tooltip=false\nedge.arrow=true\nedge.arrowsize=5\n"),
          new SmartCircularSortedPlacementStrategy(), FxNoteGraph.class.getResource("/fx-note-graph.css").toURI(),
          new ForceDirectedSpringGravityLayoutStrategy<>(6000, 2, 15, .4, .15));
    } catch (URISyntaxException error) { throw new IllegalStateException(error); }
    graph.setVertexLabelProvider(path -> {
      String name = title(path); return name.length() > 22 ? name.substring(0, 20) + "…" : name;
    });
    graph.setVertexRadiusProvider(path -> path.equals(active) ? 9 : 5);
    for (Path path : visible) if (path.equals(active)) graph.getStylableVertex(path).addStyleClass("current-note");
    graph.setVertexClickHandler(event -> {
      if (event.isPrimaryButton() && event.isDoubleClick()) open.accept(event.getVertex().getUnderlyingVertex().element());
    });
    ContentZoomScrollPane zoom = new ContentZoomScrollPane(graph, 4, .15);
    zoom.getStyleClass().add("note-graph-zoom"); zoom.setMinSize(0, 0);
    Tooltip.install(zoom, new Tooltip("Scroll to zoom · drag to pan · double-click a note to open"));
    canvas.getChildren().setAll(zoom);
    if (in.size() + out.size() >= MAX_VISIBLE_NODES)
      detail("Showing 60 notes. Lists include all indexed links.");
    if (showing.get() && wrapper.isVisible()) { graph.setAutomaticLayout(true); settle.playFromStart(); }
  }
  private void emptyCanvas() {
    Label empty = new Label(store == null ? "Open a folder to see connections" : "Open a note to see connections");
    empty.getStyleClass().add("graph-detail");
    empty.setWrapText(true); canvas.getChildren().setAll(empty); graph = null;
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
  }
}
