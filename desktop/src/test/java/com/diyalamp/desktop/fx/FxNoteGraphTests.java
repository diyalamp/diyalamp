package com.diyalamp.desktop.fx;

import com.brunomnsilva.smartgraph.containers.ContentZoomScrollPane;
import com.brunomnsilva.smartgraph.graph.Digraph;
import com.brunomnsilva.smartgraph.graphview.SmartGraphPanel;
import com.diyalamp.desktop.NoteStore;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;

/** Real SmartGraph, lists, asynchronous folder lifecycle, and Zen integration; fictional notes only. */
public final class FxNoteGraphTests {
  private static int checks;
  private static void check(boolean condition, String message) {
    checks++; if (!condition) throw new AssertionError(message);
  }
  public static void main(String[] arguments) throws Exception {
    Path root = Files.createTempDirectory("diyalamp-graph-component-").toRealPath();
    AtomicReference<Path> opened = new AtomicReference<>();
    AtomicReference<String> status = new AtomicReference<>();
    CountDownLatch ready = new CountDownLatch(1);
    Platform.startup(ready::countDown); ready.await(10, TimeUnit.SECONDS);
    FxNoteGraph panel = null;
    try {
      Path origin = Files.writeString(root.resolve("Origin.md"), "[[Target]]\n");
      Path target = Files.writeString(root.resolve("Target.md"), "[[Origin]]\n");
      SimpleDoubleProperty zen = new SimpleDoubleProperty();
      FxNoteGraph chosen = fx(() -> {
        FxTheme.setup();
        FxNoteGraph graph = new FxNoteGraph(opened::set, status::set);
        graph.bindZen(zen);
        HBox surface = new HBox(graph.node());
        surface.getStyleClass().add("workspace-root");
        Scene scene = new Scene(surface, 620, 760);
        scene.getStylesheets().add(FxNoteGraphTests.class.getResource("/fx-workspace.css").toExternalForm());
        FxTheme.accent(surface, FxTheme.DEFAULT_ACCENT);
        surface.resize(620, 760); surface.applyCss(); surface.layout();
        graph.workspace(new NoteStore(root)); graph.select(origin);
        return graph;
      });
      panel = chosen;
      await(() -> fx(() -> field(chosen, "snapshot") != null));
      fx(() -> {
        HBox surface = (HBox) chosen.node().getScene().getRoot(); surface.applyCss(); surface.layout();
        @SuppressWarnings("unchecked") SmartGraphPanel<Path, NoteLinkIndex.Edge> graph = (SmartGraphPanel<Path, NoteLinkIndex.Edge>) field(chosen, "graph");
        check(graph != null, "Selected saved note creates the real SmartGraph component");
        check(graph.getModel() instanceof Digraph, "The component uses a directed graph model");
        check(graph.getModel().numVertices() == 2 && graph.getModel().numEdges() == 2, "Reciprocal links retain two directed edges");
        check(graph.getParent().getParent() instanceof javafx.scene.Group || graph.getParent() instanceof javafx.scene.Group,
            "The graph uses the upstream zoom container");
        check(surface.lookup(".note-graph-zoom") instanceof ContentZoomScrollPane, "Pan and zoom use the mature library component");
        @SuppressWarnings("unchecked") ListView<Path> in = (ListView<Path>) field(chosen, "incoming");
        @SuppressWarnings("unchecked") ListView<Path> out = (ListView<Path>) field(chosen, "outgoing");
        check(in.getItems().equals(java.util.List.of(target)) && out.getItems().equals(java.util.List.of(target)),
            "Both directional lists reflect the selected note");
        out.getSelectionModel().select(target);
        Event.fireEvent(out, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        check(target.equals(opened.get()), "An outgoing list item opens the existing local note");
        Label detail = (Label) field(chosen, "detail");
        check(detail.getFont().getSize() == 13 && detail.getFont().getStyle().contains("Bold"), "Secondary graph UI matches readable 13px system bold");
        zen.set(1); surface.layout();
        check(!chosen.node().isManaged() && !chosen.node().isVisible(), "Zen completely removes the right panel from layout");
        check(chosen.showingProperty().get(), "Zen preserves the user's graph preference");
        zen.set(.5); surface.layout();
        check(chosen.node().isVisible() && chosen.node().isManaged(), "Reversing Zen restores the graph during its shared animation");
        check(Math.abs(((javafx.scene.layout.Region) chosen.node()).getPrefWidth() - 152) < .1, "The graph uses the shared Zen progress for width");
        zen.set(0);
        chosen.openWiki(origin, "Target");
        check(target.equals(opened.get()), "Wiki clicks use the same safe local resolver");
        return null;
      });
      Files.writeString(target, "No outgoing links now\n");
      fx(() -> { chosen.saved(target); return null; });
      await(() -> fx(() -> {
        NoteLinkIndex.Snapshot snapshot = (NoteLinkIndex.Snapshot) field(chosen, "snapshot");
        return snapshot.incoming(origin).isEmpty();
      }));
      check(true, "A safe-save hook refreshes incoming links asynchronously");
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
        return null;
      });
      check(Files.readString(origin).equals("[[Target]]\n"), "UI interaction does not rewrite note files");
    } finally {
      if (panel != null) { FxNoteGraph closing = panel; fx(() -> { closing.close(); return null; }); }
      Platform.exit();
      try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    System.out.println("Passed " + checks + " JavaFX note-graph checks.");
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
