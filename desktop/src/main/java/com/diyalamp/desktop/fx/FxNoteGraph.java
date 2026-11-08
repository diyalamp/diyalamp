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
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.beans.property.*;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** SmartGraph visualization and a background, read-only index of saved local notes. */
final class FxNoteGraph implements AutoCloseable {
  private static final int MAX_VISIBLE_NODES = 60;
  private static final int SPARSE_GRAPH_NODES = 7;
  private static final double LABEL_INSET = 6;
  private static final double LABEL_CLEARANCE = 4;
  private static final PseudoClass CROWDED = PseudoClass.getPseudoClass("crowded");
  // SmartGraph scales repulsion by 1,000. Gentle steps and a stronger center
  // pull in crowded graphs prevent notes from piling against the pane's edges.
  private static final ForceDirectedSpringGravityLayoutStrategy<Path> STANDARD_LAYOUT =
      new ForceDirectedSpringGravityLayoutStrategy<>(7, 2.8, 34, .018, .0025);
  private static final ForceDirectedSpringGravityLayoutStrategy<Path> CROWDED_LAYOUT =
      new ForceDirectedSpringGravityLayoutStrategy<>(4.5, 3.5, 30, .014, .008);
  private static final double WIDTH = 304;
  private static final double LINK_HEADER_HEIGHT = 40;
  private static final double DIVIDER_HEIGHT = 8;
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
  private final VBox collapsedSections = new VBox(), pairedSections = new VBox();
  private final LinkSection incomingSection, outgoingSection;
  private final Map<Integer, double[]> dividerPositions = new HashMap<>();
  private int sectionMode = -1;
  private Boolean incomingOverride, outgoingOverride;
  private boolean awaitingDividerLayout;
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

  private record LinkSection(VBox pane, ToggleButton heading) { }

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
    Label title = new Label("Connections"); title.getStyleClass().add("workspace-section-title");
    Region spring = new Region(); HBox.setHgrow(spring, Priority.ALWAYS);
    Button refresh = FxTheme.iconButton("refresh-cw", "Refresh note connections", this::refresh);
    Button close = FxTheme.iconButton("panel-right", "Hide connections", () -> showing.set(false));
    HBox heading = new HBox(8, title, spring, refresh, close); heading.setAlignment(Pos.CENTER_LEFT);
    heading.getStyleClass().addAll("workspace-band", "graph-heading-row");
    selected.getStyleClass().add("graph-current-note"); selected.setMinWidth(0);
    HBox summary = new HBox(selected); summary.setAlignment(Pos.CENTER_LEFT);
    summary.getStyleClass().addAll("workspace-band", "graph-summary");
    HBox.setHgrow(selected, Priority.ALWAYS);
    detail.getStyleClass().addAll("graph-detail", "graph-status"); detail.setWrapText(true);
    detail.setMouseTransparent(true); StackPane.setAlignment(detail, Pos.BOTTOM_LEFT);
    detail("");
    incomingSection = listSection(incomingTitle, incoming, "No incoming links");
    outgoingSection = listSection(outgoingTitle, outgoing, "No outgoing links");
    canvas.getStyleClass().add("graph-canvas"); canvas.setMinHeight(110);
    sections.setOrientation(Orientation.VERTICAL); sections.getStyleClass().add("graph-sections");
    sections.heightProperty().addListener((observable, oldHeight, newHeight) -> {
      if (awaitingDividerLayout && newHeight.doubleValue() > 0) applyDividerPositions();
    });
    updateSections();
    VBox.setVgrow(sections, Priority.ALWAYS);
    content.getChildren().addAll(heading, summary, sections);
    debounce.setOnFinished(event -> requestScan(null));
    settle.setOnFinished(event -> { if (graph != null) graph.setAutomaticLayout(false); });
    showing.addListener((observable, previous, visible) -> {
      if (!visible) stopLayout();
      if (FxMotion.reduced()) finishMotion();
      else {
        if (animation != null) animation.stop();
        if (visible) { wrapper.setVisible(true); wrapper.setManaged(true); }
        animation = new Timeline(new KeyFrame(FxMotion.SIDEBAR_TIME,
            new KeyValue(reveal, visible ? 1 : 0, FxMotion.SIDEBAR_EASE)));
        animation.setOnFinished(event -> animation = null);
        animation.play();
      }
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

  void finishMotion() {
    if (animation != null) animation.stop();
    animation = null;
    reveal.set(showing.get() ? 1 : 0);
    updateHidden();
    stopLayout();
  }

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
    incomingOverride = null; outgoingOverride = null;
    clearCache.set(true); invalidated.clear();
    if (pending != null) pending.cancel(true);
    render(); if (showing.get()) requestScan(null);
  }
  void select(Path path) {
    if (Objects.equals(path, active)) return;
    incomingOverride = null; outgoingOverride = null;
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

  private LinkSection listSection(Label title, ListView<Path> list, String empty) {
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
    Node arrow = FxTheme.icon("chevron-right", 16);
    HBox label = new HBox(8, arrow, title); label.setAlignment(Pos.CENTER_LEFT);
    ToggleButton heading = new ToggleButton(null, label);
    heading.getStyleClass().addAll("flat", "graph-list-toggle");
    heading.setAlignment(Pos.CENTER_LEFT); heading.setMaxWidth(Double.MAX_VALUE);
    heading.setMinHeight(LINK_HEADER_HEIGHT); heading.setPrefHeight(LINK_HEADER_HEIGHT); heading.setMaxHeight(LINK_HEADER_HEIGHT);
    heading.accessibleTextProperty().bind(title.textProperty());
    heading.setAccessibleHelp("Show or hide this connection list.");
    heading.selectedProperty().addListener((observable, previous, open) -> arrow.setRotate(open ? 90 : 0));
    heading.setOnAction(event -> {
      if (inbound) incomingOverride = heading.isSelected(); else outgoingOverride = heading.isSelected();
      updateSections();
    });
    list.visibleProperty().bind(heading.selectedProperty());
    list.managedProperty().bind(heading.selectedProperty());
    VBox section = new VBox(heading, list); section.getStyleClass().add("graph-link-section");
    VBox.setVgrow(list, Priority.ALWAYS);
    return new LinkSection(section, heading);
  }

  private void updateSections() {
    boolean in = incomingSection.heading().isSelected(), out = outgoingSection.heading().isSelected();
    int mode = (in ? 1 : 0) | (out ? 2 : 0);
    if (mode == sectionMode) return;
    if (sectionMode > 0 && !awaitingDividerLayout)
      dividerPositions.put(sectionMode, sections.getDividerPositions());
    sectionMode = mode;
    sections.getItems().clear();
    collapsedSections.getChildren().clear();
    pairedSections.getChildren().clear();

    VBox incomingPane = incomingSection.pane(), outgoingPane = outgoingSection.pane();
    incomingSection.heading().pseudoClassStateChanged(PseudoClass.getPseudoClass("separated"), mode == 0);
    outgoingSection.heading().pseudoClassStateChanged(PseudoClass.getPseudoClass("separated"), mode != 3);
    if (mode == 3) {
      sections.getItems().addAll(canvas, incomingPane, outgoingPane);
    } else if (mode == 0) {
      collapsedSections.getChildren().setAll(canvas, incomingPane, outgoingPane);
      VBox.setVgrow(canvas, Priority.ALWAYS);
      VBox.setVgrow(incomingPane, Priority.NEVER); VBox.setVgrow(outgoingPane, Priority.NEVER);
      sections.getItems().add(collapsedSections);
    } else {
      // Attach the collapsed header to the open list. Otherwise its fixed size
      // would block both adjacent dividers from resizing the remaining sections.
      pairedSections.getChildren().setAll(incomingPane, outgoingPane);
      VBox.setVgrow(incomingPane, in ? Priority.ALWAYS : Priority.NEVER);
      VBox.setVgrow(outgoingPane, out ? Priority.ALWAYS : Priority.NEVER);
      sections.getItems().addAll(canvas, pairedSections);
    }
    awaitingDividerLayout = mode != 0 && sections.getHeight() <= 0;
    if (mode != 0 && !awaitingDividerLayout) applyDividerPositions();
  }

  private void applyDividerPositions() {
    awaitingDividerLayout = false;
    double[] remembered = dividerPositions.get(sectionMode);
    if (remembered != null) {
      sections.setDividerPositions(remembered);
      return;
    }
    double height = sections.getHeight();
    if (sectionMode == 3) {
      double share = (height - 2 * DIVIDER_HEIGHT) / 3;
      sections.setDividerPositions((share + DIVIDER_HEIGHT / 2) / height,
          (2 * share + 1.5 * DIVIDER_HEIGHT) / height);
    } else {
      sections.setDividerPositions((height - LINK_HEADER_HEIGHT) / (2 * height));
    }
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
    incomingSection.heading().setSelected(incomingOverride != null ? incomingOverride : !in.isEmpty());
    outgoingSection.heading().setSelected(outgoingOverride != null ? outgoingOverride : !out.isEmpty());
    updateSections();
    detail(active != null && snapshot == null ? "Updating connections…"
        : snapshot != null && snapshot.partial() ? "Some notes were skipped while indexing." : "");
    if (active == null || snapshot == null) { emptyCanvas(); return; }
    List<Path> neighbors = java.util.stream.Stream.concat(in.stream(), out.stream())
        .filter(path -> !path.equals(active)).distinct().toList();
    LinkedHashSet<Path> visible = new LinkedHashSet<>(); visible.add(active);
    neighbors.stream().limit(MAX_VISIBLE_NODES - 1).forEach(visible::add);
    if (!visible.contains(hovered)) hovered = null;
    Set<NoteLinkIndex.Edge> wantedEdges = new LinkedHashSet<>();
    snapshot.edges().stream().filter(edge -> visible.contains(edge.from()) && visible.contains(edge.to()))
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
    if (neighbors.size() >= MAX_VISIBLE_NODES)
      detail("Showing 60 notes. Lists include all indexed links.");
  }

  private void createGraph() {
    try {
      graph = new SmartGraphPanel<>(model, new SmartGraphProperties(
          "vertex.allow-user-move=true\nvertex.radius=4\nvertex.label=true\nvertex.tooltip=true\nedge.label=false\nedge.tooltip=false\nedge.arrow=true\nedge.arrowsize=4\n"),
          new SmartCircularSortedPlacementStrategy(), FxNoteGraph.class.getResource("/fx-note-graph.css").toURI(),
          STANDARD_LAYOUT);
    } catch (URISyntaxException error) { throw new IllegalStateException(error); }
    graph.setVertexLabelProvider(FxNoteGraph::graphTitle);
    graph.setVertexRadiusProvider(path -> path.equals(active) ? 6.5 : 4);
    graph.setVertexClickHandler(event -> {
      if (event.isPrimaryButton() && event.isDoubleClick()) open.accept(event.getVertex().getUnderlyingVertex().element());
    });
    zoom = new ContentZoomScrollPane(graph, 4, .15);
    zoom.getStyleClass().add("note-graph-zoom"); zoom.setMinSize(0, 0);
    Tooltip.install(zoom, new Tooltip("Hover to inspect · scroll to zoom · drag to pan · double-click to open"));
    zoom.scaleFactorProperty().addListener((observable, previous, scale) -> updateLabels());
    graph.automaticLayoutProperty().addListener((observable, previous, moving) -> updateLabels());
    zoom.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> reheat());
    zoom.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> reheat());
    zoom.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
      if (graph != null && !graph.automaticLayoutProperty().get()) updateLabels();
    });
    graph.widthProperty().addListener((observable, previous, value) -> resizeGraph());
    graph.heightProperty().addListener((observable, previous, value) -> resizeGraph());
    graph.sceneProperty().addListener((observable, previous, value) -> refreshGraph());
    canvas.getChildren().setAll(zoom, placeholder, detail);
  }

  private void resizeGraph() {
    refreshGraph(); reheat();
    if (graph != null && !graph.automaticLayoutProperty().get()) updateLabels();
  }

  private void refreshGraph() {
    if (graph == null || updatingGraph || graph.getScene() == null || graph.getWidth() <= 0 || graph.getHeight() <= 0 || !viewDirty) return;
    updatingGraph = true;
    try {
      if (!graphInitialized) {
        graph.init(); graphInitialized = true;
        Vertex<Path> current = vertices.get(active);
        if (current != null) graph.setVertexPosition(current, graph.getWidth() / 2, graph.getHeight() / 2);
      }
      // SmartGraph 2.6.1 performs updateAndWait directly when already on the FX thread.
      // Existing vertex nodes and the upstream zoom container are retained.
      graph.updateAndWait(); viewDirty = false;
      boolean crowded = vertices.size() > 24;
      graph.pseudoClassStateChanged(CROWDED, crowded);
      graph.setAutomaticLayoutStrategy(crowded ? CROWDED_LAYOUT : STANDARD_LAYOUT);
      for (Path path : restorePositions) {
        Point2D point = positions.get(path); Vertex<Path> vertex = vertices.get(path);
        if (point != null && vertex != null) graph.setVertexPosition(vertex, point.getX(), point.getY());
      }
      restorePositions.clear(); updateHighlights();
      for (SmartGraphVertex<Path> vertex : graph.getSmartVertices()) {
        if (vertex instanceof Node node && watchedVertices.add(node)) {
          Path path = vertex.getUnderlyingVertex().element();
          positionLabel(vertex);
          node.addEventFilter(MouseEvent.MOUSE_ENTERED, event -> {
            if (!path.equals(hovered)) { hovered = path; updateHighlights(); }
          });
          node.addEventFilter(MouseEvent.MOUSE_EXITED, event -> {
            if (path.equals(hovered)) { hovered = null; updateHighlights(); }
          });
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
      String state = path.equals(active) ? "current-note" : path.equals(hovered) ? "inspected-note"
          : neighbors.contains(path) ? "related-note" : hovered != null ? "muted-note" : null;
      noteStyle(vertex, state);
      noteStyle(vertex.getStylableLabel(), state);
    }
    for (SmartGraphEdge<NoteLinkIndex.Edge, Path> edge : graph.getSmartEdges()) {
      NoteLinkIndex.Edge link = edge.getUnderlyingEdge().element();
      boolean highlighted = touches(link, hovered);
      clearStyle(edge, "secondary-link"); clearStyle(edge, "muted-link"); clearStyle(edge, "highlighted-link");
      if (highlighted) edge.addStyleClass("highlighted-link");
      else if (hovered != null) edge.addStyleClass("muted-link");
      else if (!touches(link, active)) edge.addStyleClass("secondary-link");
      // SmartGraph mirrors edge classes to arrows. Use visibility to keep
      // overview arrows outside hit testing regardless of edge opacity.
      if (edge.getStylableArrow() instanceof Node arrow) arrow.setVisible(highlighted);
    }
    updateLabels();
  }

  private void updateLabels() {
    if (graph == null || zoom == null) return;
    double scale = zoom.scaleFactorProperty().get();
    int count = vertices.size();
    double labelScale = count <= SPARSE_GRAPH_NODES ? .85 : Math.min(2.5, 1.4 + count / 40.0);
    // Inspect one note at a time. During motion, keep the active name in
    // place and defer the fit check until the layout settles.
    Path prominent = hovered == null ? active : hovered;
    Set<Path> fitted = hovered == null && !graph.automaticLayoutProperty().get() && scale >= labelScale
        ? fittingLabels() : Collections.emptySet();
    for (SmartGraphVertex<Path> vertex : graph.getSmartVertices()) {
      if (!(vertex.getStylableLabel() instanceof SmartLabel label)) continue;
      Path path = vertex.getUnderlyingVertex().element();
      boolean visible = path.equals(prominent) || fitted.contains(path);
      label.setVisible(visible);
      // SmartGraph reserves space for label text even when the label is hidden.
      label.setText(visible ? graphTitle(path) : "");
      if (path.equals(prominent)) label.toFront();
    }
  }

  private Set<Path> fittingLabels() {
    var ordered = new ArrayList<>(graph.getSmartVertices());
    ordered.sort(Comparator.comparing((SmartGraphVertex<Path> vertex) ->
        !vertex.getUnderlyingVertex().element().equals(active))
        .thenComparing(vertex -> vertex.getUnderlyingVertex().element()));
    Text measure = new Text();
    measure.setFont(FxTheme.textFont(FxTheme.TEXT_SIZE, false, false));
    Set<Path> fitted = new HashSet<>();
    List<Rectangle2D> occupied = new ArrayList<>();
    for (var vertex : ordered) {
      Path path = vertex.getUnderlyingVertex().element();
      measure.setText(graphTitle(path));
      double width = measure.getLayoutBounds().getWidth();
      double height = measure.getLayoutBounds().getHeight();
      double left = labelLeft(vertex.getPositionCenterX() - width / 2, width, graph.getWidth());
      double below = vertex.getPositionCenterY() + vertex.getRadius()
          + SmartGraphVertexNode.ATTACHED_LABEL_VERTICAL_OFFSET;
      double top = labelTop(below, height, vertex.getRadius(), graph.getHeight(), false);
      Rectangle2D box = new Rectangle2D(left - LABEL_CLEARANCE, top - LABEL_CLEARANCE,
          width + 2 * LABEL_CLEARANCE, height + 2 * LABEL_CLEARANCE);
      if (occupied.stream().noneMatch(box::intersects)) {
        fitted.add(path);
        occupied.add(box);
      }
    }
    return fitted;
  }

  private void positionLabel(SmartGraphVertex<Path> vertex) {
    if (!(vertex.getStylableLabel() instanceof SmartLabel label)) return;
    SmartGraphPanel<Path, NoteLinkIndex.Edge> owner = graph;
    label.translateXProperty().bind(Bindings.createDoubleBinding(() -> {
      double left = label.xProperty().get();
      return labelLeft(left, label.layoutWidthProperty().get(), owner.getWidth()) - left;
    }, label.xProperty(), label.layoutWidthProperty(), owner.widthProperty()));
    label.translateYProperty().bind(Bindings.createDoubleBinding(() -> {
      double below = label.yProperty().get();
      return labelTop(below, label.layoutHeightProperty().get(), vertex.getRadius(), owner.getHeight(),
          label.getStyleClass().contains("inspected-note")) - below;
    }, label.yProperty(), label.layoutHeightProperty(), label.getStyleClass(), owner.heightProperty()));
  }

  private static double labelLeft(double left, double width, double paneWidth) {
    return Math.max(LABEL_INSET, Math.min(left, paneWidth - LABEL_INSET - width));
  }

  private static double labelTop(double below, double height, double radius, double paneHeight, boolean inspect) {
    double above = below - height - 2 * (radius + SmartGraphVertexNode.ATTACHED_LABEL_VERTICAL_OFFSET);
    return above >= LABEL_INSET && (inspect || below + height > paneHeight - LABEL_INSET) ? above : below;
  }

  private static boolean touches(NoteLinkIndex.Edge edge, Path path) {
    return edge.from().equals(path) || edge.to().equals(path);
  }

  private static void noteStyle(SmartStylableNode node, String state) {
    if (node == null) return;
    clearStyle(node, "current-note"); clearStyle(node, "inspected-note");
    clearStyle(node, "related-note"); clearStyle(node, "muted-note");
    if (state != null) node.addStyleClass(state);
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
    if (!canAnimate() || FxMotion.reduced()) return;
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
    if (zoom == null) canvas.getChildren().setAll(placeholder, detail);
    else { zoom.setVisible(false); zoom.setManaged(false); }
  }
  private static String title(Path path) {
    String name = path.getFileName().toString();
    return name.replaceFirst("(?i)\\.(md|markdown)$", "");
  }
  private static String graphTitle(Path path) {
    String name = title(path);
    return name.length() > 22 ? name.substring(0, 20) + "…" : name;
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
