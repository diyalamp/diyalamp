package com.diyalamp.desktop.fx;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Toggle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** A two-choice radio group with a decorative selection indicator. */
final class FxSegmentedControl extends HBox {
  private final RadioButton first, second;
  private final Region indicator = new Region();
  private final DoubleProperty position = new SimpleDoubleProperty();
  private Timeline motion;
  private final ChangeListener<Boolean> motionPreference;

  FxSegmentedControl(double spacing, RadioButton first, RadioButton second) {
    super(spacing);
    this.first = first;
    this.second = second;
    indicator.getStyleClass().add("segmented-indicator");
    indicator.setManaged(false);
    indicator.setMouseTransparent(true);
    for (RadioButton choice : new RadioButton[] {first, second}) {
      choice.setMaxWidth(Double.MAX_VALUE);
      setHgrow(choice, Priority.ALWAYS);
    }
    getChildren().addAll(indicator, first, second);
    position.addListener(observable -> layoutIndicator());

    var selection = first.getToggleGroup().selectedToggleProperty();
    moveTo(selection.get());
    selection.addListener((observable, previous, selected) -> moveTo(selected));
    motionPreference = (observable, previous, reduced) -> {
      if (reduced && motion != null) moveTo(selection.get());
    };
    Platform.getPreferences().reducedMotionProperty().addListener(new WeakChangeListener<>(motionPreference));
  }

  private void moveTo(Toggle selected) {
    double start = position.get();
    if (motion != null) { motion.stop(); motion = null; }
    boolean wasVisible = indicator.isVisible();
    indicator.setVisible(selected != null);
    if (selected == null) return;
    double end = selected == second ? 1 : 0;
    if (!wasVisible || start == end || first.getWidth() == 0 || getScene() == null || FxMotion.reduced()) {
      position.set(end);
      return;
    }
    position.set(start);
    motion = new Timeline(new KeyFrame(FxMotion.SIDEBAR_TIME,
        new KeyValue(position, end, FxMotion.SIDEBAR_EASE)));
    motion.setOnFinished(event -> motion = null);
    motion.play();
  }

  @Override protected void layoutChildren() {
    super.layoutChildren();
    layoutIndicator();
  }

  private void layoutIndicator() {
    double progress = position.get();
    double left = first.getLayoutX() + (second.getLayoutX() - first.getLayoutX()) * progress;
    double width = first.getWidth() + (second.getWidth() - first.getWidth()) * progress;
    indicator.resizeRelocate(left, first.getLayoutY(), width, first.getHeight());
  }
}
