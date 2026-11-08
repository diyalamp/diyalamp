package com.diyalamp.desktop.fx;

import atlantafx.base.controls.Tab;
import atlantafx.base.controls.TabLine;
import atlantafx.base.controls.TabSkin;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/** AtlantaFX headers reordered through its permutation API during a native drag gesture. */
final class FxDocumentTabs extends TabLine {
  private final Region insertionGuide = new Region();
  private final Runnable revealAfterLayout = this::revealSelectedTab;
  private final PauseTransition revealAfterOpening = new PauseTransition(ANIMATION_SPEED);
  private Timeline reorderMotion;
  private Scene revealScene;

  FxDocumentTabs() {
    // The library's mouse-only gesture cannot become an external native drag halfway through.
    // Native DND owns the gesture; the maintained reorder API still owns the tab/skin permutation.
    setTabDragPolicy(Tab.DragPolicy.FIXED);
    insertionGuide.getStyleClass().add("tab-insertion-guide");
    insertionGuide.setManaged(false);
    insertionGuide.setMouseTransparent(true);
    insertionGuide.setVisible(false);
    applyMotionPreference();
    widthProperty().addListener((observable, oldWidth, newWidth) -> queueSelectedTabReveal());
    getSelectionModel().selectedItemProperty().addListener(observable -> queueSelectedTabReveal());
    getTabs().addListener((ListChangeListener<Tab>) change -> {
      while (change.next()) {
        if (change.wasAdded() && getAnimated()) {
          revealAfterOpening.playFromStart();
          break;
        }
      }
    });
    revealAfterOpening.setOnFinished(event -> queueSelectedTabReveal());
    sceneProperty().addListener((observable, previous, next) -> {
      cancelQueuedReveal();
      if (next == null) revealAfterOpening.stop();
      else queueSelectedTabReveal();
    });
  }

  int insertionAt(double sceneX) {
    Parent strip = strip();
    if (strip == null) return getTabs().size();
    double x = strip.sceneToLocal(sceneX, 0).getX();
    Bounds viewport = viewport(strip);
    int insertion = getTabs().size();
    for (TabSkin skin : headers(strip)) {
      double left = Math.max(viewport.getMinX(), skin.getLayoutX());
      double right = Math.min(viewport.getMaxX(), skin.getLayoutX() + skin.getWidth());
      if (left >= right) continue;
      int index = getTabs().indexOf(skin.getTab());
      if (x < (left + right) / 2) return index;
      insertion = index + 1;
    }
    return insertion;
  }

  void showInsertion(int insertion) {
    Parent strip = strip();
    if (strip == null || getTabs().isEmpty()) { clearInsertion(); return; }
    boolean atEnd = insertion >= getTabs().size();
    Tab target = atEnd ? getTabs().getLast() : getTabs().get(insertion);
    TabSkin header = headers(strip).stream().filter(skin -> skin.getTab() == target).findFirst().orElse(null);
    if (header == null) { clearInsertion(); return; }
    Bounds viewport = viewport(strip);
    double edge = header.getLayoutX() + (atEnd ? header.getWidth() : 0);
    double x = Math.max(viewport.getMinX() + 1, Math.min(viewport.getMaxX() - 1, edge));
    var anchor = sceneToLocal(strip.localToScene(x, header.getLayoutY()));
    if (insertionGuide.getParent() == null) getChildren().add(insertionGuide);
    insertionGuide.resizeRelocate(Math.round(anchor.getX()) - 1, anchor.getY(), 2, header.getHeight());
    insertionGuide.setVisible(true);
  }

  void clearInsertion() {
    insertionGuide.setVisible(false);
  }

  boolean moveToInsertion(Tab tab, int insertion) {
    int from = getTabs().indexOf(tab);
    if (from < 0) return false;
    int to = Math.max(0, Math.min(getTabs().size() - 1, insertion > from ? insertion - 1 : insertion));
    if (from != to) animateReorder(from, to);
    getSelectionModel().select(tab);
    queueSelectedTabReveal();
    return true;
  }

  private void animateReorder(int from, int to) {
    List<TabSkin> headers = headers(strip());
    Map<Tab, Double> oldPositions = new IdentityHashMap<>();
    for (TabSkin skin : headers) oldPositions.put(skin.getTab(), skin.getLayoutX() + skin.getTranslateX());
    settleReorder(headers);
    // AtlantaFX recreates the affected headers on an external permutation. Skip its
    // hide/show sequence here so those headers can slide without collapsing first.
    boolean animated = getAnimated();
    setAnimated(false);
    try { reorderTabs(from, to); }
    finally { setAnimated(animated); }
    if (oldPositions.isEmpty() || FxMotion.reduced()) return;
    applyCss(); layout();
    List<KeyValue> settle = new ArrayList<>();
    for (TabSkin skin : headers(strip())) {
      Double old = oldPositions.get(skin.getTab());
      if (old == null || Math.abs(old - skin.getLayoutX()) < .5) continue;
      skin.setTranslateX(old - skin.getLayoutX());
      settle.add(new KeyValue(skin.translateXProperty(), 0, Interpolator.EASE_OUT));
    }
    if (settle.isEmpty()) return;
    reorderMotion = new Timeline(new KeyFrame(Duration.millis(160), settle.toArray(KeyValue[]::new)));
    reorderMotion.setOnFinished(event -> reorderMotion = null);
    reorderMotion.play();
  }

  void applyMotionPreference() {
    boolean reduced = FxMotion.reduced();
    setAnimated(!reduced);
    if (reduced) {
      revealAfterOpening.stop();
      if (reorderMotion != null) settleReorder(headers(strip()));
    }
  }

  private void settleReorder(List<TabSkin> headers) {
    if (reorderMotion != null) {
      reorderMotion.stop();
      reorderMotion = null;
    }
    for (TabSkin skin : headers) skin.setTranslateX(0);
  }

  private void queueSelectedTabReveal() {
    if (revealScene != null || getScene() == null) return;
    revealScene = getScene();
    revealScene.addPostLayoutPulseListener(revealAfterLayout);
    Platform.requestNextPulse();
  }

  private void cancelQueuedReveal() {
    if (revealScene == null) return;
    revealScene.removePostLayoutPulseListener(revealAfterLayout);
    revealScene = null;
  }

  private void revealSelectedTab() {
    cancelQueuedReveal();
    Tab selected = getSelectionModel().getSelectedItem();
    Parent strip = strip();
    if (selected == null || strip == null || !getTabResizePolicy().isScrollable()) return;
    Bounds visible = viewport(strip);
    if (visible.getWidth() <= 0) return;
    for (TabSkin header : headers(strip)) {
      if (header.getTab() != selected) continue;
      double left = header.getLayoutX(), right = left + header.getWidth();
      double delta = left < visible.getMinX() || header.getWidth() > visible.getWidth()
          ? visible.getMinX() - left : Math.min(0, visible.getMaxX() - right);
      if (Math.abs(delta) > .5) {
        // Use AtlantaFX's existing horizontal scroll path, including its clipping and bounds.
        strip.fireEvent(new ScrollEvent(ScrollEvent.SCROLL, 0, 0, 0, 0, false, false, false,
            false, false, false, delta, 0, delta, 0,
            ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
            ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null));
      }
      return;
    }
  }

  private Parent strip() {
    return lookup(".tabs") instanceof Parent pane ? pane : null;
  }

  private static Bounds viewport(Parent strip) {
    return strip.getClip() == null ? strip.getLayoutBounds() : strip.getClip().getBoundsInParent();
  }

  private List<TabSkin> headers(Parent strip) {
    if (strip == null) return List.of();
    return strip.getChildrenUnmodifiable().stream().filter(TabSkin.class::isInstance).map(TabSkin.class::cast)
        .filter(skin -> !skin.isClosing() && skin.isVisible() && getTabs().contains(skin.getTab())).toList();
  }
}
