package com.diyalamp.desktop.fx;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Checks that folder motion leaves native row positions, selection and recycled cells intact. */
public final class FxTreeMotionTests {
  public static void main(String[] args) throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Platform.startup(() -> {
      Stage stage = new Stage();
      try {
        new FxTreeMotionTests(stage, failure, done).start();
      } catch (Throwable error) {
        stage.close();
        failure.set(error);
        done.countDown();
      }
    });
    if (!done.await(10, TimeUnit.SECONDS)) throw new AssertionError("Tree motion tests timed out");
    Platform.exit();
    if (failure.get() != null) throw new AssertionError("Tree motion regression", failure.get());
    System.out.println("Passed folder row motion checks.");
  }

  private final Stage stage;
  private final AtomicReference<Throwable> failure;
  private final CountDownLatch done;
  private final TreeItem<String> root = new TreeItem<>("Workspace");
  private final TreeItem<String> branch = new TreeItem<>("Folder");
  private final TreeItem<String> child = new TreeItem<>("Child note");
  private final TreeItem<String> after = new TreeItem<>("Following note");
  private final TreeView<String> tree = new TreeView<>(root);
  private final Scene scene = new Scene(new StackPane(tree), 280, 290);

  private FxTreeMotionTests(Stage stage, AtomicReference<Throwable> failure, CountDownLatch done) {
    this.stage = stage;
    this.failure = failure;
    this.done = done;
  }

  private void start() {
    branch.getChildren().add(child);
    root.getChildren().addAll(List.of(branch, after));
    for (int i = 0; i < 35; i++) root.getChildren().add(new TreeItem<>("Extra note " + i));
    root.setExpanded(true);
    tree.setShowRoot(false);
    tree.setFixedCellSize(36);
    FxTreeMotion.install(tree);
    stage.setScene(scene);
    stage.show();
    scene.getRoot().applyCss();
    scene.getRoot().layout();
    if (FxMotion.reduced()) reduced();
    else {
      tree.getSelectionModel().select(after);
      branch.setExpanded(true);
      check(tree.getRow(child) == 1, "The child row must exist immediately after expansion");
      afterLayout(() -> {
        check(adjacent(child, after) && cell(after).getOpacity() == 1
                && tree.getSelectionModel().getSelectedItem() == after,
            "Expansion must keep the selected row and its actions at the native layout position");
        check(cell(child).getOpacity() < 1, "Newly shown notes must fade in");
        PauseTransition progress = new PauseTransition(Duration.millis(65));
        progress.setOnFinished(event -> collapse());
        progress.play();
      });
    }
  }

  private void collapse() {
    TreeCell<?> faded = cell(child);
    branch.setExpanded(false);
    check(faded.getOpacity() == 1, "Collapse cancels an in-flight reveal before the cell can be recycled");
    afterLayout(() -> {
      check(tree.getRow(child) == -1 && adjacent(branch, after)
              && tree.getSelectionModel().getSelectedItem() == after,
          "Collapse must remove children without moving the selection or painting over adjacent rows");
      branch.setExpanded(true);
      afterLayout(this::scroll);
    });
  }

  private void scroll() {
    TreeCell<?> faded = cell(child);
    check(faded.getOpacity() < 1, "Reopened children can fade in again");
    tree.scrollTo(35);
    afterLayout(() -> {
      check(faded.getOpacity() == 1, "Recycling a cell must restore its opacity");
      tree.scrollTo(0);
      root.getChildren().setAll(List.of(branch));
      afterLayout(this::tailCollapse);
    });
  }

  private void tailCollapse() {
    branch.setExpanded(false);
    check(tree.getRow(child) == -1, "A departing row must leave the accessible tree immediately");
    afterLayout(() -> {
      check(scene.getRoot().lookup(".tree-departure") == null,
          "Collapsing a tail branch must not leave a painted duplicate of its action icons");
      reduced();
    });
  }

  private void reduced() {
    System.setProperty("diyalamp.reduceMotion", "true");
    branch.setExpanded(true);
    afterLayout(() -> {
      check(adjacent(branch, child) && cell(child).getOpacity() == 1,
          "Reduced motion must lay out expanded branches without animation");
      stage.close();
      done.countDown();
    });
  }

  private void afterLayout(Runnable check) {
    Runnable listener = new Runnable() {
      @Override public void run() {
        scene.removePostLayoutPulseListener(this);
        Platform.runLater(() -> {
          try { check.run(); }
          catch (Throwable error) {
            failure.set(error);
            stage.close();
            done.countDown();
          }
        });
      }
    };
    scene.addPostLayoutPulseListener(listener);
    Platform.requestNextPulse();
  }

  private TreeCell<?> cell(TreeItem<String> item) {
    return tree.lookupAll(".tree-cell").stream().filter(node -> node instanceof TreeCell<?> cell
        && cell.getTreeItem() == item).map(TreeCell.class::cast).findFirst().orElseThrow();
  }

  private boolean adjacent(TreeItem<String> first, TreeItem<String> second) {
    double top = cell(first).localToScene(0, 0).getY(), bottom = cell(second).localToScene(0, 0).getY();
    return Math.abs(bottom - top - tree.getFixedCellSize()) < .5;
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
