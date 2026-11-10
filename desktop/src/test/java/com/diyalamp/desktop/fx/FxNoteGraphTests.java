package com.diyalamp.desktop.fx;

import com.brunomnsilva.smartgraph.containers.ContentZoomScrollPane;
import com.brunomnsilva.smartgraph.graph.Digraph;
import com.brunomnsilva.smartgraph.graphview.SmartGraphPanel;
import com.brunomnsilva.smartgraph.graphview.SmartGraphVertex;
import com.brunomnsilva.smartgraph.graphview.SmartLabel;
import com.diyalamp.desktop.NoteStore;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.Animation;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.AccessibleAttribute;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/** Real SmartGraph, retained layout, interaction, asynchronous folder lifecycle; fictional notes only. */
public final class FxNoteGraphTests {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] arguments) throws Exception {
    Path root = Files.createTempDirectory("diyalamp-graph-component-").toRealPath();
    AtomicReference<Path> opened = new AtomicReference<>();
    AtomicReference<String> status = new AtomicReference<>();
    AtomicReference<Stage> window = new AtomicReference<>();
    AtomicReference<SmartGraphPanel<Path, NoteLinkIndex.Edge>> retainedGraph = new AtomicReference<>();
    AtomicReference<ContentZoomScrollPane> retainedZoom = new AtomicReference<>();
    AtomicReference<javafx.geometry.Point2D> beforeMotion = new AtomicReference<>();
    CountDownLatch ready = new CountDownLatch(1);
    Platform.startup(ready::countDown); ready.await(10, TimeUnit.SECONDS);
    FxNoteGraph panel = null;
    try {
      Path origin = Files.writeString(root.resolve("Origin.md"), "[[Target]]\n");
      Path target = Files.writeString(root.resolve("Target.md"), "[[Origin]]\n");
      Path source = Files.writeString(root.resolve("Source.md"), "[[Origin]]\n");
      Path isolated = Files.writeString(root.resolve("Isolated.md"), "No links\n");
      Path dense = writeDenseGraph(root);
      FxNoteGraph chosen = fx(() -> {
        FxTheme.setup();
        FxNoteGraph graph = new FxNoteGraph(opened::set, status::set);
        check(!graph.showingProperty().get() && !graph.node().isManaged(), "Connections default closed to leave room for writing");
        var count = (Label) field(graph, "linkedCount");
        check(!count.isVisible() && !count.isManaged(), "No active note leaves the unique link count hidden");
        graph.showingProperty().set(true);
        HBox surface = new HBox(graph.node());
        surface.getStyleClass().add("workspace-root");
        Scene scene = new Scene(surface, 620, 760);
        scene.getStylesheets().add(FxNoteGraphTests.class.getResource("/fx-workspace.css").toExternalForm());
        FxTheme.accent(surface, FxTheme.DEFAULT_ACCENT);
        Stage stage = new Stage(); stage.setScene(scene); stage.show(); window.set(stage);
        surface.resize(620, 760); surface.applyCss(); surface.layout();
        graph.workspace(new NoteStore(root)); graph.select(origin);
        return graph;
      });
      panel = chosen;
      await(() -> fx(() -> field(chosen, "snapshot") != null));
      await(() -> fx(() -> ((javafx.scene.layout.Region) chosen.node()).getPrefWidth() == 304));
      await(() -> fx(() -> (boolean) field(chosen, "graphInitialized")));
      fx(() -> {
        HBox surface = (HBox) chosen.node().getScene().getRoot(); surface.applyCss(); surface.layout();
        @SuppressWarnings("unchecked") SmartGraphPanel<Path, NoteLinkIndex.Edge> graph = (SmartGraphPanel<Path, NoteLinkIndex.Edge>) field(chosen, "graph");
        check(graph != null, "Selected saved note creates the real SmartGraph component");
        retainedGraph.set(graph); retainedZoom.set((ContentZoomScrollPane) field(chosen, "zoom"));
        check(graph.getBackground().getFills().getLast().getFill().equals(FxTheme.EDITOR_BACKGROUND),
            "Actual graph canvas must use the VS Code default dark editor color");
        javafx.scene.layout.VBox inspector = (javafx.scene.layout.VBox) field(chosen, "content");
        check(inspector.getBackground().getFills().getLast().getFill().equals(FxTheme.EDITOR_BACKGROUND),
            "Actual inspector must use the VS Code default dark sidebar color");
        var graphLabels = graph.lookupAll(".vertex-label").stream()
            .filter(node -> node instanceof javafx.scene.text.Text).toList();
        check(!graphLabels.isEmpty(), "Real diagram labels must be present for typography checks");
        check(graphLabels.stream().allMatch(node -> node instanceof javafx.scene.text.Text text
            && text.getFont().getSize() == FxTheme.TEXT_SIZE && text.getFont().getName().equals("Inter Regular")),
            "Diagram labels use the same Inter16 size as the whole workspace");
        check(graph.getModel() instanceof Digraph, "The component uses a directed graph model");
        check(graph.getModel().numVertices() == 3 && graph.getModel().numEdges() == 3,
            "Reciprocal links and an inbound-only note retain three directed edges");
        check(graph.getParent().getParent() instanceof javafx.scene.Group || graph.getParent() instanceof javafx.scene.Group,
            "The graph uses the upstream zoom container");
        check(surface.lookup(".note-graph-zoom") instanceof ContentZoomScrollPane, "Pan and zoom use the mature library component");
        @SuppressWarnings("unchecked") ListView<Path> in = (ListView<Path>) field(chosen, "incoming");
        @SuppressWarnings("unchecked") ListView<Path> out = (ListView<Path>) field(chosen, "outgoing");
        check(in.getItems().equals(java.util.List.of(source, target)) && out.getItems().equals(java.util.List.of(target)),
            "Both directional lists reflect the selected note");
        var count = (Label) field(chosen, "linkedCount");
        check(count.isVisible() && count.getAccessibleText().equals("2 unique linked notes")
                && count.getTooltip().getText().contains("counts once"),
            "A reciprocal note contributes once to the accessible summary count");
        check(in.getAccessibleText().equals("Incoming links to the current note")
            && out.getAccessibleText().equals("Outgoing links from the current note"),
            "Connection lists must have distinct accessible names that explain their direction");
        check(in.getAccessibleHelp().startsWith("Notes that link to the current note.")
            && out.getAccessibleHelp().startsWith("Notes linked from the current note.")
            && in.getAccessibleHelp().contains("Enter to open") && out.getAccessibleHelp().contains("Enter to open"),
            "List help must explain each direction and its keyboard action");
        check(((Label) field(chosen, "incomingTitle")).getLabelFor() == in
            && ((Label) field(chosen, "outgoingTitle")).getLabelFor() == out,
            "Each visible directional heading must label its own list");
        check(in.queryAccessibleAttribute(AccessibleAttribute.TEXT).equals(in.getAccessibleText())
            && out.queryAccessibleAttribute(AccessibleAttribute.TEXT).equals(out.getAccessibleText()),
            "Actual JavaFX accessibility queries must expose the useful directional names");
        out.getSelectionModel().select(target);
        Event.fireEvent(out, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        check(target.equals(opened.get()), "An outgoing list item opens the existing local note");
        opened.set(null); in.getSelectionModel().select(source);
        Event.fireEvent(in, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        check(source.equals(opened.get()), "Enter on an incoming-only row must open that existing source note");
        Label detail = (Label) field(chosen, "detail");
        check(detail.getFont().getSize() == 16 && detail.getFont().getName().equals("Inter Regular"),
            "Inspector detail uses readable Inter 16 regular");
        check(!detail.isManaged(), "A healthy graph does not repeat instructional status copy");
        var row = out.lookupAll(".list-cell").stream()
            .filter(node -> node instanceof javafx.scene.control.ListCell<?> cell && target.equals(cell.getItem()))
            .map(node -> (javafx.scene.control.ListCell<?>) node).findFirst().orElseThrow();
        check(row.getFont().getSize() == 16 && row.getFont().getName().equals("Inter Regular"),
            "Incoming and outgoing notes use the shared Inter 16 regular face");
        check(in.getFixedCellSize() == 36 && out.getFixedCellSize() == 36,
            "Connection rows share the Files sidebar's 36 px row rhythm");
        var actions = chosen.node().lookup(".graph-heading-row").lookupAll(".button").stream()
            .filter(Button.class::isInstance).map(Button.class::cast).toList();
        check(actions.size() == 1 && actions.getFirst().getAccessibleText().equals("Refresh note connections")
            && actions.getFirst().getWidth() == 28 && actions.getFirst().getHeight() == 28,
            "The Connections header keeps only its compact refresh action");
        chosen.openWiki(origin, "Target");
        check(target.equals(opened.get()), "Wiki clicks use the same safe local resolver");
        return null;
      });
      checkViewportFit(root, origin, source);
      double[] chosenDividers = fx(() -> sectionLayout(chosen, origin, source, isolated));
      checkSectionMotion(chosen, chosenDividers);
      Object beforeRefresh = fx(() -> field(chosen, "snapshot"));
      fx(() -> { chosen.refresh(); return null; });
      await(() -> fx(() -> field(chosen, "snapshot") != beforeRefresh));
      fx(() -> {
        layout(chosen);
        check(Arrays.equals(chosenDividers, ((SplitPane) field(chosen, "sections")).getDividerPositions()),
            "Refreshing the same lists must retain the dragged divider positions");
        return null;
      });
      fx(() -> {
        SmartGraphPanel<Path, NoteLinkIndex.Edge> graph = retainedGraph.get();
        graph.setAutomaticLayout(false);
        var originVertex = graph.getModel().vertices().stream().filter(vertex -> vertex.element().equals(origin)).findFirst().orElseThrow();
        var sourceVertex = graph.getModel().vertices().stream().filter(vertex -> vertex.element().equals(source)).findFirst().orElseThrow();
        graph.setVertexPosition(originVertex, 70, 65); graph.setVertexPosition(sourceVertex, 170, 90);
        var originNode = graph.getSmartVertices().stream().filter(vertex -> vertex.getUnderlyingVertex() == originVertex).findFirst().orElseThrow();
        ContentZoomScrollPane zoom = retainedZoom.get(); zoom.scaleFactorProperty().set(1.75);
        chosen.select(target);
        check(field(chosen, "graph") == graph && field(chosen, "zoom") == zoom,
            "Selecting a note must retain the real graph and upstream pan/zoom container");
        check(graph.getModel().numVertices() == 2 && graph.getSmartVertices().contains(originNode),
            "A local-graph change must remove only irrelevant nodes, preserving shared visual vertices");
        check(graph.getVertexPositionX(originVertex) == 70 && graph.getVertexPositionY(originVertex) == 65,
            "Changing selection must not reset a retained node to circular placement");
        check(zoom.scaleFactorProperty().get() == 1.75, "Selection must preserve the user's zoom level");
        chosen.select(origin);
        var restoredSource = graph.getModel().vertices().stream().filter(vertex -> vertex.element().equals(source)).findFirst().orElseThrow();
        check(graph.getVertexPositionX(restoredSource) == 170 && graph.getVertexPositionY(restoredSource) == 90,
            "Returning to a local graph must restore the last position of a temporarily removed note");
        Event.fireEvent((Node) originNode, mouse(MouseEvent.MOUSE_ENTERED));
        check(!graph.lookupAll(".related-note").isEmpty() && !graph.lookupAll(".highlighted-link").isEmpty(),
            "Hovering a note must emphasize its existing neighboring nodes and links");
        Event.fireEvent((Node) originNode, mouse(MouseEvent.MOUSE_EXITED));
        graph.applyCss();
        check(field(chosen, "hovered") == null && graph.lookupAll(".related-note").isEmpty(),
            "Leaving a note must clear temporary connection emphasis: hovered=" + field(chosen, "hovered")
                + ", nodes=" + graph.lookupAll(".related-note").stream().map(node -> node.getClass().getSimpleName() + node.getStyleClass()).toList());
        check(graph.automaticLayoutProperty().get(), "Interaction must reheat the library's real force-directed layout");
        beforeMotion.set(position(graph, origin));
        return null;
      });
      await(() -> fx(() -> position(retainedGraph.get(), origin).distance(beforeMotion.get()) > .25));
      check(true, "The retained SmartGraph vertices must actually move under the library's force simulation");
      await(() -> fx(() -> !retainedGraph.get().automaticLayoutProperty().get()));
      check(true, "The force simulation settles and stops after interaction becomes idle");
      fx(() -> {
        var sourceNode = graphVertex(retainedGraph.get(), source);
        Event.fireEvent((Node) sourceNode, mouse(MouseEvent.MOUSE_ENTERED));
        check(!retainedGraph.get().automaticLayoutProperty().get(),
            "Inspecting a settled node should leave its position stable");
        Event.fireEvent((Node) sourceNode, mouse(MouseEvent.MOUSE_EXITED));
        Event.fireEvent(retainedGraph.get(), mouse(MouseEvent.MOUSE_PRESSED));
        check(retainedGraph.get().automaticLayoutProperty().get(), "A new pointer interaction must restart the settled force simulation");
        chosen.showingProperty().set(false);
        check(!retainedGraph.get().automaticLayoutProperty().get(), "Hiding the inspector must stop the force timer immediately");
        Event.fireEvent(retainedGraph.get(), mouse(MouseEvent.MOUSE_PRESSED));
        check(!retainedGraph.get().automaticLayoutProperty().get(), "A hidden inspector cannot restart its animation timer");
        chosen.showingProperty().set(true);
        return null;
      });
      await(() -> fx(() -> ((javafx.scene.layout.Region) chosen.node()).getPrefWidth() == 304));
      fx(() -> {
        check(field(chosen, "graph") == retainedGraph.get() && field(chosen, "zoom") == retainedZoom.get()
            && retainedZoom.get().scaleFactorProperty().get() == 1.75,
            "Closing and reopening the inspector must preserve its graph and zoom state");
        window.get().hide();
        check(!retainedGraph.get().automaticLayoutProperty().get(), "A hidden application window must stop graph physics");
        window.get().show();
        check(retainedGraph.get().automaticLayoutProperty().get(), "Restoring the window may reheat the visible graph");
        return null;
      });
      Files.writeString(target, "No outgoing links now\n");
      fx(() -> { chosen.saved(target); return null; });
      await(() -> fx(() -> {
        NoteLinkIndex.Snapshot snapshot = (NoteLinkIndex.Snapshot) field(chosen, "snapshot");
        return snapshot.incoming(origin).equals(java.util.List.of(source));
      }));
      check(true, "A safe-save hook refreshes incoming links asynchronously");
      check(fx(() -> field(chosen, "graph") == retainedGraph.get() && field(chosen, "zoom") == retainedZoom.get()),
          "A safe save must update the existing model without rebuilding the visualization or viewport");
      fx(() -> {
        inspectLabelCollision(chosen, retainedGraph.get(), retainedZoom.get(), origin, target, source);
        inspectDenseGraph(chosen, retainedGraph.get(), retainedZoom.get(), dense);
        return null;
      });
      Path next = Files.createDirectory(root.resolve("another-folder"));
      Path other = Files.writeString(next.resolve("Other.md"), "No links\n");
      fx(() -> { chosen.workspace(new NoteStore(next)); chosen.select(other); return null; });
      await(() -> fx(() -> {
        NoteLinkIndex.Snapshot snapshot = (NoteLinkIndex.Snapshot) field(chosen, "snapshot");
        return snapshot != null && snapshot.root().equals(next);
      }));
      fx(() -> {
        @SuppressWarnings("unchecked") ListView<Path> in = (ListView<Path>) field(chosen, "incoming");
        check(in.getItems().isEmpty(), "Changing folders cannot display another workspace's incoming links");
        check(field(chosen, "graph") != retainedGraph.get() && !retainedGraph.get().automaticLayoutProperty().get(),
            "Changing workspaces must dispose the prior force simulation and its local graph state");
        return null;
      });
      check(Files.readString(origin).equals("[[Target]]\n"), "UI interaction does not rewrite note files");
    } finally {
      if (panel != null) { FxNoteGraph closing = panel; fx(() -> {
        closing.close();
        if (retainedGraph.get() != null) check(!retainedGraph.get().automaticLayoutProperty().get(), "Closed graphs cannot retain a running force timer");
        if (window.get() != null) window.get().hide();
        return null;
      }); }
      Platform.exit();
      try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    System.out.println("Passed " + checks + " JavaFX note-graph checks.");
  }

  private static void checkViewportFit(Path root, Path active, Path neighbor) throws Exception {
    String key = "diyalamp.reduceMotion";
    String prior = System.getProperty(key);
    System.setProperty(key, "true");
    AtomicReference<Stage> stage = new AtomicReference<>();
    FxNoteGraph panel = fx(() -> {
      FxNoteGraph inspector = new FxNoteGraph(path -> {}, message -> {});
      inspector.showingProperty().set(true);
      HBox surface = new HBox(inspector.node());
      surface.getStyleClass().add("workspace-root");
      Scene scene = new Scene(surface, 68, 490);
      scene.getStylesheets().add(FxNoteGraphTests.class.getResource("/fx-workspace.css").toExternalForm());
      FxTheme.accent(surface, FxTheme.DEFAULT_ACCENT);
      Stage window = new Stage();
      stage.set(window);
      window.setScene(scene);
      window.show();
      inspector.workspace(new NoteStore(root));
      inspector.select(active);
      return inspector;
    });
    try {
      await(() -> fx(() -> {
        SmartGraphPanel<?, ?> graph = (SmartGraphPanel<?, ?>) field(panel, "graph");
        return graph != null && graph.getWidth() > 0 && graph.getWidth() < 64;
      }));
      fx(() -> { stage.get().setWidth(576); ((Region) panel.node()).setPrefWidth(576); return null; });
      await(() -> fx(() -> {
        SmartGraphPanel<?, ?> graph = (SmartGraphPanel<?, ?>) field(panel, "graph");
        return graph.getWidth() > 500 && (boolean) field(panel, "graphInitialized");
      }));
      javafx.geometry.Point2D neighborRatio = fx(() -> {
        @SuppressWarnings("unchecked") var graph = (SmartGraphPanel<Path, NoteLinkIndex.Edge>) field(panel, "graph");
        var center = position(graph, active);
        var linked = position(graph, neighbor);
        check(!graph.automaticLayoutProperty().get()
                && center.distance(graph.getWidth() / 2, graph.getHeight() / 2) < 1 && center.distance(linked) > 20,
            "The first usable viewport must center and separate notes without graph animation");
        return new javafx.geometry.Point2D(linked.getX() / graph.getWidth(), linked.getY() / graph.getHeight());
      });
      fx(() -> { stage.get().setWidth(250); return null; });
      await(() -> fx(() -> ((SmartGraphPanel<?, ?>) field(panel, "graph")).getWidth() < 250));
      fx(() -> {
        @SuppressWarnings("unchecked") var graph = (SmartGraphPanel<Path, NoteLinkIndex.Edge>) field(panel, "graph");
        var linked = position(graph, neighbor);
        check(position(graph, active).distance(graph.getWidth() / 2, graph.getHeight() / 2) < 1
                && neighborRatio.distance(linked.getX() / graph.getWidth(), linked.getY() / graph.getHeight()) < .01,
            "A resized inspector keeps the same relative graph layout without relying on animation");
        return null;
      });
    } finally {
      if (prior == null) System.clearProperty(key); else System.setProperty(key, prior);
      fx(() -> { panel.close(); stage.get().hide(); return null; });
    }
  }

  private static Path writeDenseGraph(Path root) throws java.io.IOException {
    StringBuilder links = new StringBuilder();
    for (int number = 0; number < 9; number++) {
      String name = "Neighbor " + number;
      links.append("[[").append(name).append("]]\n");
      String body = switch (number) {
        case 0 -> "[[Neighbor 1]]";
        case 1 -> "[[Neighbor 2]]";
        case 8 -> "[[Dense Hub]]";
        default -> "A linked note.";
      };
      Files.writeString(root.resolve(name + ".md"), body);
    }
    return Files.writeString(root.resolve("Dense Hub.md"), links);
  }

  private static void inspectDenseGraph(FxNoteGraph panel, SmartGraphPanel<Path, NoteLinkIndex.Edge> graph,
      ContentZoomScrollPane zoom, Path hub) {
    zoom.scaleFactorProperty().set(1);
    panel.select(hub); layout(panel);
    check(graph.getModel().numVertices() == 10 && graph.getModel().numEdges() == 12,
        "The overview preserves every directed link between visible notes, including reciprocal links");
    check(countEdges(graph, "secondary-link") == 2,
        "Neighbor cross-links receive the secondary edge style");
    check(graph.lookupAll(".arrow").stream().noneMatch(Node::isVisible),
        "Direction markers stay quiet in the overview");
    check(visibleLabels(graph) == 1 && ((Node) graphVertex(graph, hub).getStylableLabel()).isVisible(),
        "At overview scale, a crowded graph shows its current note name");

    var neighbor = graphVertex(graph, hub.resolveSibling("Neighbor 1.md"));
    Event.fireEvent((Node) neighbor, mouse(MouseEvent.MOUSE_ENTERED)); graph.applyCss();
    check(visibleLabels(graph) == 1 && ((Node) neighbor.getStylableLabel()).isVisible(),
        "Hovering a dense node shows only its label, with no names to overlap it");
    check(countEdges(graph, "highlighted-link") == 3 && countEdges(graph, "muted-link") == 9,
        "Inspection emphasizes only the hovered note's real incoming and outgoing links");
    check(graph.lookupAll(".arrow").stream().filter(Node::isVisible).count() == 3,
        "Only arrows incident to the inspected node are visible");
    Event.fireEvent((Node) neighbor, mouse(MouseEvent.MOUSE_EXITED));
    graph.setAutomaticLayout(false);
    var ordered = graph.getModel().vertices().stream().sorted(Comparator.comparing(vertex -> vertex.element())).toList();
    for (int index = 0; index < ordered.size(); index++)
      graph.setVertexPosition(ordered.get(index), 70 + 130 * (index % 2), 12 + 28 * (index / 2));
    zoom.scaleFactorProperty().set(2);
    layout(panel);
    var bounds = graph.getSmartVertices().stream().map(vertex -> (Node) vertex.getStylableLabel())
        .filter(Node::isVisible).map(Node::getBoundsInParent).toList();
    check(bounds.size() > 1, "Zooming into a settled graph reveals additional names");
    for (int index = 0; index < bounds.size(); index++)
      for (int other = index + 1; other < bounds.size(); other++)
        check(!bounds.get(index).intersects(bounds.get(other)), "Visible names fit without overlap when zoomed");
    zoom.scaleFactorProperty().set(1);
    check(visibleLabels(graph) == 1, "Zooming back out restores the uncluttered overview");
  }

  private static void inspectLabelCollision(FxNoteGraph panel, SmartGraphPanel<Path, NoteLinkIndex.Edge> graph,
      ContentZoomScrollPane zoom, Path active, Path overlapping, Path separate) {
    graph.setAutomaticLayout(false);
    SmartGraphVertex<Path> activeNode = graphVertex(graph, active), overlapNode = graphVertex(graph, overlapping);
    SmartGraphVertex<Path> separateNode = graphVertex(graph, separate);
    graph.setVertexPosition(activeNode.getUnderlyingVertex(), 110, 55);
    graph.setVertexPosition(overlapNode.getUnderlyingVertex(), 115, 55);
    graph.setVertexPosition(separateNode.getUnderlyingVertex(), 210, 105);
    SmartLabel activeLabel = (SmartLabel) activeNode.getStylableLabel();
    SmartLabel overlapLabel = (SmartLabel) overlapNode.getStylableLabel();
    SmartLabel separateLabel = (SmartLabel) separateNode.getStylableLabel();
    overlapLabel.setText("Target"); overlapLabel.setVisible(true); layout(panel);
    check(activeLabel.getBoundsInParent().intersects(overlapLabel.getBoundsInParent()),
        "The fixture places the real rendered labels in collision");

    zoom.scaleFactorProperty().set(1); layout(panel);
    check(activeLabel.isVisible() && separateLabel.isVisible() && !overlapLabel.isVisible(),
        "The active label wins and a separated name can still fit");
    check(!activeLabel.getBoundsInParent().intersects(separateLabel.getBoundsInParent()),
        "The remaining visible label bounds do not overlap");
    var beforeHover = position(graph, overlapping);
    Event.fireEvent((Node) overlapNode, mouse(MouseEvent.MOUSE_ENTERED)); layout(panel);
    check(overlapLabel.isVisible() && visibleLabels(graph) == 1 && position(graph, overlapping).equals(beforeHover),
        "Hover reveals the rejected name without moving its node");
    Event.fireEvent((Node) overlapNode, mouse(MouseEvent.MOUSE_EXITED)); layout(panel);
    check(activeLabel.isVisible() && separateLabel.isVisible() && !overlapLabel.isVisible(),
        "Leaving hover restores the same fitted labels");
    graph.setAutomaticLayout(true);
    check(activeLabel.isVisible() && visibleLabels(graph) == 1,
        "Moving nodes keep only the active name visible");
    graph.setAutomaticLayout(false);

    Node grabbed = (Node) separateNode, underPointer = (Node) overlapNode;
    var start = position(graph, separate);
    Event.fireEvent(grabbed, dragMouse(MouseEvent.MOUSE_PRESSED, grabbed, 10, 10));
    Event.fireEvent(grabbed, dragMouse(MouseEvent.MOUSE_DRAGGED, grabbed, 35, 20));
    check(!position(graph, separate).equals(start), "The graph library still moves the grabbed node");
    Event.fireEvent(grabbed, mouse(MouseEvent.MOUSE_EXITED));
    Event.fireEvent(underPointer, mouse(MouseEvent.MOUSE_ENTERED));
    graph.setAutomaticLayout(false);
    zoom.scaleFactorProperty().set(1.5);
    check(separateLabel.isVisible() && visibleLabels(graph) == 1,
        "Dragging keeps only the grabbed name through pointer transitions, settling, and zoom");
    Event.fireEvent(grabbed, dragMouse(MouseEvent.MOUSE_RELEASED, underPointer, 35, 20));
    check(overlapLabel.isVisible() && visibleLabels(graph) == 1,
        "Release restores hover for the note actually under the pointer");
    Event.fireEvent(underPointer, mouse(MouseEvent.MOUSE_EXITED));
    check(activeLabel.isVisible() && separateLabel.isVisible() && !overlapLabel.isVisible(),
        "Normal fitted labels return when the pointer leaves after a drag");
  }

  private static SmartGraphVertex<Path> graphVertex(SmartGraphPanel<Path, NoteLinkIndex.Edge> graph, Path path) {
    return graph.getSmartVertices().stream()
        .filter(vertex -> vertex.getUnderlyingVertex().element().equals(path)).findFirst().orElseThrow();
  }

  private static long visibleLabels(SmartGraphPanel<Path, NoteLinkIndex.Edge> graph) {
    return graph.getSmartVertices().stream().map(vertex -> (Node) vertex.getStylableLabel())
        .filter(Node::isVisible).count();
  }

  private static long countEdges(SmartGraphPanel<Path, NoteLinkIndex.Edge> graph, String style) {
    return graph.getSmartEdges().stream().map(Node.class::cast)
        .filter(node -> node.getStyleClass().contains(style)).count();
  }

  private static double[] sectionLayout(FxNoteGraph graph, Path origin, Path source, Path isolated) throws Exception {
    layout(graph);
    var split = (SplitPane) field(graph, "sections");
    var summary = graph.node().lookup(".graph-summary");
    var heading = graph.node().lookup(".graph-heading-row");
    check(summary.getLayoutBounds().getHeight() == 40 && heading.getLayoutBounds().getHeight() == 40,
        "The active note and Connections title must use the shared header bands");
    check(split.getItems().size() == 3 && split.getDividers().size() == 2,
        "Three open sections need two native dividers");
    var heights = split.getItems().stream().map(node -> node.getLayoutBounds().getHeight()).toList();
    check(java.util.Collections.max(heights) - java.util.Collections.min(heights) <= 2,
        "All open sections must start at equal height: " + heights);
    double first = split.getDividerPositions()[0];
    dragFirstDivider(split, 32);
    layout(graph);
    double[] adjusted = split.getDividerPositions();
    check(adjusted[0] > first + .03, "Dragging the first divider must resize the graph");

    var incoming = disclosure(graph, "Incoming");
    var outgoing = disclosure(graph, "Outgoing");
    graph.select(source); layout(graph);
    check(!incoming.isSelected() && outgoing.isSelected() && split.getDividers().size() == 1,
        "An empty incoming list starts closed without blocking the remaining divider");
    double graphHeight = split.getItems().getFirst().getLayoutBounds().getHeight();
    double linksHeight = split.getItems().getLast().getLayoutBounds().getHeight();
    check(Math.abs(graphHeight - (linksHeight - incoming.getHeight())) <= 2,
        "Graph and the remaining open list split the space left by the collapsed header: " + graphHeight + ", " + linksHeight);
    first = split.getDividerPositions()[0];
    dragFirstDivider(split, 32); layout(graph);
    check(split.getDividerPositions()[0] > first + .03,
        "Dragging still resizes the graph and outgoing list with incoming collapsed");

    graph.select(isolated); layout(graph);
    check(!incoming.isSelected() && !outgoing.isSelected() && split.getDividers().isEmpty(),
        "Two empty lists leave the graph flexible and retain both headings");
    var count = (Label) field(graph, "linkedCount");
    check(count.isVisible() && count.getAccessibleText().equals("0 unique linked notes"),
        "An open note without any connections shows a zero count");
    incoming.fire(); layout(graph);
    check(incoming.isSelected() && ((ListView<?>) field(graph, "incoming")).isVisible()
        && split.getDividers().size() == 1, "A user can expand a zero-count list");

    graph.select(origin); layout(graph);
    check(Arrays.equals(adjusted, split.getDividerPositions()),
        "Returning to all three sections must recover the prior adjustment");
    return adjusted;
  }

  private static ToggleButton disclosure(FxNoteGraph graph, String title) {
    return graph.node().lookupAll(".graph-list-toggle").stream().map(ToggleButton.class::cast)
        .filter(button -> button.getAccessibleText().startsWith(title)).findFirst().orElseThrow();
  }

  private static void checkSectionMotion(FxNoteGraph graph, double[] dividers) throws Exception {
    var list = (ListView<?>) field(graph, "incoming");
    var outgoing = (ListView<?>) field(graph, "outgoing");
    var incoming = fx(() -> disclosure(graph, "Incoming"));
    var split = (SplitPane) field(graph, "sections");
    var fade = (Animation) field(field(graph, "incomingSection"), "fade");
    if (!fx(FxMotion::reduced)) {
      fx(() -> {
        incoming.fire();
        check(list.isVisible() && list.isManaged() && list.isMouseTransparent() && !list.isFocusTraversable()
                && Arrays.equals(dividers, split.getDividerPositions()),
            "A closing list fades in its original split space and stops accepting input");
        incoming.fire();
        return null;
      });
      await(() -> fx(() -> fade.getStatus() == Animation.Status.STOPPED));
      fx(() -> {
        check(incoming.isSelected() && list.isVisible() && list.isManaged() && list.isFocusTraversable()
                && list.getOpacity() == 1 && Arrays.equals(dividers, split.getDividerPositions()),
            "Reversing a collapse keeps the list open at the remembered divider positions");
        incoming.fire();
        return null;
      });
      await(() -> fx(() -> !list.isManaged()));
      fx(() -> {
        check(!list.isVisible() && !list.isFocusTraversable() && outgoing.isVisible()
                && split.getDividers().size() == 1,
            "A settled collapse removes the list from layout and navigation while Outgoing stays open");
        incoming.fire();
        check(list.isVisible() && list.isManaged() && list.getOpacity() < 1,
            "Opening a section starts a content reveal in its restored split space");
        return null;
      });
      await(() -> fx(() -> fade.getStatus() == Animation.Status.STOPPED));
    }
    fx(() -> {
      String key = "diyalamp.reduceMotion";
      String previous = System.getProperty(key);
      System.setProperty(key, "true");
      try {
        FxTheme.applyMotionPreference(graph.node().getScene().getRoot());
        incoming.fire();
        check(!list.isManaged() && !list.isVisible() && fade.getStatus() == Animation.Status.STOPPED,
            "Reduced motion closes the list immediately");
        incoming.fire();
        layout(graph);
        check(list.isVisible() && list.getOpacity() == 1
                && incoming.lookup(".graph-disclosure-chevron").getRotate() == 90,
            "Reduced motion opens at full opacity and sets the disclosure angle directly");
      } finally {
        if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        FxTheme.applyMotionPreference(graph.node().getScene().getRoot());
      }
      return null;
    });
  }

  private static void layout(FxNoteGraph graph) {
    var root = graph.node().getScene().getRoot(); root.applyCss(); root.layout();
  }

  private static void dragFirstDivider(SplitPane split, double delta) {
    Node divider = split.lookupAll(".split-pane-divider").stream()
        .min(Comparator.comparingDouble(node -> node.getBoundsInParent().getMinY())).orElseThrow();
    var center = divider.localToScene(divider.getLayoutBounds());
    for (var event : java.util.List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_RELEASED)) {
      double y = center.getCenterY() + (event == MouseEvent.MOUSE_PRESSED ? 0 : delta);
      Event.fireEvent(divider, new MouseEvent(event, center.getCenterX(), y, center.getCenterX(), y,
          MouseButton.PRIMARY, 1, false, false, false, false, true, false, false, false, false, true, null));
    }
  }

  private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type) {
    return new MouseEvent(type, 10, 10, 10, 10, MouseButton.PRIMARY, 1,
        false, false, false, false, true, false, false, false, false, true, null);
  }
  private static MouseEvent dragMouse(javafx.event.EventType<MouseEvent> type, Node picked, double x, double y) {
    return new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1, false, false, false, false,
        type != MouseEvent.MOUSE_RELEASED, false, false, false, false, false, new PickResult(picked, x, y));
  }
  private static javafx.geometry.Point2D position(SmartGraphPanel<Path, NoteLinkIndex.Edge> graph, Path path) {
    var vertex = graph.getModel().vertices().stream().filter(item -> item.element().equals(path)).findFirst().orElseThrow();
    return new javafx.geometry.Point2D(graph.getVertexPositionX(vertex), graph.getVertexPositionY(vertex));
  }
  private static Object field(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
  }
  private static <T> T fx(Callable<T> action) throws Exception {
    FutureTask<T> result = new FutureTask<>(action); Platform.runLater(result); return result.get(10, TimeUnit.SECONDS);
  }
  private static void await(Callable<Boolean> ready) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!ready.call()) {
      if (System.nanoTime() > deadline) throw new AssertionError("Graph index timed out");
      Thread.sleep(30);
    }
  }
}
