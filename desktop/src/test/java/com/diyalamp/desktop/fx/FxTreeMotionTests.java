package com.diyalamp.desktop.fx;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.transform.Translate;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Checks that folder motion follows native layout and never leaves a recycled row offset. */
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
      branch.setExpanded(true);
      check(tree.getRow(child) == 1, "The child row must exist immediately after expansion");
      afterLayout(() -> {
        check(offset(cell(after)) < 0, "Expansion must move the following row down from its old position");
        check(cell(child).getOpacity() < 1, "Newly shown notes must fade in");
        PauseTransition progress = new PauseTransition(Duration.millis(65));
        progress.setOnFinished(event -> collapse());
        progress.play();
      });
    }
  }

  private void collapse() {
    branch.setExpanded(false);
    afterLayout(() -> {
      check(tree.getRow(child) == -1, "Collapse must remove the child row from navigation");
      TreeCell<?> moving = cell(after);
      check(offset(moving) > 0, "Collapse must move the following row up from its painted position");
      tree.scrollTo(35);
      afterLayout(() -> {
        check(offset(moving) == 0, "Recycling a cell must clear its transform");
        tree.scrollTo(0);
        root.getChildren().setAll(List.of(branch));
        branch.setExpanded(true);
        afterLayout(this::tailCollapse);
      });
    });
  }

  private void tailCollapse() {
    branch.setExpanded(false);
    check(tree.getRow(child) == -1, "A departing row must leave the accessible tree immediately");
    afterLayout(() -> {
      Group departing = (Group) scene.getRoot().lookup(".tree-departure");
      check(departing != null && !departing.isManaged() && departing.isMouseTransparent(),
          "The last branch must animate its departing pixels without retaining an interactive row");
      ImageView pixels = (ImageView) departing.getChildren().getFirst();
      var capture = pixels.getImage();
      check(capture.getHeight() == 36, "Collapse must snapshot only the disappearing row");
      var colors = new java.util.HashSet<Integer>();
      for (int x = 0; x < capture.getWidth(); x++)
        for (int y = 0; y < capture.getHeight(); y++) colors.add(capture.getPixelReader().getArgb(x, y));
      check(colors.size() > 5, "The departing strip must contain the original note, not blank space");
      branch.setExpanded(true);
      check(scene.getRoot().lookup(".tree-departure") == null,
          "Reversing a collapse must remove the departing strip");
      branch.setExpanded(false);
      reduced();
    });
  }

  private void reduced() {
    System.setProperty("diyalamp.reduceMotion", "true");
    branch.setExpanded(true);
    afterLayout(() -> {
      check(offset(cell(branch)) == 0 && offset(cell(child)) == 0 && cell(child).getOpacity() == 1,
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

  private static double offset(TreeCell<?> cell) {
    return cell.getTransforms().stream().filter(Translate.class::isInstance)
        .map(Translate.class::cast).mapToDouble(Translate::getY).sum();
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
