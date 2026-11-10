package com.diyalamp.desktop.fx;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.image.ImageView;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Translate;
import javafx.util.Duration;

/** Animates the visible rows around the native TreeView layout after a branch changes. */
final class FxTreeMotion<T> {
  private static final Duration DURATION = Duration.millis(150);

  static <T> void install(TreeView<T> tree) { new FxTreeMotion<>(tree); }

  private final TreeView<T> tree;
  private final Map<TreeItem<?>, Double> before = new IdentityHashMap<>();
  private final List<TreeItem<?>> branches = new ArrayList<>();
  private final List<RowMotion> active = new ArrayList<>();
  private final Runnable afterLayout = this::animate;
  private final EventHandler<TreeItem.TreeModificationEvent<T>> branchChanged = this::branchChanged;
  private final ChangeListener<Boolean> motionPreference = (observable, previous, reduced) -> {
    if (reduced) cancel();
  };
  private Scene pending;
  private Group departing;
  private TreeItem<?> departingBranch;
  private Timeline departure;

  private FxTreeMotion(TreeView<T> tree) {
    this.tree = tree;
    tree.rootProperty().addListener((observable, oldRoot, newRoot) -> {
      cancel();
      if (oldRoot != null) {
        oldRoot.removeEventFilter(TreeItem.branchExpandedEvent(), branchChanged);
        oldRoot.removeEventFilter(TreeItem.branchCollapsedEvent(), branchChanged);
      }
      watch(newRoot);
    });
    watch(tree.getRoot());
    tree.sceneProperty().addListener(observable -> cancel());
    tree.visibleProperty().addListener((observable, previous, visible) -> { if (!visible) cancel(); });
    tree.addEventFilter(ScrollEvent.SCROLL, event -> cancel());
    Platform.getPreferences().reducedMotionProperty().addListener(new WeakChangeListener<>(motionPreference));
  }

  private void watch(TreeItem<T> root) {
    if (root == null) return;
    root.addEventFilter(TreeItem.branchExpandedEvent(), branchChanged);
    root.addEventFilter(TreeItem.branchCollapsedEvent(), branchChanged);
  }

  private void branchChanged(TreeItem.TreeModificationEvent<T> event) {
    if (FxMotion.reduced() || !tree.isVisible() || tree.getScene() == null || tree.getEditingItem() != null) return;
    TreeItem<T> branch = event.getTreeItem();
    if (pending == null) {
      List<TreeCell<?>> rows = visibleRows();
      for (TreeCell<?> cell : rows) before.put(cell.getTreeItem(), y(cell));
      if (!before.containsKey(branch)) {
        before.clear();
        return;
      }
      clearDeparture();
      if (event.wasCollapsed()) animateTailDeparture(branch, rows);
      // Sample the painted positions first so reversing a running fold has no jump.
      stopRows();
      pending = tree.getScene();
      pending.addPostLayoutPulseListener(afterLayout);
      Platform.requestNextPulse();
    }
    if (!branches.contains(branch)) branches.add(branch);
  }

  private void animate() {
    pending.removePostLayoutPulseListener(afterLayout);
    pending = null;
    if (departingBranch != null && departingBranch.isExpanded()) clearDeparture();
    if (!FxMotion.reduced()) {
      double firstChanged = branches.stream().filter(before::containsKey).mapToDouble(before::get).min().orElse(0);
      for (TreeCell<?> cell : visibleRows()) {
        TreeItem<?> item = cell.getTreeItem();
        Double previous = before.get(item);
        if (previous != null && previous > firstChanged) {
          double distance = previous - y(cell);
          double limit = cell.getHeight() * 4;
          if (Math.abs(distance) > .5) active.add(new RowMotion(cell, Math.clamp(distance, -limit, limit), false));
        } else if (previous == null && newlyRevealed(item)) {
          active.add(new RowMotion(cell, -6, true));
        }
      }
    }
    before.clear();
    branches.clear();
  }

  private boolean newlyRevealed(TreeItem<?> item) {
    for (TreeItem<?> parent = item.getParent(); parent != null; parent = parent.getParent())
      if (parent.isExpanded() && branches.contains(parent)) return true;
    return false;
  }

  private static boolean descendsFrom(TreeItem<?> item, TreeItem<?> ancestor) {
    for (TreeItem<?> parent = item.getParent(); parent != null; parent = parent.getParent())
      if (parent == ancestor) return true;
    return false;
  }

  /** When no following row is visible, let the departing pixels finish the fold. */
  private void animateTailDeparture(TreeItem<T> branch, List<TreeCell<?>> rows) {
    if (!(tree.getParent() instanceof Pane host) || rows.isEmpty()
        || !descendsFrom(rows.getLast().getTreeItem(), branch)) return;
    Node viewport = tree.lookup(".virtual-flow .clipped-container");
    if (viewport == null) return;
    TreeCell<?> first = rows.stream().filter(row -> descendsFrom(row.getTreeItem(), branch)).findFirst().orElseThrow();
    Bounds visible = viewport.localToScene(viewport.getLayoutBounds());
    Bounds firstRow = first.localToScene(first.getLayoutBounds());
    TreeCell<?> last = rows.getLast();
    Bounds lastRow = last.localToScene(last.getLayoutBounds());
    double top = Math.max(visible.getMinY(), firstRow.getMinY());
    double width = visible.getWidth(), height = Math.min(visible.getMaxY(), lastRow.getMaxY()) - top;
    if (width < 1 || height < 1 || width > 640 || height > 960) return;

    // The filter runs before the native skin relayouts the collapsing branch. Capture one small
    // strip of the current viewport rather than retaining or duplicating interactive cells.
    var viewportOrigin = viewport.getParent().sceneToLocal(visible.getMinX(), top);
    SnapshotParameters parameters = new SnapshotParameters();
    parameters.setFill(Color.TRANSPARENT);
    parameters.setViewport(new Rectangle2D(viewportOrigin.getX(), viewportOrigin.getY(), width, height));
    ImageView pixels = new ImageView(viewport.snapshot(parameters, null));
    pixels.setFitWidth(width);
    pixels.setFitHeight(height);
    departing = new Group(pixels);
    departing.getStyleClass().add("tree-departure");
    departing.setClip(new Rectangle(width, height));
    departing.setManaged(false);
    departing.setMouseTransparent(true);
    var origin = host.sceneToLocal(visible.getMinX(), top);
    departing.relocate(origin.getX(), origin.getY());
    host.getChildren().add(departing);
    departingBranch = branch;
    departure = new Timeline(new KeyFrame(DURATION,
        new KeyValue(pixels.translateYProperty(), -Math.min(height, 24), FxMotion.SIDEBAR_EASE),
        new KeyValue(pixels.opacityProperty(), 0, FxMotion.SIDEBAR_EASE)));
    departure.setOnFinished(event -> clearDeparture());
    departure.play();
  }

  private void clearDeparture() {
    if (departure == null) return;
    departure.stop();
    if (departing.getParent() instanceof Pane host) host.getChildren().remove(departing);
    departing = null;
    departingBranch = null;
    departure = null;
  }

  private List<TreeCell<?>> visibleRows() {
    if (!(tree.lookup(".virtual-flow") instanceof VirtualFlow<?> flow)
        || flow.getFirstVisibleCell() == null || flow.getLastVisibleCell() == null) return List.of();
    List<TreeCell<?>> rows = new ArrayList<>();
    int last = flow.getLastVisibleCell().getIndex();
    for (int i = flow.getFirstVisibleCell().getIndex(); i <= last; i++)
      if (flow.getVisibleCell(i) instanceof TreeCell<?> cell && cell.getTreeItem() != null && !cell.isEmpty())
        rows.add(cell);
    return rows;
  }

  private double y(TreeCell<?> cell) {
    return tree.sceneToLocal(cell.localToScene(0, 0)).getY();
  }

  private void cancel() {
    if (pending != null) pending.removePostLayoutPulseListener(afterLayout);
    pending = null;
    before.clear();
    branches.clear();
    stopRows();
    clearDeparture();
  }

  private void stopRows() {
    for (RowMotion row : List.copyOf(active)) row.stop();
  }

  private final class RowMotion {
    private final TreeCell<?> cell;
    private final Translate translate;
    private final double opacity;
    private final boolean reveal;
    private final Timeline animation;
    private final ChangeListener<TreeItem<?>> recycled = (observable, previous, item) -> stop();

    RowMotion(TreeCell<?> cell, double distance, boolean reveal) {
      this.cell = cell;
      this.reveal = reveal;
      translate = new Translate(0, distance);
      cell.getTransforms().add(translate);
      opacity = cell.getOpacity();
      var movement = new KeyValue(translate.yProperty(), 0, FxMotion.SIDEBAR_EASE);
      if (reveal) {
        cell.setOpacity(0);
        animation = new Timeline(new KeyFrame(Duration.millis(42), new KeyValue(cell.opacityProperty(), 0)),
            new KeyFrame(DURATION, movement, new KeyValue(cell.opacityProperty(), opacity, FxMotion.SIDEBAR_EASE)));
      } else {
        animation = new Timeline(new KeyFrame(DURATION, movement));
      }
      cell.treeItemProperty().addListener(recycled);
      animation.setOnFinished(event -> stop());
      animation.play();
    }

    private void stop() {
      animation.stop();
      cell.treeItemProperty().removeListener(recycled);
      cell.getTransforms().remove(translate);
      if (reveal) cell.setOpacity(opacity);
      active.remove(this);
    }
  }
}
