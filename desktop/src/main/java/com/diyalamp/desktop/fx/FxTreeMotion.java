package com.diyalamp.desktop.fx;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.ScrollEvent;
import javafx.util.Duration;

/** Fades in newly visible descendants while the native tree owns row positions and hit targets. */
final class FxTreeMotion<T> {
  private static final Duration DURATION = Duration.millis(120);

  static <T> void install(TreeView<T> tree) { new FxTreeMotion<>(tree); }

  private final TreeView<T> tree;
  private final List<TreeItem<?>> branches = new ArrayList<>();
  private final Map<TreeCell<?>, RowFade> active = new IdentityHashMap<>();
  private final Runnable afterLayout = this::reveal;
  private final EventHandler<TreeItem.TreeModificationEvent<T>> branchChanged = this::branchChanged;
  private final ChangeListener<Boolean> motionPreference = (observable, previous, reduced) -> {
    if (reduced) cancel();
  };
  private Scene pending;

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
    if (event.wasCollapsed()) {
      cancel();
      return;
    }
    if (FxMotion.reduced() || !tree.isVisible() || tree.getScene() == null || tree.getEditingItem() != null) return;
    TreeItem<T> branch = event.getTreeItem();
    if (visibleRows().stream().noneMatch(cell -> cell.getTreeItem() == branch)) return;
    branches.add(branch);
    if (pending != null) return;
    pending = tree.getScene();
    pending.addPostLayoutPulseListener(afterLayout);
    Platform.requestNextPulse();
  }

  private void reveal() {
    pending.removePostLayoutPulseListener(afterLayout);
    pending = null;
    if (!FxMotion.reduced() && tree.getEditingItem() == null)
      for (TreeCell<?> cell : visibleRows())
        if (!active.containsKey(cell) && newlyRevealed(cell.getTreeItem())) active.put(cell, new RowFade(cell));
    branches.clear();
  }

  private boolean newlyRevealed(TreeItem<?> item) {
    for (TreeItem<?> parent = item.getParent(); parent != null; parent = parent.getParent())
      if (parent.isExpanded() && branches.contains(parent)) return true;
    return false;
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

  private void cancel() {
    if (pending != null) pending.removePostLayoutPulseListener(afterLayout);
    pending = null;
    branches.clear();
    for (RowFade fade : List.copyOf(active.values())) fade.stop();
  }

  private final class RowFade {
    private final TreeCell<?> cell;
    private final double opacity;
    private final FadeTransition fade;
    private final ChangeListener<TreeItem<?>> recycled = (observable, previous, item) -> stop();

    RowFade(TreeCell<?> cell) {
      this.cell = cell;
      opacity = cell.getOpacity();
      cell.setOpacity(opacity * .55);
      fade = new FadeTransition(DURATION, cell);
      fade.setInterpolator(FxMotion.SIDEBAR_EASE);
      fade.setToValue(opacity);
      cell.treeItemProperty().addListener(recycled);
      fade.setOnFinished(event -> stop());
      fade.play();
    }

    private void stop() {
      fade.stop();
      cell.treeItemProperty().removeListener(recycled);
      cell.setOpacity(opacity);
      active.remove(cell);
    }
  }
}
